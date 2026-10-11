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

/** Refreshes the queue-depth gauge (disabled in tests with matcher.scheduling.enabled=false). Jobs arrive by message. */
@Component
@EnableScheduling
@ConditionalOnProperty("matcher.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class MatcherScheduler(
    private val queueDepth: QueueDepthGauge,
) {
    // Fixed rate, not fixed delay: fixed-delay tasks share one scheduler thread, so a long task would freeze the gauge
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
