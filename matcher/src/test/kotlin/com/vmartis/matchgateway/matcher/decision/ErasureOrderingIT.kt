package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.unit
import com.vmartis.matchgateway.matcher.decision.infra.DecisionProcessor
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** AC-49, INV-5, D-8 (matcher side): an erasure committed while the matcher is mid-decision wins; a late write is a no-op. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.processing-deadline=30s"],
)
@Import(TestcontainersConfiguration::class)
class ErasureOrderingIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    @Autowired lateinit var processor: DecisionProcessor

    @Autowired lateinit var transactions: TransactionTemplate

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    /** Same statements as the gateway's SubjectStore.erase (ADR-007), in one transaction. */
    private fun erase(
        tenant: String,
        subject: String,
    ) {
        transactions.executeWithoutResult {
            jdbc
                .sql("DELETE FROM subject WHERE tenant_id = :tenant AND subject_id = :subject")
                .param("tenant", tenant)
                .param("subject", subject)
                .update()
            jdbc
                .sql(
                    """
                    UPDATE verification
                       SET status = 'FAILED', reason = 'SUBJECT_ERASED', probe = NULL, decided_at = now()
                     WHERE tenant_id = :tenant AND subject_id = :subject AND status = 'PENDING'
                    """.trimIndent(),
                ).param("tenant", tenant)
                .param("subject", subject)
                .update()
        }
    }

    @Test
    fun `AC-49 erasure committed while the consumer is mid-decision wins - the late write changes 0 rows and the job is acked`() {
        fx.enrol("t1", "s1", bytes(unit(1)))
        val id = fx.pending("t1", "s1", bytes(unit(1)))
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        fx.lockTenantSettings().use { lock ->
            fx.publish(id)
            fx.awaitBlockedOnTenantSettings(1) // the consumer already read the template and probe: it would decide MATCH
            erase("t1", "s1")
            assertThat(fx.row(id).status).isEqualTo("FAILED")
            lock.release()
            fx.await("the consumer to finish the attempt") { fx.processingCount() - processedBefore == 1L }
        }

        fx.awaitQueuesEmpty() // acked: not redelivered, not dead-lettered
        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_ERASED")
        assertThat(r.score).isNull()
        assertThat(r.threshold).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
        assertThat(fx.counters()).isEqualTo(before) // the matcher recorded nothing: it did not write the outcome
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
    }

    @Test
    fun `AC-49 a direct process blocked mid-decision loses against the erasure and returns false`() {
        fx.enrol("t1", "s1", bytes(unit(2)))
        val id = fx.pending("t1", "s1", bytes(unit(2)))
        val before = fx.counters()
        val pool = Executors.newSingleThreadExecutor()
        try {
            fx.lockTenantSettings().use { lock ->
                val a = pool.submit<Boolean> { processor.process(id) }
                fx.awaitBlockedOnTenantSettings(1)
                erase("t1", "s1")
                lock.release()

                assertThat(a.get(30, TimeUnit.SECONDS)).isFalse()
            }
        } finally {
            pool.shutdownNow()
        }

        assertThat(fx.row(id).reason).isEqualTo("SUBJECT_ERASED")
        assertThat(fx.row(id).status).isEqualTo("FAILED")
        assertThat(fx.counters()).isEqualTo(before)
    }

    @Test
    fun `AC-49 a message for a verification that was erased while pending is a no-op`() {
        fx.enrol("t1", "s1", bytes(unit(3)))
        val id = fx.pending("t1", "s1", bytes(unit(3)))
        erase("t1", "s1")
        val erased = fx.row(id)
        val before = fx.counters()
        val processedBefore = fx.processingCount()

        fx.publish(id)
        fx.await("the message to be processed") { fx.processingCount() > processedBefore }
        fx.awaitQueuesEmpty()

        assertThat(fx.row(id)).isEqualTo(erased)
        assertThat(erased.status).isEqualTo("FAILED")
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(processor.process(id)).isFalse()
        assertThat(processor.process(UUID.randomUUID())).isFalse()
    }
}
