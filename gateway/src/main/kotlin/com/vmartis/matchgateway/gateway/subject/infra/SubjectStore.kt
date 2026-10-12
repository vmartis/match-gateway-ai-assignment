package com.vmartis.matchgateway.gateway.subject.infra

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class SubjectStore(
    private val jdbc: JdbcClient,
) {
    /**
     * Creates or replaces the subject's active template and returns the new version (BR-4). The subject-row upsert locks
     * the row, so concurrent enrolments of one subject are serialised; the partial unique index guarantees one active
     * version (INV-2). Every statement is scoped by the caller's tenant (INV-4).
     */
    @Transactional
    fun enrol(
        tenantId: String,
        subjectId: String,
        template: ByteArray,
    ): Int {
        val version =
            jdbc
                .sql(
                    """
                    INSERT INTO subject (tenant_id, subject_id, last_version) VALUES (:tenant, :subject, 1)
                    ON CONFLICT (tenant_id, subject_id) DO UPDATE SET last_version = subject.last_version + 1
                    RETURNING last_version
                    """.trimIndent(),
                ).param("tenant", tenantId)
                .param("subject", subjectId)
                .query(Int::class.java)
                .single()
        jdbc
            .sql("UPDATE template_version SET active = false WHERE tenant_id = :tenant AND subject_id = :subject AND active")
            .param("tenant", tenantId)
            .param("subject", subjectId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO template_version (tenant_id, subject_id, version, template, active)
                VALUES (:tenant, :subject, :version, :template, true)
                """.trimIndent(),
            ).param("tenant", tenantId)
            .param("subject", subjectId)
            .param("version", version)
            .param("template", template)
            .update()
        return version
    }

    /**
     * Erases the subject in one transaction (ADR-007): its template versions (cascade), the probes of its pending
     * verifications, which end FAILED/SUBJECT_ERASED (D-8), and its idempotency fingerprints (A-S3). Decided
     * verifications stay, without template data. Returns false if the caller's tenant has no such subject.
     */
    @Transactional
    fun erase(
        tenantId: String,
        subjectId: String,
    ): Boolean {
        val deleted =
            jdbc
                .sql("DELETE FROM subject WHERE tenant_id = :tenant AND subject_id = :subject")
                .param("tenant", tenantId)
                .param("subject", subjectId)
                .update()
        if (deleted == 0) return false
        // status <> PENDING makes a late matcher write a guarded no-op (ADR-009, INV-5).
        jdbc
            .sql(
                """
                UPDATE verification
                   SET status = 'FAILED', reason = 'SUBJECT_ERASED', probe = NULL,
                       decided_at = now()
                 WHERE tenant_id = :tenant AND subject_id = :subject AND status = 'PENDING'
                """.trimIndent(),
            ).param("tenant", tenantId)
            .param("subject", subjectId)
            .update()
        jdbc
            .sql(
                """
                DELETE FROM idempotency_key
                 WHERE tenant_id = :tenant
                   AND verification_id IN (SELECT id FROM verification WHERE tenant_id = :tenant AND subject_id = :subject)
                """.trimIndent(),
            ).param("tenant", tenantId)
            .param("subject", subjectId)
            .update()
        return true
    }
}
