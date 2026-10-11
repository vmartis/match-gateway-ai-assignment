package com.vmartis.matchgateway.gateway.verification.infra

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
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
) {
    /** The verification row is the matcher's job (ADR-003): PENDING with the probe until it is decided (D-11). */
    fun submit(
        tenantId: String,
        subjectId: String,
        probe: ByteArray,
    ): UUID {
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
        return id
    }

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
