package com.vmartis.matchgateway.matcher.decision.infra

import com.rabbitmq.client.Channel
import com.vmartis.matchgateway.common.messaging.Topology
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Ends a job that used its 3 deliveries as FAILED/MAX_ATTEMPTS (AC-27). The dead-letter queue never drops a message, so a
 * failing write is retried after a delay (no hot loop while the database is down).
 */
@Component
class DeadLetterListener(
    private val processor: DecisionProcessor,
    private val properties: MatcherProperties,
) {
    @RabbitListener(queues = [Topology.DEAD_LETTER_QUEUE], ackMode = "MANUAL")
    fun onMessage(
        message: Message,
        channel: Channel,
    ) {
        val tag = message.messageProperties.deliveryTag
        val id = message.verificationId()
        if (id == null) {
            log.warn("Dropping a dead letter whose body is not a verification id")
            channel.basicAck(tag, false)
            return
        }
        try {
            processor.failMaxAttempts(id)
        } catch (e: RuntimeException) {
            log.warn("Dead letter {} not written yet: {}", id, e.javaClass.simpleName)
            Thread.sleep(properties.deadLetterRetryDelay.toMillis())
            channel.basicReject(tag, true)
            return
        }
        channel.basicAck(tag, false)
    }

    private companion object {
        val log = LoggerFactory.getLogger(DeadLetterListener::class.java)
    }
}
