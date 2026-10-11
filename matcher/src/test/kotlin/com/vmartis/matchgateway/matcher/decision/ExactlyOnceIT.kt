package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.common.messaging.Topology
import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.unit
import com.vmartis.matchgateway.matcher.decision.infra.DecisionProcessor
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.sql.SQLException
import java.util.Random
import java.util.UUID
import javax.sql.DataSource

/** AC-34, AC-35, AC-36, NFR-7, INV-1 (D-28): every verification gets exactly one terminal outcome, which is then immutable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration::class)
class ExactlyOnceIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    @Autowired lateinit var processor: DecisionProcessor

    @Autowired lateinit var registry: RabbitListenerEndpointRegistry

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    private val results = listOf("MATCH", "NO_MATCH", "FAILED")

    private fun rootSqlState(e: Throwable): String? {
        var c: Throwable? = e
        var state: String? = null
        while (c != null) {
            if (c is SQLException) state = c.sqlState
            c = c.cause
        }
        return state
    }

    private fun decidedMatchRow(): UUID {
        fx.enrol("t1", "s1", bytes(unit(1)))
        val id = fx.submit("t1", "s1", bytes(unit(1)))
        fx.awaitTerminal(id)
        assertThat(fx.row(id).status).isEqualTo("MATCH")
        return id
    }

    @Test
    fun `AC-34 the verification listener runs 3 concurrent consumers with manual acknowledgement`() {
        val container =
            registry.listenerContainers
                .filterIsInstance<SimpleMessageListenerContainer>()
                .single { Topology.QUEUE in it.queueNames }

        fx.await("3 active consumers") { container.activeConsumerCount == 3 }

        assertThat(container.acknowledgeMode).isEqualTo(AcknowledgeMode.MANUAL)
    }

    @Test
    fun `AC-34 500 verifications and 500 messages consumed by 3 concurrent consumers are decided exactly once each`() {
        val total = 500
        (0 until 20).forEach { fx.enrol("t1", "s$it", bytes(unit(it.toLong()))) }
        val rnd = Random(42)
        val before = fx.counters()
        val processedBefore = fx.processingCount()
        val ids =
            (0 until total).map { i ->
                when {
                    i % 10 == 0 -> fx.pending("t1", "ghost$i", bytes(unit(1000L + i)))
                    i % 2 == 0 -> fx.pending("t1", "s${i % 20}", bytes(unit((i % 20).toLong())))
                    else -> fx.pending("t1", "s${i % 20}", bytes(unit(5000L + rnd.nextInt(1000))))
                }
            }

        ids.forEach(fx::publish)
        fx.awaitAllTerminal(ids, 120)
        fx.awaitQueuesEmpty(60)

        assertThat(fx.count("select count(*) from verification where status <> 'PENDING'")).isEqualTo(total.toLong())
        assertThat(fx.count("select count(*) from verification where status = 'PENDING'")).isZero()
        assertThat(fx.count("select count(*) from verification where probe is not null")).isZero()
        assertThat(fx.count("select count(*) from verification where status = 'FAILED' and reason = 'SUBJECT_NOT_ENROLLED'"))
            .isEqualTo(50L)
        assertThat(fx.count("select count(*) from verification where status = 'FAILED' and reason = 'MAX_ATTEMPTS'")).isZero()
        assertThat(ids.map { fx.row(it).status }.toSet()).containsExactlyInAnyOrder("MATCH", "NO_MATCH", "FAILED")
        // each message processed once: no redelivery, no duplicate, nothing dead-lettered
        assertThat(fx.processingCount() - processedBefore).isEqualTo(total.toLong())
        // applied terminal transitions = 500, and the metric agrees with the database per result, not only in sum
        val byStatus =
            jdbc
                .sql("select status, count(*) as n from verification group by status")
                .query { rs, _ -> rs.getString("status") to rs.getLong("n").toDouble() }
                .list()
                .toMap()
        fx.await("counters to match the database", 30) { results.associateWith { fx.counter(it) - before.getValue(it) } == byStatus }
        assertThat(byStatus.values.sum()).isEqualTo(total.toDouble())
    }

    @Test
    fun `AC-35 stale writer blocked mid-decision writes nothing and is not counted when it resumes`() {
        fx.enrol("t1", "s1", bytes(unit(6)))
        val id = fx.pending("t1", "s1", bytes(unit(6)))
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        fx.lockTenantSettings().use { lock ->
            // A: the listener's consumer holds the job after it read the template, before its guarded write.
            fx.publish(id)
            fx.awaitBlockedOnTenantSettings(1)

            // B decides differently: the template is gone, so B ends FAILED / SUBJECT_NOT_ENROLLED without reading the threshold.
            fx.jdbc.sql("delete from template_version where tenant_id = 't1' and subject_id = 's1'").update()
            assertThat(processor.process(id)).isTrue()
            val decidedByB = fx.row(id)
            assertThat(decidedByB.status).isEqualTo("FAILED")
            assertThat(decidedByB.reason).isEqualTo("SUBJECT_NOT_ENROLLED")

            lock.release()
            // A finishes its decision (MATCH, score 1.0) but its guarded write changes 0 rows.
            fx.await("A to finish") { fx.processingCount() - processedBefore == 2L }
            fx.awaitQueuesEmpty()
            assertThat(fx.row(id)).isEqualTo(decidedByB)
        }

        fx.await("FAILED counter") { fx.counter("FAILED") - before.getValue("FAILED") == 1.0 }
        assertThat(fx.counters()).isEqualTo(before + ("FAILED" to before.getValue("FAILED") + 1.0))
        assertThat(fx.latencyCount() - latencyBefore).isEqualTo(1L)
    }

    @Test
    fun `AC-35 a duplicate publish of a decided id changes nothing and counts nothing`() {
        val id = decidedMatchRow()
        fx.awaitQueuesEmpty()
        fx.await("MATCH counter") { fx.counter("MATCH") >= 1.0 }
        val decided = fx.row(id)
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()
        // a re-decision would now give a different outcome (FAILED), so an overwrite cannot go unnoticed
        fx.jdbc.sql("delete from template_version").update()

        fx.publish(id)
        fx.publish(id)
        fx.await("both duplicates to be processed") { fx.processingCount() - processedBefore == 2L }
        fx.awaitQueuesEmpty()

        assertThat(fx.row(id)).isEqualTo(decided)
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
    }

    @Test
    fun `AC-36 a message for a terminal verification is acked on the first delivery and never dead-lettered`() {
        val ghost = fx.pending("t1", "ghost", bytes(unit(3)))
        assertThat(processor.process(ghost)).isTrue()
        val terminal = fx.row(ghost)
        assertThat(terminal.status).isEqualTo("FAILED")
        val before = fx.counters()
        val processedBefore = fx.processingCount()

        repeat(3) { fx.publish(ghost) }
        fx.await("the 3 messages to be processed") { fx.processingCount() - processedBefore == 3L }
        fx.awaitQueuesEmpty()

        // 3 messages, 3 processing calls: none was redelivered, so the delivery limit was never touched
        assertThat(fx.processingCount() - processedBefore).isEqualTo(3L)
        assertThat(fx.row(ghost)).isEqualTo(terminal)
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.queueMessages(Topology.DEAD_LETTER_QUEUE)).isZero()
    }

    @Test
    fun `AC-36 a message for an already terminal id is acked, never decided again and not a processing error`() {
        val id = decidedMatchRow()
        fx.awaitQueuesEmpty()
        val decided = fx.row(id)

        assertThat(processor.process(id)).isFalse() // no exception: the listener would reject it and count a delivery

        assertThat(fx.row(id)).isEqualTo(decided)
    }

    @Test
    fun `INV-1 raw update of a decided row is rejected by the database with check_violation`() {
        val id = decidedMatchRow()
        val before = fx.row(id)

        assertThatThrownBy {
            jdbc.sql("update verification set status = 'NO_MATCH', score = 0.0 where id = :id").param("id", id).update()
        }.isInstanceOf(DataAccessException::class.java)
            .satisfies({ assertThat(rootSqlState(it)).isEqualTo("23514") })

        assertThat(fx.row(id)).isEqualTo(before)
    }

    @Test
    fun `INV-1 raw update of the reason of a FAILED row is rejected`() {
        val id = fx.pending("t1", "ghost", bytes(unit(5)))
        assertThat(processor.process(id)).isTrue()
        val before = fx.row(id)
        assertThat(before.status).isEqualTo("FAILED")
        assertThat(before.reason).isEqualTo("SUBJECT_NOT_ENROLLED")

        assertThatThrownBy {
            jdbc.sql("update verification set reason = 'SUBJECT_ERASED' where id = :id").param("id", id).update()
        }.isInstanceOf(DataAccessException::class.java)
            .satisfies({ assertThat(rootSqlState(it)).isEqualTo("23514") })

        assertThat(fx.row(id)).isEqualTo(before)
    }

    @Test
    fun `INV-1 the immutability trigger does not block deleting a decided row`() {
        val id = decidedMatchRow()

        assertThat(jdbc.sql("delete from verification where id = :id").param("id", id).update()).isEqualTo(1)

        assertThat(fx.count("select count(*) from verification where id = '$id'")).isZero()
    }

    @Test
    fun `INV-1 flyway history contains migration version 2 terminal immutability`() {
        val versions = jdbc.sql("select version from flyway_schema_history where success").query(String::class.java).list()

        assertThat(versions).contains("1", "2")
    }
}
