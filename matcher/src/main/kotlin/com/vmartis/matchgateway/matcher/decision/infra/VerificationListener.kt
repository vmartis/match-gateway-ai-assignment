package com.vmartis.matchgateway.matcher.decision.infra

import com.rabbitmq.client.Channel
import com.vmartis.matchgateway.common.messaging.Topology
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.util.UUID

private const val CANONICAL_UUID_LENGTH = 36

/** The verification id of a message, or null if the body is not a canonical UUID (poison: never retried). */
internal fun Message.verificationId(): UUID? {
    // Size first: never decode a large hostile body (security review step 13); the broker also caps message size.
    if (body.size != CANONICAL_UUID_LENGTH) return null
    val text = String(body, Charsets.UTF_8)
    return try {
        UUID.fromString(text).takeIf { it.toString() == text }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Consumes jobs with manual acknowledgement (ADR-009): ack only after the outcome is committed (or the job was already
 * terminal); on a processing error `basicReject` with requeue – never `basicNack`, which does not count toward the
 * quorum queue's delivery limit – so a failing job is dead-lettered after 3 deliveries (AC-27).
 */
@Component
class VerificationListener(
    private val processor: DecisionProcessor,
) {
    @RabbitListener(queues = [Topology.QUEUE], ackMode = "MANUAL")
    fun onMessage(
        message: Message,
        channel: Channel,
    ) {
        val tag = message.messageProperties.deliveryTag
        val id = message.verificationId()
        if (id == null) {
            log.warn("Dropping a job message whose body is not a verification id")
            channel.basicAck(tag, false)
            return
        }
        try {
            processor.process(id)
        } catch (e: RuntimeException) {
            // Only the id and the exception class: messages may carry data (CLAUDE.md §4).
            log.warn("Verification {} attempt failed: {}", id, e.javaClass.simpleName)
            channel.basicReject(tag, true)
            return
        }
        // After the commit; if the ack is lost the redelivery is a guarded no-op (AC-36).
        channel.basicAck(tag, false)
    }

    private companion object {
        val log = LoggerFactory.getLogger(VerificationListener::class.java)
    }
}
