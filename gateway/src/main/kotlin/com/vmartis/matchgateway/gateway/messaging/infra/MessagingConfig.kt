package com.vmartis.matchgateway.gateway.messaging.infra

import com.vmartis.matchgateway.common.messaging.Topology
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Declares the shared topology (ADR-009); Spring AMQP's RabbitAdmin applies it idempotently on every new connection. */
@Configuration
class MessagingConfig {
    /** Returns are handled through CorrelationData by the outbox publisher; this only replaces Spring's per-return WARN. */
    @Bean
    fun returnsAreHandledByTheOutbox() =
        RabbitTemplateCustomizer { template ->
            template.setReturnsCallback { returned -> log.debug("Unroutable job message returned: {}", returned.replyText) }
        }

    @Bean
    fun verificationTopology(): Declarables {
        val exchange = DirectExchange(Topology.EXCHANGE, true, false)
        val queue = Queue(Topology.QUEUE, true, false, false, Topology.queueArguments)
        val deadLetterExchange = DirectExchange(Topology.DEAD_LETTER_EXCHANGE, true, false)
        val deadLetterQueue = Queue(Topology.DEAD_LETTER_QUEUE, true, false, false, Topology.deadLetterQueueArguments)
        return Declarables(
            exchange,
            queue,
            BindingBuilder.bind(queue).to(exchange).with(Topology.ROUTING_KEY),
            deadLetterExchange,
            deadLetterQueue,
            BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(Topology.ROUTING_KEY),
        )
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(MessagingConfig::class.java)
    }
}
