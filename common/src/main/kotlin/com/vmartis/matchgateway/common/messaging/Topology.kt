package com.vmartis.matchgateway.common.messaging

/**
 * Broker topology shared by gateway and matcher (ADR-009). Both apps declare it; RabbitMQ rejects a second declaration with
 * different arguments, so the arguments live here once. Messages carry only a verification id (NFR-5).
 */
object Topology {
    const val EXCHANGE = "matchgw.verifications"
    const val QUEUE = "matchgw.verifications"
    const val ROUTING_KEY = "verification"
    const val DEAD_LETTER_EXCHANGE = "matchgw.verifications.dlx"
    const val DEAD_LETTER_QUEUE = "matchgw.verifications.dead"

    /** A job is delivered at most 3 times, then dead-lettered → FAILED/MAX_ATTEMPTS (AC-27). Only reject/crash/timeout count, never nack. */
    const val MAX_DELIVERIES = 3

    val queueArguments: Map<String, Any> =
        mapOf(
            "x-queue-type" to "quorum",
            // Counts redeliveries, not deliveries: limit 2 = 3 deliveries (verified on RabbitMQ 4.3.6, code review step 11).
            "x-delivery-limit" to MAX_DELIVERIES - 1,
            "x-dead-letter-exchange" to DEAD_LETTER_EXCHANGE,
            // Explicit, so dead-lettering never depends on the routing key a publisher happened to use.
            "x-dead-letter-routing-key" to ROUTING_KEY,
            // at-least-once dead-lettering requires reject-publish overflow (RabbitMQ quorum queue docs).
            "x-dead-letter-strategy" to "at-least-once",
            "x-overflow" to "reject-publish",
        )

    // -1 = unlimited: without it RabbitMQ 4.x silently drops a dead letter after 20 returns, leaving the row PENDING forever.
    val deadLetterQueueArguments: Map<String, Any> = mapOf("x-queue-type" to "quorum", "x-delivery-limit" to -1)
}
