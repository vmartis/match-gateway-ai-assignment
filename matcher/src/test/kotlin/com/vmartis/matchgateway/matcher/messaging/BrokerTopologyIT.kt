package com.vmartis.matchgateway.matcher.messaging

import com.vmartis.matchgateway.common.messaging.Topology
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Step 11 / D-28, AC-1 + AC-2: the matcher declares the same topology and reports broker readiness on its port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class BrokerTopologyIT {
    @LocalServerPort
    var serverPort: Int = 0

    private val client: RestTestClient by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$serverPort").build() }

    @Autowired
    lateinit var broker: RabbitMQContainer

    @Autowired
    lateinit var rabbitAdmin: RabbitAdmin

    @Autowired
    lateinit var rabbitTemplate: RabbitTemplate

    private fun ctl(vararg args: String): JsonNode {
        val result = broker.execInContainer("rabbitmqctl", *args, "--formatter", "json")
        assertThat(result.exitCode).describedAs(result.stderr).isZero()
        return JsonMapper.builder().build().readTree(result.stdout)
    }

    // rabbitmqctl renders queue arguments as [name, type, value] triples (verified against RabbitMQ 4.3.6).
    private fun JsonNode.argumentsByName(): Map<String, JsonNode> = (0 until size()).associate { this[it][0].asString() to this[it][2] }

    private fun queues(): Map<String, JsonNode> {
        client
            .get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus()
            .isOk // first connection makes RabbitAdmin declare
        val rows = ctl("list_queues", "name", "type", "arguments")
        return (0 until rows.size()).associate { rows[it]["name"].asString() to rows[it] }
    }

    @Test
    fun `AC-1 main queue is a durable quorum queue with delivery limit dead lettering and reject-publish`() {
        val queue = checkNotNull(queues()[Topology.QUEUE])

        assertThat(queue["type"].asString()).isEqualTo("quorum")
        val args = queue["arguments"].argumentsByName()
        assertThat(args.getValue("x-queue-type").asString()).isEqualTo("quorum")
        assertThat(args.getValue("x-delivery-limit").asInt()).isEqualTo(2) // 2 redeliveries = 3 deliveries
        assertThat(args.getValue("x-dead-letter-routing-key").asString()).isEqualTo("verification")
        assertThat(args.getValue("x-dead-letter-exchange").asString()).isEqualTo("matchgw.verifications.dlx")
        assertThat(args.getValue("x-dead-letter-strategy").asString()).isEqualTo("at-least-once")
        assertThat(args.getValue("x-overflow").asString()).isEqualTo("reject-publish")
    }

    @Test
    fun `AC-1 dead letter queue is a quorum queue`() {
        val queue = checkNotNull(queues()[Topology.DEAD_LETTER_QUEUE])

        assertThat(queue["type"].asString()).isEqualTo("quorum")
        assertThat(queue["arguments"].argumentsByName().getValue("x-queue-type").asString()).isEqualTo("quorum")
        assertThat(queue["arguments"].argumentsByName().getValue("x-delivery-limit").asInt()).isEqualTo(-1)
    }

    @Test
    fun `AC-1 both queues are bound to their direct exchanges with the routing key`() {
        queues()
        val rows = ctl("list_bindings", "source_name", "destination_name", "destination_kind", "routing_key")
        val bindings =
            (0 until rows.size())
                .map { rows[it] }
                .map { listOf(it["source_name"].asString(), it["destination_name"].asString(), it["routing_key"].asString()) }

        assertThat(bindings).contains(
            listOf("matchgw.verifications", "matchgw.verifications", "verification"),
            listOf("matchgw.verifications.dlx", "matchgw.verifications.dead", "verification"),
        )
        val exchanges = ctl("list_exchanges", "name", "type", "durable")
        val byName = (0 until exchanges.size()).associate { exchanges[it]["name"].asString() to exchanges[it] }
        assertThat(byName.getValue(Topology.EXCHANGE)["type"].asString()).isEqualTo("direct")
        assertThat(byName.getValue(Topology.DEAD_LETTER_EXCHANGE)["type"].asString()).isEqualTo("direct")
        assertThat(byName.getValue(Topology.EXCHANGE)["durable"].asBoolean()).isTrue()
        assertThat(byName.getValue(Topology.DEAD_LETTER_EXCHANGE)["durable"].asBoolean()).isTrue()
    }

    @Test
    fun `AC-1 a message published to the exchange with the routing key lands in the queue`() {
        queues()
        val body = "topology-probe-matcher"

        rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.ROUTING_KEY, body)
        val received = rabbitTemplate.receive(Topology.QUEUE, 5_000)

        assertThat(received).isNotNull
        assertThat(String(checkNotNull(received).body, Charsets.UTF_8)).isEqualTo(body)
    }

    @Test
    fun `AC-1 declaring the topology a second time is idempotent`() {
        queues()

        rabbitAdmin.initialize()
        rabbitAdmin.initialize()

        assertThat(queues()).containsKeys(Topology.QUEUE, Topology.DEAD_LETTER_QUEUE)
    }

    /** Gets one message from [queue] (polling briefly: a requeue is asynchronous), or null after the timeout. */
    private fun getOne(
        channel: com.rabbitmq.client.Channel,
        queue: String,
    ): com.rabbitmq.client.GetResponse? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            channel.basicGet(queue, false)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `AC-27 a message rejected with requeue is delivered exactly 3 times and then dead-lettered`() {
        queues() // declare
        rabbitAdmin.purgeQueue(Topology.QUEUE, false)
        rabbitAdmin.purgeQueue(Topology.DEAD_LETTER_QUEUE, false)
        val body = "delivery-count-${java.util.UUID.randomUUID()}"
        rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.ROUTING_KEY, body)

        val deliveries =
            rabbitTemplate.execute { channel ->
                var count = 0
                while (true) {
                    val r = getOne(channel, Topology.QUEUE) ?: break
                    count++
                    channel.basicReject(r.envelope.deliveryTag, true) // counts toward the limit; nack would not
                }
                count
            }

        assertThat(deliveries).isEqualTo(Topology.MAX_DELIVERIES)
        assertThat(rabbitTemplate.receiveAndConvert(Topology.DEAD_LETTER_QUEUE, 5_000)).isEqualTo(body)
    }

    @Test
    fun `AC-27 the dead letter queue never drops a message that keeps failing`() {
        queues()
        rabbitAdmin.purgeQueue(Topology.DEAD_LETTER_QUEUE, false)
        val body = "dead-${java.util.UUID.randomUUID()}"
        rabbitTemplate.convertAndSend(Topology.DEAD_LETTER_EXCHANGE, Topology.ROUTING_KEY, body)

        rabbitTemplate.execute { channel ->
            repeat(25) {
                // RabbitMQ 4.x would drop it after 20 returns without an explicit limit
                val r = checkNotNull(getOne(channel, Topology.DEAD_LETTER_QUEUE)) { "dead letter dropped after $it returns" }
                channel.basicReject(r.envelope.deliveryTag, true)
            }
        }

        assertThat(rabbitTemplate.receiveAndConvert(Topology.DEAD_LETTER_QUEUE, 5_000)).isEqualTo(body)
    }

    @Test
    fun `AC-2 readiness goes DOWN when the broker stops and UP again when it is back`() {
        fun readiness(): Int =
            client
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()

        fun awaitStatus(expected: Int) {
            val deadline = System.nanoTime() + 60_000_000_000L
            while (readiness() != expected) {
                check(System.nanoTime() < deadline) { "readiness never became $expected" }
                Thread.sleep(200)
            }
        }
        assertThat(readiness()).isEqualTo(200)
        try {
            assertThat(broker.execInContainer("rabbitmqctl", "stop_app").exitCode).isZero()
            awaitStatus(503)
        } finally {
            assertThat(broker.execInContainer("rabbitmqctl", "start_app").exitCode).isZero()
        }
        awaitStatus(200)
        assertThat(queues()).containsKeys(Topology.QUEUE, Topology.DEAD_LETTER_QUEUE)
    }

    @Test
    fun `AC-2 readiness is UP with the broker reachable`() {
        val body =
            client
                .get()
                .uri(
                    "/actuator/health/readiness",
                ).exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseBody

        assertThat(body).isEqualTo("""{"status":"UP"}""")
    }

    @Test
    @Tag("security")
    fun `A02 broker credentials never appear in the logs`(output: CapturedOutput) {
        queues()
        rabbitAdmin.initialize()

        assertThat(output.all)
            .doesNotContain(broker.adminPassword)
            .doesNotContain("guest:guest")
            .doesNotContain("amqp://guest")
            .doesNotContain("amqp://" + broker.adminUsername + ":")
    }
}
