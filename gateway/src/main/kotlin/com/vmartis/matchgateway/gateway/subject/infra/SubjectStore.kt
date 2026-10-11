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
}
