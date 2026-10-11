package com.vmartis.matchgateway.gateway.verification.domain

import java.util.UUID

/** Result of submitting a verification under an Idempotency-Key (ADR-008). */
sealed interface IdempotencyOutcome {
    data class Created(
        val id: UUID,
    ) : IdempotencyOutcome

    /** Same key and same body within 24 h: the original verification with its current status (AM-12). */
    data class Replayed(
        val id: UUID,
        val status: String,
    ) : IdempotencyOutcome

    /** Same key with a different body (AC-19). */
    data object KeyReused : IdempotencyOutcome
}
