package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.decision.domain.Decision
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** AC-3 / AC-24: MATCH iff score >= threshold. */
class DecisionTest {
    @Test
    fun `score equal to threshold is a MATCH`() {
        assertThat(Decision.decide(0.5, 0.5)).isEqualTo(Outcome.Decided("MATCH", 0.5, 0.5))
    }

    @Test
    fun `score just below threshold is NO_MATCH`() {
        assertThat(Decision.decide(0.5 - 1e-9, 0.5)).isEqualTo(Outcome.Decided("NO_MATCH", 0.5 - 1e-9, 0.5))
    }

    @Test
    fun `score just above threshold is MATCH`() {
        assertThat(Decision.decide(0.5 + 1e-9, 0.5).status).isEqualTo("MATCH")
    }

    @Test
    fun `threshold 0 matches a score of 0 and NO_MATCH for negative scores`() {
        assertThat(Decision.decide(0.0, 0.0).status).isEqualTo("MATCH")
        assertThat(Decision.decide(-0.25, 0.0).status).isEqualTo("NO_MATCH")
    }

    @Test
    fun `threshold 1 matches only a perfect score`() {
        assertThat(Decision.decide(1.0, 1.0).status).isEqualTo("MATCH")
        assertThat(Decision.decide(1.0 - 1e-9, 1.0).status).isEqualTo("NO_MATCH")
    }
}
