package com.vmartis.matchgateway.common.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

/** Step 11 / D-28: both apps declare the same topology; any drift makes RabbitMQ reject the second declaration. */
class TopologyTest {
    @Test
    fun `names and routing key are pinned`() {
        assertEquals("matchgw.verifications", Topology.EXCHANGE)
        assertEquals("matchgw.verifications", Topology.QUEUE)
        assertEquals("verification", Topology.ROUTING_KEY)
        assertEquals("matchgw.verifications.dlx", Topology.DEAD_LETTER_EXCHANGE)
        assertEquals("matchgw.verifications.dead", Topology.DEAD_LETTER_QUEUE)
        assertEquals(3, Topology.MAX_DELIVERIES)
    }

    @Test
    fun `main queue arguments are a quorum queue with delivery limit dead lettering and reject-publish`() {
        assertEquals(
            mapOf<String, Any>(
                "x-queue-type" to "quorum",
                // x-delivery-limit counts redeliveries: 2 → 3 deliveries in total (AC-27; code review step 11).
                "x-delivery-limit" to 2,
                "x-dead-letter-exchange" to "matchgw.verifications.dlx",
                "x-dead-letter-routing-key" to "verification",
                "x-dead-letter-strategy" to "at-least-once",
                "x-overflow" to "reject-publish",
            ),
            Topology.queueArguments,
        )
    }

    @Test
    fun `dead letter queue is a quorum queue that never drops a message`() {
        // Without an explicit limit RabbitMQ 4.x drops a quorum-queue message after 20 returns; -1 = unlimited.
        assertEquals(mapOf<String, Any>("x-queue-type" to "quorum", "x-delivery-limit" to -1), Topology.deadLetterQueueArguments)
    }

    @Test
    fun `delivery limit argument is an Int one below the maximum number of deliveries`() {
        assertEquals(Topology.MAX_DELIVERIES - 1, Topology.queueArguments["x-delivery-limit"])
    }
}
