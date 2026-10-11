package com.vmartis.matchgateway.matcher.decision

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
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * AC-28 (stall half, ADR-009): a consumer that is connected but stuck past the processing deadline gives the message back
 * (the broker's own consumer timeout is >= 1 min, so the matcher bounds the processing itself). Shortened deadline: 2 s.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.processing-deadline=2s"],
)
@Import(TestcontainersConfiguration::class)
class MatcherStallIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    @Autowired lateinit var processor: DecisionProcessor

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    @Test
    fun `AC-28 a consumer stalled past the processing deadline rejects the message and it is decided after the stall ends`() {
        fx.enrol("t1", "s1", bytes(unit(11)))
        val id = fx.pending("t1", "s1", bytes(unit(11)))
        val processedBefore = fx.processingCount()
        val matchBefore = fx.counter("MATCH")

        fx.lockTenantSettings().use { lock ->
            val startedAt = System.nanoTime()
            fx.publish(id)
            fx.awaitBlockedOnTenantSettings(1)
            // The first attempt ends by itself (deadline), although nothing closed the connection and the lock is still held.
            fx.await("the first attempt to time out") { fx.processingCount() > processedBefore }
            val stalledFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertThat(stalledFor).isGreaterThanOrEqualTo(1_500L).isLessThan(10_000L)
            assertThat(fx.row(id).status).isEqualTo("PENDING")
            assertThat(fx.row(id).probeNull).isFalse()
            lock.release()
        }

        fx.awaitTerminal(id)
        fx.awaitQueuesEmpty()
        val r = fx.row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.probeNull).isTrue()
        // the timed-out attempt counted as a delivery: at least the failed attempt plus the one that decided
        assertThat(fx.processingCount() - processedBefore).isGreaterThanOrEqualTo(2L)
        fx.await("MATCH counter") { fx.counter("MATCH") - matchBefore == 1.0 }
    }

    @Test
    fun `AC-28 a stall that never ends uses up the 3 deliveries and ends FAILED MAX_ATTEMPTS`() {
        fx.enrol("t1", "s1", bytes(unit(12)))
        val id = fx.pending("t1", "s1", bytes(unit(12)))
        val processedBefore = fx.processingCount()

        fx.lockTenantSettings().use {
            fx.publish(id)
            // 3 deliveries x 2 s deadline; the lock is held until the job has been given up
            fx.awaitTerminal(id, 60)
        }

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.probeNull).isTrue()
        assertThat(fx.processingCount() - processedBefore).isEqualTo(3L)
    }

    @Test
    fun `AC-28 the processing transaction times out at the deadline and leaves the verification untouched`() {
        fx.enrol("t1", "s1", bytes(unit(13)))
        val id = fx.pending("t1", "s1", bytes(unit(13)))
        val processedBefore = fx.processingCount()
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()

        fx.lockTenantSettings().use {
            val started = System.nanoTime()
            assertThatThrownBy { processor.process(id) }.isInstanceOf(RuntimeException::class.java)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertThat(elapsedMs).isGreaterThanOrEqualTo(1_500L).isLessThan(10_000L)
        }

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("PENDING")
        assertThat(r.probeNull).isFalse()
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L) // the failed attempt is still timed
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
    }
}
