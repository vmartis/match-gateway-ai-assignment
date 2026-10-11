package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.unit
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
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.UUID
import javax.sql.DataSource

/** AC-27: the dead-letter consumer turns a dead-lettered job into FAILED / MAX_ATTEMPTS and never drops one that cannot be written. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.dead-letter-retry-delay=100ms"],
)
@Import(TestcontainersConfiguration::class)
class DeadLetterListenerIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    @Test
    fun `a dead-lettered pending job ends FAILED MAX_ATTEMPTS with probe cleared, counted once and acked`() {
        val id = fx.pending("t1", "s1", bytes(unit(1)))
        val failedBefore = fx.counter("FAILED")
        val latencyBefore = fx.latencyCount()

        fx.publishDeadLetter(id)

        fx.awaitTerminal(id)
        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        fx.awaitQueuesEmpty()
        fx.await("FAILED counter") { fx.counter("FAILED") - failedBefore == 1.0 }
        assertThat(fx.latencyCount() - latencyBefore).isEqualTo(1L)
    }

    @Test
    fun `a dead letter for an already terminal verification is acked without any change or metric`() {
        fx.enrol("t1", "s1", bytes(unit(2)))
        val id = fx.submit("t1", "s1", bytes(unit(2)))
        fx.awaitTerminal(id)
        fx.awaitQueuesEmpty()
        fx.await("MATCH counter") { fx.counter("MATCH") >= 1.0 }
        val decided = fx.row(id)
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()

        fx.publishDeadLetter(id)
        fx.publishDeadLetter(UUID.randomUUID())
        fx.awaitQueuesEmpty()

        assertThat(fx.row(id)).isEqualTo(decided)
        assertThat(decided.status).isEqualTo("MATCH")
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
    }

    @Test
    fun `a dead letter whose write keeps failing is retried and never dropped until the write succeeds`() {
        val id = fx.pending("t1", "s1", bytes(unit(3)))
        // Only the MAX_ATTEMPTS write fails. A sequence counts the attempts because it is not rolled back with the failed write.
        jdbc.sql("create sequence test_dead_letter_attempts").update()
        jdbc
            .sql(
                """
                create function test_reject_max_attempts() returns trigger language plpgsql as
                $$ begin perform nextval('test_dead_letter_attempts'); raise exception 'injected write failure'; end $$
                """.trimIndent(),
            ).update()
        jdbc
            .sql(
                "create trigger test_reject_max_attempts before update on verification " +
                    "for each row when (new.reason = 'MAX_ATTEMPTS') execute function test_reject_max_attempts()",
            ).update()
        try {
            fx.publishDeadLetter(id)
            fx.await("at least 3 failed write attempts") {
                fx.count("select case when is_called then last_value else 0 end from test_dead_letter_attempts") >= 3
            }
            assertThat(fx.row(id).status).isEqualTo("PENDING")
            // still held by the broker, not dropped (rabbitmqctl queue statistics are refreshed periodically, hence the await)
            fx.await("dead letter held by the broker") { fx.queueMessages("matchgw.verifications.dead") == 1L }
        } finally {
            jdbc.sql("drop trigger test_reject_max_attempts on verification").update()
            jdbc.sql("drop function test_reject_max_attempts()").update()
            jdbc.sql("drop sequence test_dead_letter_attempts").update()
        }

        fx.awaitTerminal(id)
        fx.awaitQueuesEmpty()
        assertThat(fx.row(id).reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(fx.row(id).probeNull).isTrue()
    }
}
