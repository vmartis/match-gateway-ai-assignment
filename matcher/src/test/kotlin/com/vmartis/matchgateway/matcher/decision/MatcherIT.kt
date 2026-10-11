package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.common.messaging.Topology
import com.vmartis.matchgateway.common.template.Cosine
import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.reference
import com.vmartis.matchgateway.matcher.Vectors.unit
import com.vmartis.matchgateway.matcher.decision.infra.DecisionProcessor
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
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

/**
 * AC-24..AC-29, AC-17, INV-4, INV-5 (D-28, ADR-009): the matcher consumes job messages from RabbitMQ and decides the pending
 * verifications in the shared database. The listeners are running; the processing deadline is the 30 s maximum so that the
 * crash test can hold a decision blocked without it timing out (the stall test lives in [MatcherStallIT]).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.processing-deadline=30s"],
)
@Import(TestcontainersConfiguration::class)
class MatcherIT {
    @Autowired
    lateinit var listeners: org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry

    @Autowired
    lateinit var connectionFactory: org.springframework.amqp.rabbit.connection.CachingConnectionFactory

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

    private fun within1e5() = Offset.offset(1e-5)

    @Test
    fun `AC-24 enrolled pair is decided MATCH or NO_MATCH against threshold 0_5 with a float64 score`() {
        val stored = unit(1)
        val same = unit(1)
        val other = unit(2)
        fx.enrol("t1", "s1", bytes(stored))
        val matchId = fx.submit("t1", "s1", bytes(same))
        val noMatchId = fx.submit("t1", "s1", bytes(other))

        fx.awaitAllTerminal(listOf(matchId, noMatchId))

        val a = fx.row(matchId)
        assertThat(a.status).isEqualTo("MATCH")
        assertThat(a.score).isCloseTo(reference(stored, same), within1e5())
        assertThat(a.threshold).isEqualTo(0.5)
        assertThat(a.decided).isTrue()
        assertThat(a.probeNull).isTrue()
        val expectedOther = reference(stored, other)
        assertThat(expectedOther).isLessThan(0.5)
        val b = fx.row(noMatchId)
        assertThat(b.status).isEqualTo("NO_MATCH")
        assertThat(b.score).isCloseTo(expectedOther, within1e5())
        assertThat(b.threshold).isEqualTo(0.5)
        assertThat(b.probeNull).isTrue()
    }

    @Test
    fun `AC-24 score equal to the threshold is a MATCH`() {
        val stored = unit(3)
        val probe = unit(4)
        val exact = Cosine.similarity(stored, probe)
        fx.enrol("t1", "s1", bytes(stored))
        fx.setThreshold("t1", exact)
        val id = fx.submit("t1", "s1", bytes(probe))

        fx.awaitTerminal(id)

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.score).isEqualTo(exact)
        assertThat(r.threshold).isEqualTo(exact)
    }

    @Test
    fun `AC-24 score just below the threshold is a NO_MATCH`() {
        val stored = unit(3)
        val probe = unit(4)
        val exact = Cosine.similarity(stored, probe)
        fx.enrol("t1", "s1", bytes(stored))
        fx.setThreshold("t1", Math.nextUp(exact))
        val id = fx.submit("t1", "s1", bytes(probe))

        fx.awaitTerminal(id)

        assertThat(fx.row(id).status).isEqualTo("NO_MATCH")
    }

    @Test
    fun `AC-25 threshold changed before the message is processed is used and stored`() {
        fx.enrol("t1", "s1", bytes(unit(5)))
        val id = fx.pending("t1", "s1", bytes(unit(5)))
        fx.setThreshold("t1", 0.9) // changed after the POST, before the job is processed
        fx.publish(id)

        fx.awaitTerminal(id)

        val r = fx.row(id)
        assertThat(r.threshold).isEqualTo(0.9)
        assertThat(r.status).isEqualTo("MATCH")
    }

    @Test
    fun `AC-25 higher threshold turns a mid score into NO_MATCH`() {
        val stored = unit(6)
        val noise = unit(7)
        val probe = FloatArray(128) { 0.6f * stored[it] + 0.4f * noise[it] }
        assertThat(reference(stored, probe)).isBetween(0.5, 0.9)
        fx.enrol("t1", "s1", bytes(stored))
        fx.setThreshold("t1", 0.9)
        val id = fx.submit("t1", "s1", bytes(probe))

        fx.awaitTerminal(id)

        assertThat(fx.row(id).status).isEqualTo("NO_MATCH")
        assertThat(fx.row(id).threshold).isEqualTo(0.9)
    }

    @Test
    fun `AC-26 AC-29 AC-17 no active template fails SUBJECT_NOT_ENROLLED after exactly one delivery and clears the probe`() {
        val processedBefore = fx.processingCount()
        val failedBefore = fx.counter("FAILED")
        val id = fx.submit("t1", "ghost", bytes(unit(8)))

        fx.awaitTerminal(id)
        fx.awaitQueuesEmpty()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(r.score).isNull()
        assertThat(r.threshold).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        // no retry: one processing attempt, nothing dead-lettered
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
        fx.await("FAILED counter") { fx.counter("FAILED") - failedBefore == 1.0 }
    }

    @Test
    fun `AC-27 corrupt stored template is delivered exactly 3 times then dead-lettered to FAILED MAX_ATTEMPTS and never processed again`() {
        fx.enrol("t1", "s1", bytes(unit(9).also { it[10] = Float.NaN }))
        val processedBefore = fx.processingCount()
        val failedBefore = fx.counter("FAILED")
        val id = fx.submit("t1", "s1", bytes(unit(9)))

        fx.awaitTerminal(id, 60)
        fx.awaitQueuesEmpty()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.score).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(r.decided).isTrue()
        assertThat(fx.processingCount() - processedBefore).isEqualTo(Topology.MAX_DELIVERIES.toLong())
        fx.await("FAILED counter") { fx.counter("FAILED") - failedBefore == 1.0 }

        // never processed again: neither a direct call nor a duplicate message changes anything
        assertThat(processor.process(id)).isFalse()
        fx.publish(id)
        fx.awaitQueuesEmpty()
        assertThat(fx.row(id)).isEqualTo(r)
        assertThat(fx.counter("FAILED") - failedBefore).isEqualTo(1.0)
    }

    @Test
    fun `AC-27 probe with a non-finite value is a processing error delivered 3 times then FAILED MAX_ATTEMPTS`() {
        fx.enrol("t1", "s1", bytes(unit(10)))
        val processedBefore = fx.processingCount()
        val badProbe = unit(10).also { it[0] = Float.POSITIVE_INFINITY }
        val id = fx.submit("t1", "s1", bytes(badProbe))

        fx.awaitTerminal(id, 60)
        fx.awaitQueuesEmpty()

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.score).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(fx.processingCount() - processedBefore).isEqualTo(3L)
    }

    @Test
    fun `AC-28 a replica that crashes while holding the message - the job is redelivered and decided within 30 s`() {
        fx.enrol("t1", "s1", bytes(unit(11)))
        val id = fx.pending("t1", "s1", bytes(unit(11)))
        val processedBefore = fx.processingCount()
        val matchBefore = fx.counter("MATCH")

        // The crashing replica is a separate process: a raw consumer that takes the job and never acks it. The app's own
        // listeners are paused meanwhile, so the job is guaranteed to be held by the replica that crashes.
        listeners.stop()
        val crashed =
            com.rabbitmq.client
                .ConnectionFactory()
                .apply {
                    setUri(broker.amqpUrl)
                    username = broker.adminUsername
                    password = broker.adminPassword
                }.newConnection()
        val crashedAt: Long
        try {
            val received = java.util.concurrent.CountDownLatch(1)
            crashed.createChannel().apply {
                basicQos(1)
                basicConsume(
                    Topology.QUEUE,
                    false,
                    object : com.rabbitmq.client.DefaultConsumer(this) {
                        override fun handleDelivery(
                            consumerTag: String,
                            envelope: com.rabbitmq.client.Envelope,
                            properties: com.rabbitmq.client.AMQP.BasicProperties,
                            body: ByteArray,
                        ) = received.countDown() // holds the job, never acks
                    },
                )
            }
            fx.publish(id)
            assertThat(received.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        } catch (e: Throwable) {
            crashed.abort() // a failed setup must not leave a consumer behind in the shared cached context
            throw e
        } finally {
            listeners.start() // never leave the cached context without listeners
        }
        crashedAt = System.nanoTime()
        crashed.abort() // the replica dies: no ack, connection gone → the broker redelivers

        fx.awaitTerminal(id, 30)
        assertThat(System.nanoTime() - crashedAt).isLessThan(30_000_000_000L)
        val r = fx.row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.probeNull).isTrue()
        fx.awaitQueuesEmpty()
        // decided exactly once, by the healthy replica
        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L)
        assertThat(fx.counter("MATCH") - matchBefore).isEqualTo(1.0)
    }

    @Test
    fun `AC-28 a consumer stuck on one job does not hold up other jobs (prefetch 1)`() {
        fx.enrol("t1", "s1", bytes(unit(21)))
        val stuck = fx.pending("t1", "s1", bytes(unit(21)))
        dataSource.connection.use { lock ->
            lock.autoCommit = false
            // Only this job's terminal UPDATE blocks: its consumer is stuck holding this one message.
            lock.prepareStatement("select id from verification where id = ? for update").use {
                it.setObject(1, stuck)
                it.executeQuery().close()
            }
            fx.publish(stuck)
            fx.await("a consumer stuck on the locked job") {
                jdbc
                    .sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like 'UPDATE verification%'")
                    .query(Long::class.java)
                    .single() == 1L
            }
            val others = (1..20).map { fx.pending("t1", "s1", bytes(unit(21))) }
            others.forEach { fx.publish(it) }

            // The other consumers take them all; none waits behind the stuck job until the 30 s deadline.
            others.forEach { fx.awaitTerminal(it, 10) }
            lock.rollback()
        }
        fx.awaitTerminal(stuck, 30)
    }

    @Test
    fun `INV-4 template of another tenant with the same subject id is never used`() {
        fx.enrol("other", "s1", bytes(unit(13)))
        val id = fx.submit("t1", "s1", bytes(unit(13)))

        fx.awaitTerminal(id)

        val r = fx.row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
    }

    @Test
    fun `result counter and latency timer are incremented once per decided verification`() {
        fx.enrol("t1", "s1", bytes(unit(14)))
        val matchBefore = fx.counter("MATCH")
        val latencyBefore = fx.latencyCount()
        val id = fx.submit("t1", "s1", bytes(unit(14)))

        fx.awaitTerminal(id)
        fx.awaitQueuesEmpty()

        fx.await("MATCH counter") { fx.counter("MATCH") - matchBefore == 1.0 }
        fx.await("latency sample") { fx.latencyCount() - latencyBefore == 1L }
    }

    @Test
    fun `a message for an unknown verification id is acked without effect and neither redelivered nor dead-lettered`() {
        val before = fx.counters()
        val latencyBefore = fx.latencyCount()
        val processedBefore = fx.processingCount()

        fx.publish(UUID.randomUUID())
        fx.await("the message to be processed") { fx.processingCount() - processedBefore >= 1 }
        fx.awaitQueuesEmpty()

        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L) // delivered once, acked
        assertThat(fx.counters()).isEqualTo(before)
        assertThat(fx.latencyCount()).isEqualTo(latencyBefore)
        assertThat(fx.count("select count(*) from verification")).isZero()
    }

    @Test
    fun `a message whose body is not a canonical UUID is acked and dropped without processing, redelivery or dead-lettering`() {
        val poison =
            listOf(
                "not-a-uuid",
                "",
                "1-1-1-1-1", // accepted by UUID.fromString, but not canonical
                """{"id":"${UUID.randomUUID()}"}""",
                " ${UUID.randomUUID()}",
                "${UUID.randomUUID()}\n",
                "A".repeat(10_000),
            )
        val before = fx.counters()
        val processedBefore = fx.processingCount()

        poison.forEach { fx.publishRaw(it.toByteArray()) }
        fx.awaitQueuesEmpty()
        // A valid sentinel proves the consumers are alive and nothing poisonous comes back around.
        fx.enrol("t1", "s1", bytes(unit(20)))
        val sentinel = fx.submit("t1", "s1", bytes(unit(20)))
        fx.awaitTerminal(sentinel)
        fx.awaitQueuesEmpty()

        assertThat(fx.processingCount() - processedBefore).isEqualTo(1L) // the sentinel only
        assertThat(fx.counters().getValue("FAILED")).isEqualTo(before.getValue("FAILED"))
        assertThat(fx.queueMessages(Topology.DEAD_LETTER_QUEUE)).isZero()
    }
}
