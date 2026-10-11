package com.vmartis.matchgateway.gateway.verification.infra

import com.vmartis.matchgateway.common.messaging.Topology
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Publishes a job (ADR-009): the message carries only the verification id (NFR-5). The outbox row is deleted only after the
 * broker confirmed the message and did not return it as unroutable (mandatory); otherwise the row stays for [OutboxRelay].
 */
@Component
class OutboxPublisher(
    private val rabbit: RabbitTemplate,
    private val jdbc: JdbcClient,
    @Qualifier("applicationTaskExecutor") private val executor: TaskExecutor,
    @Value("\${app.outbox.confirm-timeout:5s}") private val confirmTimeout: Duration,
) {
    /**
     * Eager publish after commit, off the request thread: a slow or absent broker never delays the API response. The
     * executor is bounded (spring.task.execution.simple.*); when it is saturated the row simply waits for the relay.
     */
    fun publishAsync(id: UUID) {
        try {
            executor.execute { publish(id) }
        } catch (e: TaskRejectedException) {
            log.debug("Eager publish of verification {} left to the outbox relay: {}", id, e.javaClass.simpleName)
        }
    }

    /**
     * True iff the broker confirmed the message and the outbox row was removed. Broker failures return false (bounded by the
     * connection, channel-RPC and confirm timeouts); a database error deleting the row propagates.
     */
    fun publish(id: UUID): Boolean {
        val correlation = CorrelationData(id.toString())
        val confirmed =
            try {
                rabbit.send(Topology.EXCHANGE, Topology.ROUTING_KEY, message(id), correlation)
                val confirm = correlation.future.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
                (confirm.ack && correlation.returned == null) || deferred(id, "unconfirmed or unroutable")
            } catch (e: AmqpException) {
                deferred(id, e.javaClass.simpleName)
            } catch (e: TimeoutException) {
                deferred(id, e.javaClass.simpleName)
            } catch (e: ExecutionException) {
                deferred(id, e.javaClass.simpleName)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                deferred(id, e.javaClass.simpleName)
            }
        if (confirmed) jdbc.sql("DELETE FROM outbox WHERE verification_id = :id").param("id", id).update()
        return confirmed
    }

    private fun message(id: UUID) =
        MessageBuilder
            .withBody(id.toString().toByteArray(Charsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build()

    private fun deferred(
        id: UUID,
        reason: String,
    ): Boolean {
        // DEBUG per attempt (an outage would otherwise log once per request); OutboxRelay warns once per outage. Only the id
        // and the exception class or reason: exception messages may carry connection details (CLAUDE.md §4).
        log.debug("Publishing verification {} deferred to the outbox relay: {}", id, reason)
        return false
    }

    private companion object {
        val log = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}
