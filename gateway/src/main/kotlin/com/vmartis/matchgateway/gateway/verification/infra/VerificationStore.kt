package com.vmartis.matchgateway.gateway.verification.infra

import com.vmartis.matchgateway.gateway.verification.domain.IdempotencyOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Persistence read model; the API maps it to its own DTO (DTOs at the boundary, CLAUDE.md §3). */
data class VerificationView(
    val id: UUID,
    val subjectId: String,
    val status: String,
    val score: Double?,
    val threshold: Double?,
    val reason: String?,
    val decidedAt: Instant?,
)

@Repository
class VerificationStore(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) {
    /**
     * Creates a PENDING verification (the matcher's job, ADR-003; probe kept until decided, D-11) bound to the tenant's
     * Idempotency-Key (ADR-008). The key row's primary key is the concurrency guarantee (AC-21): a key in use within
     * 24 h makes the upsert return nothing, the new verification is rolled back, and the stored record decides.
     */
    fun submit(
        tenantId: String,
        idempotencyKey: String,
        subjectId: String,
        probe: ByteArray,
        fingerprint: ByteArray,
    ): IdempotencyOutcome {
        // The key record can vanish between the rollback and the read only through erasure (ADR-007): then submit anew.
        // Each retry needs another enrol + concurrent submit + erase of the same subject, so a small bound suffices.
        repeat(MAX_ATTEMPTS) {
            attempt(tenantId, idempotencyKey, subjectId, probe, fingerprint)?.let { return it }
        }
        error("idempotency record kept vanishing")
    }

    private fun attempt(
        tenantId: String,
        idempotencyKey: String,
        subjectId: String,
        probe: ByteArray,
        fingerprint: ByteArray,
    ): IdempotencyOutcome? {
        val created =
            transactions.execute { tx ->
                val id = UUID.randomUUID()
                jdbc
                    .sql(
                        """
                        INSERT INTO verification (id, tenant_id, subject_id, status, probe, attempts)
                        VALUES (:id, :tenant, :subject, 'PENDING', :probe, 0)
                        """.trimIndent(),
                    ).param("id", id)
                    .param("tenant", tenantId)
                    .param("subject", subjectId)
                    .param("probe", probe)
                    .update()
                val bound =
                    jdbc
                        .sql(
                            """
                            INSERT INTO idempotency_key (tenant_id, idem_key, fingerprint, verification_id)
                            VALUES (:tenant, :key, :fingerprint, :id)
                            ON CONFLICT (tenant_id, idem_key) DO UPDATE
                               SET fingerprint = EXCLUDED.fingerprint, verification_id = EXCLUDED.verification_id, created_at = now()
                             WHERE idempotency_key.created_at < now() - interval '24 hours'
                            RETURNING verification_id
                            """.trimIndent(),
                        ).param("tenant", tenantId)
                        .param("key", idempotencyKey)
                        .param("fingerprint", fingerprint)
                        .param("id", id)
                        .query(UUID::class.java)
                        .optional()
                if (bound.isEmpty) {
                    tx.setRollbackOnly()
                } else {
                    // Same transaction as the verification: no job without a message, no message without a job (ADR-009).
                    jdbc.sql("INSERT INTO outbox (verification_id) VALUES (:id)").param("id", id).update()
                }
                bound.orElse(null)
            }
        if (created != null) return IdempotencyOutcome.Created(created)
        val existing = findKey(tenantId, idempotencyKey) ?: return null
        return if (MessageDigest.isEqual(existing.fingerprint, fingerprint)) {
            IdempotencyOutcome.Replayed(existing.verificationId, existing.status)
        } else {
            IdempotencyOutcome.KeyReused
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }

    private class KeyRecord(
        val fingerprint: ByteArray,
        val verificationId: UUID,
        val status: String,
    )

    private fun findKey(
        tenantId: String,
        idempotencyKey: String,
    ): KeyRecord? =
        jdbc
            .sql(
                """
                SELECT k.fingerprint, v.id, v.status
                  FROM idempotency_key k JOIN verification v ON v.id = k.verification_id AND v.tenant_id = k.tenant_id
                 WHERE k.tenant_id = :tenant AND k.idem_key = :key
                """.trimIndent(),
            ).param("tenant", tenantId)
            .param("key", idempotencyKey)
            .query { rs, _ -> KeyRecord(rs.getBytes("fingerprint"), rs.getObject("id", UUID::class.java), rs.getString("status")) }
            .optional()
            .orElse(null)

    /** Tenant-scoped read: another tenant's id behaves exactly like an unknown one (brief L36). The probe is never read. */
    fun find(
        tenantId: String,
        id: UUID,
    ): VerificationView? =
        jdbc
            .sql(
                """
                SELECT id, subject_id, status, score, threshold, reason, decided_at
                FROM verification WHERE id = :id AND tenant_id = :tenant
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenantId)
            .query { rs, _ -> rs.toResult() }
            .optional()
            .orElse(null)

    private fun ResultSet.toResult() =
        VerificationView(
            id = getObject("id", UUID::class.java),
            subjectId = getString("subject_id"),
            status = getString("status"),
            score = getObject("score") as Double?,
            threshold = getObject("threshold") as Double?,
            reason = getString("reason"),
            decidedAt = getObject("decided_at", java.time.OffsetDateTime::class.java)?.toInstant(),
        )
}
