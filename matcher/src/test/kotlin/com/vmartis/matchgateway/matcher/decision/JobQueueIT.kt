package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.unit
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import com.vmartis.matchgateway.matcher.decision.infra.JobQueue
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
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** INV-1, AC-35, AC-36, AC-26: the guarded terminal write (WHERE id AND status = 'PENDING') is the one place that decides. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.rabbitmq.listener.simple.auto-startup=false"],
)
@Import(TestcontainersConfiguration::class)
class JobQueueIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    @Autowired lateinit var queue: JobQueue

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    @Test
    fun `load returns the pending job with tenant, subject and probe bytes`() {
        val probe = bytes(unit(1))
        val id = fx.pending("t1", "s1", probe)

        val job = checkNotNull(queue.load(id))

        assertThat(job.id).isEqualTo(id)
        assertThat(job.tenantId).isEqualTo("t1")
        assertThat(job.subjectId).isEqualTo("s1")
        assertThat(job.probe).isEqualTo(probe)
    }

    @Test
    fun `load returns null for an unknown id and for a terminal verification`() {
        val terminal = fx.pending("t1", "s1", bytes(unit(2)))
        checkNotNull(queue.load(terminal)).let { assertThat(queue.complete(it, Outcome.Failed("SUBJECT_NOT_ENROLLED"))).isNotNull() }

        assertThat(queue.load(UUID.randomUUID())).isNull()
        assertThat(queue.load(terminal)).isNull()
    }

    @Test
    fun `complete writes a decided outcome, clears the probe and returns decidedAt minus createdAt`() {
        val id = fx.pending("t1", "s1", bytes(unit(3)))
        val job = checkNotNull(queue.load(id))

        val latency = queue.complete(job, Outcome.Decided("NO_MATCH", 0.25, 0.5))

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("NO_MATCH")
        assertThat(r.score).isEqualTo(0.25)
        assertThat(r.threshold).isEqualTo(0.5)
        assertThat(r.reason).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        val fromDb =
            jdbc
                .sql("select extract(epoch from decided_at - created_at) from verification where id = :id")
                .param("id", id)
                .query(Double::class.java)
                .single()
        assertThat(checkNotNull(latency)).isGreaterThanOrEqualTo(Duration.ZERO)
        assertThat(latency.toNanos() / 1e9).isEqualTo(
            fromDb,
            org.assertj.core.data.Offset
                .offset(1e-3),
        )
    }

    @Test
    fun `complete with a failed outcome stores the reason and no score`() {
        val id = fx.pending("t1", "s1", bytes(unit(4)))

        assertThat(queue.complete(checkNotNull(queue.load(id)), Outcome.Failed("SUBJECT_NOT_ENROLLED"))).isNotNull()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(r.score).isNull()
        assertThat(r.threshold).isNull()
        assertThat(r.probeNull).isTrue()
    }

    @Test
    fun `AC-35 stale writer - the second complete of the same verification changes 0 rows and keeps the first outcome`() {
        val id = fx.pending("t1", "s1", bytes(unit(5)))
        val a = checkNotNull(queue.load(id))
        val b = checkNotNull(queue.load(id)) // both consumers loaded the job while it was pending

        assertThat(queue.complete(b, Outcome.Decided("MATCH", 0.9, 0.5))).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO)
        val decided = fx.row(id)

        assertThat(queue.complete(a, Outcome.Decided("NO_MATCH", 0.1, 0.5))).isNull()
        assertThat(queue.complete(a, Outcome.Failed("SUBJECT_NOT_ENROLLED"))).isNull()
        assertThat(queue.complete(b, Outcome.Decided("NO_MATCH", 0.1, 0.5))).isNull()
        assertThat(queue.failMaxAttempts(id)).isNull()

        val after = fx.row(id)
        assertThat(after).isEqualTo(decided)
        assertThat(after.score).isEqualTo(0.9)
        assertThat(after.threshold).isEqualTo(0.5)
        assertThat(after.reason).isNull()
    }

    @Test
    fun `failMaxAttempts writes FAILED MAX_ATTEMPTS once and returns the latency, then null`() {
        val id = fx.pending("t1", "s1", bytes(unit(6)))

        assertThat(queue.failMaxAttempts(id)).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO)

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        assertThat(queue.failMaxAttempts(id)).isNull()
        assertThat(queue.failMaxAttempts(UUID.randomUUID())).isNull()
        assertThat(fx.row(id)).isEqualTo(r)
    }

    @Test
    fun `AC-36 nothing can be loaded or written for a terminal verification`() {
        val ids = (1..5).map { fx.pending("t1", "s$it", bytes(unit(it.toLong()))) }
        ids.forEach { queue.complete(checkNotNull(queue.load(it)), Outcome.Decided("MATCH", 1.0, 0.5)) }
        val rows = ids.map(fx::row)

        ids.forEach {
            assertThat(queue.load(it)).isNull()
            assertThat(queue.failMaxAttempts(it)).isNull()
        }

        assertThat(ids.map(fx::row)).isEqualTo(rows)
        assertThat(fx.count("select count(*) from verification where status = 'PENDING'")).isZero()
    }
}
