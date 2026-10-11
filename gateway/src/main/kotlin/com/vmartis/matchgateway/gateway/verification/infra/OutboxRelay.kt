package com.vmartis.matchgateway.gateway.verification.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** Re-publishes outbox rows the eager publish did not confirm: lost publish, broker outage, gateway crash (ADR-009). */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val publisher: OutboxPublisher,
    // Longer than the publisher's confirm timeout (5 s), so the relay does not re-publish ids still awaiting their confirm.
    @Value("\${app.outbox.relay-age:15s}") private val relayAge: Duration,
) {
    @Volatile private var failing = false

    /** Publishes due rows oldest first and returns how many were confirmed; stops at the first failure (broker likely down). */
    fun relayDue(): Int {
        val due =
            jdbc
                .sql(
                    """
                    SELECT verification_id FROM outbox
                     WHERE created_at <= now() - make_interval(secs => :age)
                     ORDER BY created_at
                     LIMIT $BATCH
                    """.trimIndent(),
                ).param("age", relayAge.toMillis() / 1000.0)
                .query(UUID::class.java)
                .list()
                .filterNotNull() // primary key column: never null
        var published = 0
        for (id in due) {
            if (!publisher.publish(id)) {
                // One WARN per outage, not per run: the relay retries every second (security review step 12).
                if (!failing) {
                    log.warn(
                        "Outbox relay cannot publish; {} job(s) wait in the outbox until the broker is back",
                        due.size - published,
                    )
                }
                failing = true
                return published
            }
            published++
        }
        // Recovered only once something was actually published; an idle tick during an outage proves nothing.
        if (failing && published > 0) {
            log.info("Outbox relay publishing again")
            failing = false
        }
        return published
    }

    private companion object {
        const val BATCH = 100
        val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}

/** Schedules the relay (disabled in tests with app.outbox.relay.enabled=false, which then call relayDue() directly). */
@Component
@EnableScheduling
@ConditionalOnProperty("app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler(
    private val relay: OutboxRelay,
) {
    @Scheduled(fixedDelayString = "\${app.outbox.relay-interval:1s}")
    fun relay() {
        relay.relayDue()
    }
}
