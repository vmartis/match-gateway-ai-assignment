package com.vmartis.matchgateway.matcher.decision.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Lease + poll interval bound the time from a replica crash to the retry (brief L46: decided within 30 s), so an
 * invalid combination fails startup instead of silently breaking AC-28.
 */
@ConfigurationProperties("matcher")
data class MatcherProperties(
    val lease: Duration = Duration.ofSeconds(10),
    val pollInterval: Duration = Duration.ofMillis(200),
    val batchSize: Int = 20,
    val retryDelay: Duration = Duration.ofSeconds(1),
    val maxAttempts: Int = 3,
) {
    init {
        require(lease > Duration.ZERO && pollInterval > Duration.ZERO) { "matcher.lease and matcher.poll-interval must be > 0" }
        require(!retryDelay.isNegative) { "matcher.retry-delay must be >= 0" }
        require(lease.plus(pollInterval) <= Duration.ofSeconds(30)) { "matcher.lease + matcher.poll-interval must be <= 30 s" }
        require(batchSize in 1..100) { "matcher.batch-size must be 1..100" }
        // The schema caps attempts at 3 (CHECK attempts BETWEEN 0 AND 3); a higher value would make claims violate it.
        require(maxAttempts in 1..3) { "matcher.max-attempts must be 1..3" }
    }
}
