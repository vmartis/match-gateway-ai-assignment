package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.common.messaging.Topology
import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.subject.TestTemplates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/** Step 12 review: the scheduled relay is wired by default and honours the relay age (rows not yet due are left alone). */
@TestPropertySource(properties = ["app.outbox.relay-interval=100ms", "app.outbox.relay-age=10s"])
class OutboxRelayIT : GatewayIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var rabbitAdmin: RabbitAdmin

    @Autowired lateinit var rabbitTemplate: RabbitTemplate

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from outbox").update()
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        rabbitAdmin.purgeQueue(Topology.QUEUE, false)
    }

    private fun pendingWithOutbox(age: String): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into verification (id, tenant_id, subject_id, status, probe) values (:id, 'tenant-a', 's1', 'PENDING', :p)")
            .param("id", id)
            .param("p", TestTemplates.unitBytes(1))
            .update()
        jdbc
            .sql(
                "insert into outbox (verification_id, created_at) values (:id, now() - cast(:age as interval))",
            ).param("id", id)
            .param("age", age)
            .update()
        return id
    }

    private fun outboxContains(id: UUID): Boolean =
        jdbc
            .sql("select count(*) from outbox where verification_id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() == 1

    @Test
    fun `AC-14 the scheduled relay publishes a due row on its own and leaves a fresh row alone`() {
        val fresh = pendingWithOutbox("0 seconds")
        val due = pendingWithOutbox("1 minute")

        val deadline = System.nanoTime() + 10_000_000_000L
        while (outboxContains(due)) {
            check(System.nanoTime() < deadline) { "scheduled relay never published the due row" }
            Thread.sleep(50)
        }
        val message = rabbitTemplate.receive(Topology.QUEUE, 5_000)
        assertThat(message?.body?.toString(Charsets.UTF_8)).isEqualTo(due.toString())
        Thread.sleep(1_000) // ten more relay runs
        assertThat(outboxContains(fresh)).isTrue()
        assertThat(rabbitTemplate.receive(Topology.QUEUE, 500)).isNull()
    }
}
