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
import java.util.UUID
import javax.sql.DataSource

/** AC-24, AC-26, AC-27, AC-29, AC-35, AC-36, AC-51: DecisionProcessor called directly (the listeners are not started). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.rabbitmq.listener.simple.auto-startup=false"],
)
@Import(TestcontainersConfiguration::class)
class DecisionProcessorIT {
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
    fun `process writes the outcome and returns true, counting result and latency once`() {
        fx.enrol("t1", "s1", bytes(unit(1)))
        val id = fx.pending("t1", "s1", bytes(unit(1)))
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        assertThat(processor.process(id)).isTrue()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.probeNull).isTrue()
        assertThat(fx.counters()).isEqualTo(before + ("MATCH" to before.getValue("MATCH") + 1.0))
        assertThat(fx.latencyCount() - latencyBefore).isEqualTo(1L)
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
    }

    @Test
    fun `process of an already terminal verification returns false and only the processing timer moves`() {
        fx.enrol("t1", "s1", bytes(unit(1)))
        val id = fx.pending("t1", "s1", bytes(unit(1)))
        assertThat(processor.process(id)).isTrue()
        val decided = fx.row(id)
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()
        // would be a different outcome if it were decided again
        fx.jdbc.sql("delete from template_version").update()

        assertThat(processor.process(id)).isFalse()

        assertThat(fx.row(id)).isEqualTo(decided)
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
    }

    @Test
    fun `process of an unknown id returns false without an exception or a metric except the processing timer`() {
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        assertThat(processor.process(UUID.randomUUID())).isFalse()

        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
    }

    @Test
    fun `process without an active template writes FAILED SUBJECT_NOT_ENROLLED and returns true`() {
        val id = fx.pending("t1", "ghost", bytes(unit(2)))
        val failedBefore = fx.counter("FAILED")

        assertThat(processor.process(id)).isTrue()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(r.probeNull).isTrue()
        assertThat(fx.counter("FAILED") - failedBefore).isEqualTo(1.0)
    }

    @Test
    fun `process of a corrupt stored template throws, leaves the row pending with its probe and records only the processing attempt`() {
        fx.enrol("t1", "s1", bytes(unit(3).also { it[5] = Float.NaN }))
        val id = fx.pending("t1", "s1", bytes(unit(3)))
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        assertThatThrownBy { processor.process(id) }.isInstanceOf(RuntimeException::class.java)

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("PENDING")
        assertThat(r.probeNull).isFalse()
        assertThat(r.decided).isFalse()
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
    }

    @Test
    fun `process of a probe with a non-finite value throws and leaves the row pending`() {
        fx.enrol("t1", "s1", bytes(unit(4)))
        val id = fx.pending("t1", "s1", bytes(unit(4).also { it[0] = Float.NEGATIVE_INFINITY }))

        assertThatThrownBy { processor.process(id) }.isInstanceOf(RuntimeException::class.java)

        assertThat(fx.row(id).status).isEqualTo("PENDING")
        assertThat(fx.row(id).score).isNull()
    }

    @Test
    fun `failMaxAttempts writes FAILED MAX_ATTEMPTS and returns true, counting FAILED and latency once`() {
        val id = fx.pending("t1", "s1", bytes(unit(5)))
        val failedBefore = fx.counter("FAILED")
        val latencyBefore = fx.latencyCount()

        assertThat(processor.failMaxAttempts(id)).isTrue()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        assertThat(fx.counter("FAILED") - failedBefore).isEqualTo(1.0)
        assertThat(fx.latencyCount() - latencyBefore).isEqualTo(1L)
    }

    @Test
    fun `failMaxAttempts on a terminal or unknown verification returns false and changes nothing`() {
        fx.enrol("t1", "s1", bytes(unit(6)))
        val decidedId = fx.pending("t1", "s1", bytes(unit(6)))
        assertThat(processor.process(decidedId)).isTrue()
        val decided = fx.row(decidedId)
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()

        assertThat(processor.failMaxAttempts(decidedId)).isFalse()
        assertThat(processor.failMaxAttempts(UUID.randomUUID())).isFalse()

        assertThat(fx.row(decidedId)).isEqualTo(decided)
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
    }

    @Test
    fun `INV-4 process uses the tenant's own template and never one of another tenant with the same subject id`() {
        fx.enrol("other", "s1", bytes(unit(7)))
        fx.enrol("t1", "s1", bytes(unit(8)))
        val id = fx.pending("t1", "s1", bytes(unit(7)))

        assertThat(processor.process(id)).isTrue()

        assertThat(fx.row(id).status).isEqualTo("NO_MATCH") // compared with t1's s1, not with other's identical vector
    }
}
