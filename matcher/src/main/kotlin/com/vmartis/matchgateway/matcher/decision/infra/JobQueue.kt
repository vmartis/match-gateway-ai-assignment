package com.vmartis.matchgateway.matcher.decision.infra

import com.vmartis.matchgateway.matcher.decision.domain.Job
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * PostgreSQL work queue (ADR-003). The DB clock is the only clock. Every claim is an attempt (A-2); the terminal write is
 * guarded by status = PENDING and the claimant's lease token, so a stale or duplicate writer changes 0 rows (INV-1).
 */
@Repository
class JobQueue(
    private val jdbc: JdbcClient,
    private val properties: MatcherProperties,
) {
    fun claim(batch: Int): List<Job> {
        val token = UUID.randomUUID()
        return jdbc
            .sql(
                """
                WITH next AS (
                  SELECT id FROM verification
                  WHERE status = 'PENDING' AND attempts < :maxAttempts AND (lease_until IS NULL OR lease_until < now())
                  ORDER BY created_at
                  LIMIT :batch
                  FOR UPDATE SKIP LOCKED)
                UPDATE verification v
                   SET attempts = v.attempts + 1, lease_token = :token, lease_until = now() + make_interval(secs => :leaseSeconds)
                  FROM next WHERE v.id = next.id
                RETURNING v.id, v.tenant_id, v.subject_id, v.probe, v.attempts
                """.trimIndent(),
            ).param("maxAttempts", properties.maxAttempts)
            .param("batch", batch)
            .param("token", token)
            .param("leaseSeconds", properties.lease.seconds())
            .query { rs, _ ->
                Job(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getString("tenant_id"),
                    subjectId = rs.getString("subject_id"),
                    probe = rs.getBytes("probe"),
                    attempts = rs.getInt("attempts"),
                    leaseToken = token,
                )
            }.list()
    }

    /** Ends jobs that used all attempts and whose last lease ran out (AC-27). Returns the number of rows failed. */
    fun reap(): Int =
        jdbc
            .sql(
                """
                UPDATE verification
                   SET status = 'FAILED', reason = 'MAX_ATTEMPTS', probe = NULL, lease_token = NULL, lease_until = NULL,
                       decided_at = now()
                 WHERE status = 'PENDING' AND attempts >= :maxAttempts AND (lease_until IS NULL OR lease_until < now())
                """.trimIndent(),
            ).param("maxAttempts", properties.maxAttempts)
            .update()

    /** True iff this claimant wrote the one and only outcome; the probe is dropped in the same statement (D-11). */
    fun complete(
        job: Job,
        outcome: Outcome,
    ): Boolean {
        val (status, score, threshold, reason) =
            when (outcome) {
                is Outcome.Decided -> listOf(outcome.status, outcome.score, outcome.threshold, null)
                is Outcome.Failed -> listOf("FAILED", null, null, outcome.reason)
            }
        return jdbc
            .sql(
                """
                UPDATE verification
                   SET status = :status, score = :score, threshold = :threshold, reason = :reason,
                       probe = NULL, lease_token = NULL, lease_until = NULL, decided_at = now()
                 WHERE id = :id AND status = 'PENDING' AND lease_token = :token
                """.trimIndent(),
            ).param("status", status)
            .param("score", score)
            .param("threshold", threshold)
            .param("reason", reason)
            .param("id", job.id)
            .param("token", job.leaseToken)
            .update() == 1
    }

    /** Gives a failed attempt back after the retry delay (still guarded by the token). */
    fun release(job: Job) {
        jdbc
            .sql(
                """
                UPDATE verification SET lease_token = NULL, lease_until = now() + make_interval(secs => :delaySeconds)
                 WHERE id = :id AND status = 'PENDING' AND lease_token = :token
                """.trimIndent(),
            ).param("delaySeconds", properties.retryDelay.seconds())
            .param("id", job.id)
            .param("token", job.leaseToken)
            .update()
    }

    private fun Duration.seconds(): Double = toMillis() / 1000.0
}
