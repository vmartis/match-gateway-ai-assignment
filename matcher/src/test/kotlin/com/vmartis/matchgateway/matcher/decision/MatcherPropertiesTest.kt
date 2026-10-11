package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.decision.infra.MatcherProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/** AC-28: lease + poll interval must stay within the 30 s bound; startup fails otherwise. */
class MatcherPropertiesTest {
    private val runner =
        ApplicationContextRunner().withUserConfiguration(PropsConfig::class.java)

    @EnableConfigurationProperties(MatcherProperties::class)
    class PropsConfig

    @Test
    fun `defaults satisfy the 30 second bound`() {
        val p = MatcherProperties()
        assertThat(p.lease).isEqualTo(Duration.ofSeconds(10))
        assertThat(p.pollInterval).isEqualTo(Duration.ofMillis(200))
        assertThat(p.batchSize).isEqualTo(20)
        assertThat(p.retryDelay).isEqualTo(Duration.ofSeconds(1))
        assertThat(p.maxAttempts).isEqualTo(3)
        assertThat(p.lease.plus(p.pollInterval)).isLessThanOrEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `context starts with defaults`() {
        runner.run { ctx -> assertThat(ctx).hasNotFailed() }
    }

    @Test
    fun `context starts at exactly the 30 second boundary`() {
        runner.withPropertyValues("matcher.lease=29s", "matcher.poll-interval=1s").run { ctx ->
            assertThat(ctx).hasNotFailed()
        }
    }

    @Test
    fun `context fails when lease plus poll interval exceeds 30 seconds`() {
        runner.withPropertyValues("matcher.lease=30s", "matcher.poll-interval=1s").run { ctx ->
            assertThat(ctx).hasFailed()
        }
    }

    @Test
    fun `context fails when batch size is 0 or 101`() {
        runner.withPropertyValues("matcher.batch-size=0").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.batch-size=101").run { ctx -> assertThat(ctx).hasFailed() }
    }

    @Test
    fun `context fails when max attempts exceeds the schema cap of 3 or is 0`() {
        runner.withPropertyValues("matcher.max-attempts=4").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.max-attempts=0").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.max-attempts=3").run { ctx -> assertThat(ctx).hasNotFailed() }
    }

    @Test
    fun `context fails for a non-positive lease or poll interval or a negative retry delay`() {
        runner.withPropertyValues("matcher.lease=0s").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.poll-interval=0ms").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.retry-delay=-1s").run { ctx -> assertThat(ctx).hasFailed() }
        runner.withPropertyValues("matcher.retry-delay=0s").run { ctx -> assertThat(ctx).hasNotFailed() }
    }

    @Test
    fun `context starts at batch size boundaries 1 and 100`() {
        runner.withPropertyValues("matcher.batch-size=1").run { ctx -> assertThat(ctx).hasNotFailed() }
        runner.withPropertyValues("matcher.batch-size=100").run { ctx -> assertThat(ctx).hasNotFailed() }
    }
}
