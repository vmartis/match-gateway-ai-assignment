package com.vmartis.matchgateway.matcher.decision.infra

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/** Queue depth = non-terminal verifications (AC-51). Refreshed on a schedule, so a scrape never queries the DB (ADR-006). */
@Component
class QueueDepthGauge(
    private val jdbc: JdbcClient,
    meters: MeterRegistry,
) {
    private val depth = AtomicLong()

    init {
        Gauge.builder("matchgw.queue.depth", depth) { it.get().toDouble() }.register(meters)
    }

    fun refresh() {
        depth.set(jdbc.sql("SELECT count(*) FROM verification WHERE status = 'PENDING'").query(Long::class.java).single())
    }
}
