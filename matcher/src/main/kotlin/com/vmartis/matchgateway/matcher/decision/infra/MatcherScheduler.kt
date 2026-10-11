package com.vmartis.matchgateway.matcher.decision.infra

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableConfigurationProperties(MatcherProperties::class)
class MatcherConfig

/** Polls the queue (disabled in tests with matcher.scheduling.enabled=false, which then call drain() directly). */
@Component
@EnableScheduling
@ConditionalOnProperty("matcher.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class MatcherScheduler(
    private val worker: MatcherWorker,
    private val queueDepth: QueueDepthGauge,
) {
    @Scheduled(fixedDelayString = "\${matcher.poll-interval:200ms}")
    fun poll() {
        try {
            worker.drain()
        } catch (e: DataAccessException) {
            // e.g. tables not migrated yet while the gateway starts (AM-22): retry on the next poll, log no data.
            log.warn("Queue poll failed: {}", e.javaClass.simpleName)
        }
    }

    // Fixed rate, not fixed delay: fixed-delay tasks share one scheduler thread, so a long drain() would freeze the gauge
    // exactly when the queue backs up. With virtual threads (spring.threads.virtual.enabled) Boot's SimpleAsyncTaskScheduler
    // runs each fixed-rate execution on a new thread; on platform threads this needs a scheduler pool > 1 (code review step 10).
    @Scheduled(fixedRateString = "\${matcher.queue-depth-refresh:5s}")
    fun refreshQueueDepth() {
        try {
            queueDepth.refresh()
        } catch (e: DataAccessException) {
            log.warn("Queue depth refresh failed: {}", e.javaClass.simpleName)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MatcherScheduler::class.java)
    }
}
