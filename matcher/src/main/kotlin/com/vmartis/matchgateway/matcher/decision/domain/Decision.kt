package com.vmartis.matchgateway.matcher.decision.domain

import java.util.UUID

/** Terminal result of one verification (INV-1: written at most once). */
sealed interface Outcome {
    data class Decided(
        val status: String,
        val score: Double,
        val threshold: Double,
    ) : Outcome

    data class Failed(
        val reason: String,
    ) : Outcome
}

/** MATCH iff score >= the tenant threshold at decision time (brief L44, INV-3). */
object Decision {
    fun decide(
        score: Double,
        threshold: Double,
    ): Outcome.Decided = Outcome.Decided(if (score >= threshold) "MATCH" else "NO_MATCH", score, threshold)
}

/** A claimed verification: the lease token proves ownership for the terminal write (ADR-003). */
class Job(
    val id: UUID,
    val tenantId: String,
    val subjectId: String,
    val probe: ByteArray,
    val attempts: Int,
    val leaseToken: UUID,
)
