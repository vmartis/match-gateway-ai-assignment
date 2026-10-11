package com.vmartis.matchgateway.matcher.decision.infra

import com.vmartis.matchgateway.matcher.decision.domain.Job
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * PostgreSQL side of a job (ADR-009): delivery is at-least-once, so every terminal write is guarded by status = PENDING
 * and a duplicate, redelivered or late writer changes 0 rows (INV-1). The V2 trigger backs this up.
 */
@Repository
class JobQueue(
    private val jdbc: JdbcClient,
) {
    /** The pending verification, or null if unknown or already terminal (decided, erased, failed). */
    fun load(id: UUID): Job? =
        jdbc
            .sql("SELECT id, tenant_id, subject_id, probe FROM verification WHERE id = :id AND status = 'PENDING'")
            .param("id", id)
            .query {
                rs,
                _,
                ->
                Job(rs.getObject("id", UUID::class.java), rs.getString("tenant_id"), rs.getString("subject_id"), rs.getBytes("probe"))
            }.optional()
            .orElse(null)

    /**
     * Writes the outcome iff the verification is still pending; the probe is dropped in the same statement (D-11).
     * Returns decidedAt − createdAt when this call wrote the one and only outcome, null when it changed 0 rows.
     */
    fun complete(
        job: Job,
        outcome: Outcome,
    ): Duration? {
        val (status, score, threshold, reason) =
            when (outcome) {
                is Outcome.Decided -> listOf(outcome.status, outcome.score, outcome.threshold, null)
                is Outcome.Failed -> listOf("FAILED", null, null, outcome.reason)
            }
        return terminal(job.id, status, score, threshold, reason)
    }

    /** FAILED/MAX_ATTEMPTS for a dead-lettered job (AC-27), same guard and return value as [complete]. */
    fun failMaxAttempts(id: UUID): Duration? = terminal(id, "FAILED", null, null, "MAX_ATTEMPTS")

    private fun terminal(
        id: UUID,
        status: Any?,
        score: Any?,
        threshold: Any?,
        reason: Any?,
    ): Duration? =
        jdbc
            .sql(
                """
                UPDATE verification
                   SET status = :status, score = :score, threshold = :threshold, reason = :reason, probe = NULL, decided_at = now()
                 WHERE id = :id AND status = 'PENDING'
                RETURNING EXTRACT(EPOCH FROM decided_at - created_at)
                """.trimIndent(),
            ).param("status", status)
            .param("score", score)
            .param("threshold", threshold)
            .param("reason", reason)
            .param("id", id)
            .query(Double::class.java)
            .optional()
            // decidedAt − createdAt from the one DB clock (AM-11), for the latency histogram (AC-51).
            .map { Duration.ofNanos((it * NANOS_PER_SECOND).toLong()) }
            .orElse(null)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
