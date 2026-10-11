package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.decision.infra.MatcherProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import kotlin.reflect.full.memberProperties

/** AC-28: the processing deadline is > 0 and <= 30 s, startup fails otherwise (ADR-009). */
class MatcherPropertiesTest {
    private val runner =
        ApplicationContextRunner().withUserConfiguration(PropsConfig::class.java)

    @EnableConfigurationProperties(MatcherProperties::class)
    class PropsConfig

    @Test
    fun `defaults are a 10 second processing deadline and a 1 second dead letter retry delay`() {
        val p = MatcherProperties()
        assertThat(p.processingDeadline).isEqualTo(Duration.ofSeconds(10))
        assertThat(p.deadLetterRetryDelay).isEqualTo(Duration.ofSeconds(1))
        assertThat(p.processingDeadline).isLessThanOrEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `the polling queue properties are gone`() {
        assertThat(MatcherProperties::class.memberProperties.map { it.name })
            .containsExactlyInAnyOrder("processingDeadline", "deadLetterRetryDelay")
    }

    @Test
    fun `context starts with defaults and binds the configured values`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(MatcherProperties::class.java).processingDeadline).isEqualTo(Duration.ofSeconds(10))
        }
        runner.withPropertyValues("matcher.processing-deadline=2s", "matcher.dead-letter-retry-delay=250ms").run { ctx ->
            assertThat(ctx).hasNotFailed()
            val p = ctx.getBean(MatcherProperties::class.java)
            assertThat(p.processingDeadline).isEqualTo(Duration.ofSeconds(2))
            assertThat(p.deadLetterRetryDelay).isEqualTo(Duration.ofMillis(250))
        }
    }

    @Test
    fun `context starts at exactly the 30 second boundary and at 1 millisecond`() {
        runner.withPropertyValues("matcher.processing-deadline=30s").run { ctx -> assertThat(ctx).hasNotFailed() }
        runner.withPropertyValues("matcher.processing-deadline=1ms").run { ctx -> assertThat(ctx).hasNotFailed() }
    }

    @Test
    fun `context fails when the processing deadline exceeds 30 seconds`() {
        runner.withPropertyValues("matcher.processing-deadline=30001ms").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.processing-deadline=31s").run { ctx -> assertThat(ctx).hasFailed() }
    }

    @Test
    fun `context fails when the processing deadline is zero or negative`() {
        runner.withPropertyValues("matcher.processing-deadline=0s").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.processing-deadline=-1s").run { ctx -> assertThat(ctx).hasFailed() }
    }

    @Test
    fun `dead letter retry delay accepts 0 and fails when negative`() {
        runner.withPropertyValues("matcher.dead-letter-retry-delay=0s").run { ctx -> assertThat(ctx).hasNotFailed() }
        runner.withPropertyValues("matcher.dead-letter-retry-delay=-1ms").run { ctx -> assertThat(ctx).hasFailed() }
    }
}
