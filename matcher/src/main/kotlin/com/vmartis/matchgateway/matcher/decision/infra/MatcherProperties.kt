package com.vmartis.matchgateway.matcher.decision.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The processing deadline bounds how long a stalled-but-connected worker holds a job before rejecting it for redelivery
 * (AC-28, NFR-6: decided within 30 s); RabbitMQ's own consumer timeout cannot go below one minute (ADR-009).
 */
@ConfigurationProperties("matcher")
data class MatcherProperties(
    val processingDeadline: Duration = Duration.ofSeconds(10),
    val deadLetterRetryDelay: Duration = Duration.ofSeconds(1),
) {
    init {
        require(processingDeadline > Duration.ZERO && processingDeadline <= Duration.ofSeconds(30)) {
            "matcher.processing-deadline must be > 0 and <= 30 s"
        }
        require(!deadLetterRetryDelay.isNegative) { "matcher.dead-letter-retry-delay must be >= 0" }
    }
}
