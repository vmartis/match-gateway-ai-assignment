package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.common.messaging.Topology
import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import com.vmartis.matchgateway.gateway.subject.TestTemplates
import com.vmartis.matchgateway.gateway.verification.infra.OutboxPublisher
import com.vmartis.matchgateway.gateway.verification.infra.OutboxRelay
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Step 12 / D-28, ADR-009 "Publishing": AC-14, AC-18, AC-21 against real Postgres and a real RabbitMQ. */
@TestPropertySource(
    properties = [
        "app.outbox.relay.enabled=false",
        "app.outbox.relay-age=0s",
        "logging.level.com.vmartis.matchgateway.gateway.verification.infra.OutboxPublisher=DEBUG",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class OutboxIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var rabbitAdmin: RabbitAdmin

    @Autowired
    lateinit var rabbitTemplate: RabbitTemplate

    @Autowired
    lateinit var broker: RabbitMQContainer

    @Autowired
    lateinit var relay: OutboxRelay

    @Autowired
    lateinit var publisher: OutboxPublisher

    @LocalServerPort
    var port: Int = 0

    @Autowired
    @Qualifier("applicationTaskExecutor")
    lateinit var taskExecutor: TaskExecutor

    @Autowired
    lateinit var connectionFactory: CachingConnectionFactory

    @Value("\${spring.task.execution.simple.concurrency-limit}")
    var concurrencyLimit: Int = 0

    /** A PENDING verification with an outbox row, created by SQL: no eager publish races the test. */
    private fun pendingWithOutbox(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into verification (id, tenant_id, subject_id, status, probe) values (:id, 'tenant-a', 's1', 'PENDING', :p)")
            .param("id", id)
            .param("p", TestTemplates.unitBytes(1))
            .update()
        jdbc.sql("insert into outbox (verification_id) values (:id)").param("id", id).update()
        return id
    }

    private data class Response(
        val status: Int,
        val body: String,
    ) {
        val id: String? get() = Regex("\"verificationId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from outbox").update()
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
        rabbitAdmin.purgeQueue(Topology.QUEUE, false)
    }

    private fun json(
        subjectId: String = "subj-001",
        template: String = TestTemplates.unit(),
    ) = """{"subjectId":"$subjectId","probeTemplate":"$template"}"""

    private fun submit(
        body: String = json(),
        key: String = "k-1",
    ): Response {
        val r =
            client
                .post()
                .uri(URI.create("http://localhost:$port/v1/verifications"))
                .headers { h ->
                    h.setBearerAuth(JwtTestTokens.valid(tenant = "tenant-a", scope = "enroll verify admin"))
                    h.set("Idempotency-Key", key)
                    h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }.body(body)
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseBody ?: "")
    }

    private fun count(table: String): Int = jdbc.sql("select count(*) from $table").query(Int::class.java).single()

    private fun outboxIds(): List<String> =
        jdbc
            .sql("select verification_id::text from outbox")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun text(m: Message) = String(m.body, Charsets.UTF_8)

    private fun await(
        timeoutMs: Long = 5_000,
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    private fun drain(): List<Message> {
        val all = mutableListOf<Message>()
        while (true) all += rabbitTemplate.receive(Topology.QUEUE, 500) ?: return all
    }

    private fun queueBinding() = Binding(Topology.QUEUE, Binding.DestinationType.QUEUE, Topology.EXCHANGE, Topology.ROUTING_KEY, null)

    private fun ctl(vararg args: String) = assertThat(broker.execInContainer("rabbitmqctl", *args).exitCode).isZero()

    // ---------------------------------------------------------------- AC-14

    @Test
    fun `AC-14 a created verification publishes exactly its id as a persistent text message and the outbox row is removed`() {
        val template = TestTemplates.unit(3)

        val r = submit(json(template = template))

        assertThat(r.status).isEqualTo(202)
        val id = checkNotNull(r.id)
        val message = checkNotNull(rabbitTemplate.receive(Topology.QUEUE, 5_000))
        assertThat(text(message)).isEqualTo(id)
        assertThat(message.body).hasSize(36)
        assertThat(message.messageProperties.contentType).isEqualTo("text/plain")
        assertThat(message.messageProperties.receivedDeliveryMode).isEqualTo(MessageDeliveryMode.PERSISTENT)
        assertThat(message.messageProperties.headers.toString()).doesNotContain(template).doesNotContain("subj-001")
        await(what = "outbox row deletion") { count("outbox") == 0 }
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 500)).isNull()
    }

    // ---------------------------------------------------------------- AC-18

    @Test
    fun `AC-18 replay with the same key and body returns the same id and publishes nothing more`() {
        val first = submit()
        val id = checkNotNull(first.id)
        assertThat(text(checkNotNull(rabbitTemplate.receive(Topology.QUEUE, 5_000)))).isEqualTo(id)
        await(what = "outbox row deletion") { count("outbox") == 0 }

        val replay = submit()

        assertThat(replay.status).isEqualTo(202)
        assertThat(replay.id).isEqualTo(id)
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 1_000)).isNull()
        assertThat(count("outbox")).isZero()
        assertThat(count("verification")).isEqualTo(1)
    }

    @Test
    fun `AC-18 same key with another body gives 422 and no message and no outbox row`() {
        val first = submit()
        assertThat(text(checkNotNull(rabbitTemplate.receive(Topology.QUEUE, 5_000)))).isEqualTo(first.id)
        await(what = "outbox row deletion") { count("outbox") == 0 }

        val reuse = submit(json(template = TestTemplates.unit(7)))

        assertThat(reuse.status).isEqualTo(422)
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 1_000)).isNull()
        assertThat(count("outbox")).isZero()
        assertThat(count("verification")).isEqualTo(1)
    }

    @Test
    fun `AC-18 an invalid template gives 400 and no message, no outbox row and no verification`() {
        val r = submit(json(template = "not-base64!"))

        assertThat(r.status).isEqualTo(400)
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 1_000)).isNull()
        assertThat(count("outbox")).isZero()
        assertThat(count("verification")).isZero()
    }

    // ---------------------------------------------------------------- AC-21

    @Test
    fun `AC-21 ten concurrent identical submits create one verification, at most one outbox row and one message`() {
        val threads = 10
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val maxOutbox = intArrayOf(0)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<Response> {
                        ready.countDown()
                        go.await()
                        submit()
                    }
                }
            ready.await()
            go.countDown()
            while (!futures.all { it.isDone }) maxOutbox[0] = maxOf(maxOutbox[0], count("outbox"))
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.map { it.status }).containsOnly(202)
            assertThat(responses.map { it.id }.toSet()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }

        assertThat(maxOutbox[0]).isLessThanOrEqualTo(1)
        assertThat(count("verification")).isEqualTo(1)
        val messages = drain()
        assertThat(
            messages.map { text(it) },
        ).containsExactly(jdbc.sql("select id::text from verification").query(String::class.java).single())
        await(what = "outbox row deletion") { count("outbox") == 0 }
    }

    // ---------------------------------------------------------------- outage / unroutable

    @Test
    fun `AC-14 broker down - the POST still answers 202 within 3 s, the row stays and the relay delivers it after recovery`() {
        var id: String
        try {
            ctl("stop_app")
            val started = System.nanoTime()
            val r = submit()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertThat(r.status).isEqualTo(202)
            assertThat(elapsedMs).isLessThan(3_000)
            id = checkNotNull(r.id)
            assertThat(outboxIds()).containsExactly(id)
        } finally {
            ctl("start_app")
        }

        await(60_000, "relay to publish after broker recovery") { relay.relayDue() >= 1 }
        val messages = drain()
        assertThat(messages.map { text(it) }).containsExactly(id)
        await(what = "outbox row deletion") { count("outbox") == 0 }
    }

    @Test
    fun `AC-14 an unroutable publish is returned, the row stays and the relay delivers once the binding is back`() {
        val binding = queueBinding()
        val id = pendingWithOutbox().toString()
        try {
            rabbitAdmin.removeBinding(binding)
            assertThat(publisher.publish(UUID.fromString(id))).isFalse()
            assertThat(outboxIds()).containsExactly(id)
        } finally {
            rabbitAdmin.declareBinding(binding)
        }

        assertThat(relay.relayDue()).isEqualTo(1)

        val messages = drain()
        assertThat(messages.map { text(it) }).containsExactly(id)
        assertThat(count("outbox")).isZero()
    }

    // ---------------------------------------------------------------- atomicity

    @Test
    fun `AC-18 a failing outbox insert rolls back the verification and idempotency record and publishes nothing`() {
        jdbc
            .sql(
                """
                CREATE FUNCTION outbox_fault() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'injected fault'; END $$
                """.trimIndent(),
            ).update()
        jdbc.sql("CREATE TRIGGER outbox_fault BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION outbox_fault()").update()
        try {
            val r = submit()
            assertThat(r.status).isEqualTo(500)
            assertThat(r.body).doesNotContain("injected fault").doesNotContain("Exception")
        } finally {
            jdbc.sql("DROP TRIGGER outbox_fault ON outbox").update()
            jdbc.sql("DROP FUNCTION outbox_fault()").update()
        }

        assertThat(count("verification")).isZero()
        assertThat(count("idempotency_key")).isZero()
        assertThat(count("outbox")).isZero()
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 1_000)).isNull()
    }

    // ---------------------------------------------------------------- erasure (ADR-007)

    @Test
    fun `AC-14 erasure of a subject with a pending verification and an outbox row still answers 204 and finishes it as SUBJECT_ERASED`() {
        val enrol =
            client
                .method(HttpMethod.POST)
                .uri(URI.create("http://localhost:$port/v1/subjects/subj-001/enrollment"))
                .headers {
                    it.setBearerAuth(JwtTestTokens.valid(tenant = "tenant-a", scope = "enroll verify admin"))
                    it.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }.body("""{"template":"${TestTemplates.unit(9)}"}""")
                .exchange()
                .returnResult(String::class.java)
        assertThat(enrol.status.value()).isEqualTo(201)
        val id = checkNotNull(submit().id)
        await(what = "eager publish to finish") { count("outbox") == 0 }
        jdbc.sql("insert into outbox (verification_id) values (:id)").param("id", UUID.fromString(id)).update()

        val status =
            client
                .method(HttpMethod.DELETE)
                .uri(URI.create("http://localhost:$port/v1/subjects/subj-001"))
                .headers { it.setBearerAuth(JwtTestTokens.valid(tenant = "tenant-a", scope = "enroll verify admin")) }
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()

        assertThat(status).isEqualTo(204)
        val row =
            jdbc
                .sql("select status, reason from verification where id = :id")
                .param("id", UUID.fromString(id))
                .query()
                .singleRow()
        assertThat(row["status"]).isEqualTo("FAILED")
        assertThat(row["reason"]).isEqualTo("SUBJECT_ERASED")
        // The row survives erasure (only an id, no biometric data); its later publish is a no-op for the matcher.
        assertThat(outboxIds()).containsExactly(id)
        rabbitAdmin.purgeQueue(Topology.QUEUE, false)
        assertThat(relay.relayDue()).isEqualTo(1)
        assertThat(drain().map { text(it) }).containsExactly(id)
    }

    // ---------------------------------------------------------------- resource bounds (review step 12)

    @Test
    fun `API4 the task executor for eager publishes is bounded and rejects beyond its limit`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(concurrencyLimit)
        try {
            repeat(concurrencyLimit) {
                taskExecutor.execute {
                    started.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { taskExecutor.execute {} }.isInstanceOf(TaskRejectedException::class.java)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `API4 broker connection and channel RPC timeouts are short so a hung broker cannot park publishes`() {
        assertThat(connectionFactory.rabbitConnectionFactory.connectionTimeout).isEqualTo(2_000)
        assertThat(connectionFactory.rabbitConnectionFactory.channelRpcTimeout).isEqualTo(5_000)
    }

    @Test
    fun `API4 a hung broker - POST stays fast, a publish gives up within its bounds and the relay delivers after recovery`() {
        val docker = broker.dockerClient
        connectionFactory.resetConnection() // the paused broker must be reached through a fresh connection attempt
        docker.pauseContainerCmd(broker.containerId).exec()
        val id: String
        try {
            val started = System.nanoTime()
            val r = submit()
            assertThat(r.status).isEqualTo(202)
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000)
            id = checkNotNull(r.id)

            val publishStarted = System.nanoTime()
            assertThat(publisher.publish(UUID.fromString(id))).isFalse()
            // Bounded by amqp-client's handshake timeout (10 s default) + channel RPC timeout (5 s) + confirm timeout (5 s).
            assertThat((System.nanoTime() - publishStarted) / 1_000_000).isLessThan(30_000)
            assertThat(outboxIds()).containsExactly(id)
        } finally {
            docker.unpauseContainerCmd(broker.containerId).exec()
        }
        await(timeoutMs = 60_000, what = "relay after unpause") { count("outbox") == 0 || relay.relayDue() >= 1 && count("outbox") == 0 }
        assertThat(drain().map { text(it) }).contains(id)
    }

    @Test
    fun `AC-14 the relay stops at the first failed publish instead of trying every due row`(output: CapturedOutput) {
        val binding = queueBinding()
        pendingWithOutbox()
        pendingWithOutbox()
        val before = Regex("deferred to the outbox relay").findAll(output.all).count()
        try {
            rabbitAdmin.removeBinding(binding)
            assertThat(relay.relayDue()).isZero()
        } finally {
            rabbitAdmin.declareBinding(binding)
        }
        assertThat(Regex("deferred to the outbox relay").findAll(output.all).count() - before).isEqualTo(1)
        assertThat(count("outbox")).isEqualTo(2)
    }

    @Test
    @Tag("security")
    fun `A09 the message carries only the verification id`() {
        val r = submit(json(subjectId = "subj-pii-77"))

        val message = checkNotNull(rabbitTemplate.receive(Topology.QUEUE, 5_000))
        assertThat(text(message)).isEqualTo(r.id)
        assertThat(message.messageProperties.toString()).doesNotContain("subj-pii-77").doesNotContain("tenant-a")
    }
}
