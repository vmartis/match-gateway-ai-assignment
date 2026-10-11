package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.gateway.verification.infra.OutboxPublisher
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.util.UUID

/** Step 12 review: a saturated executor must not turn a committed submit into a 500 – the relay owns the row then. */
class OutboxPublisherTest {
    @Test
    fun `API4 publishAsync swallows a rejected task so the committed verification is still answered`() {
        val rejecting = TaskExecutor { throw TaskRejectedException("executor saturated") }
        val publisher =
            OutboxPublisher(
                RabbitTemplate(CachingConnectionFactory("localhost", 1)),
                JdbcClient.create(DriverManagerDataSource()),
                rejecting,
                Duration.ofSeconds(1),
            )

        assertThatCode { publisher.publishAsync(UUID.randomUUID()) }.doesNotThrowAnyException()
    }
}
