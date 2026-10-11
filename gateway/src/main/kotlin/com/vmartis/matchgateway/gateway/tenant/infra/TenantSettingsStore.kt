package com.vmartis.matchgateway.gateway.tenant.infra

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class TenantSettingsStore(
    private val jdbc: JdbcClient,
) {
    /** Creates or replaces the caller's decision threshold and returns the stored value (AC-45, INV-4). */
    fun setThreshold(
        tenantId: String,
        threshold: Double,
    ): Double =
        jdbc
            .sql(
                """
                INSERT INTO tenant_settings (tenant_id, threshold) VALUES (:tenant, :threshold)
                ON CONFLICT (tenant_id) DO UPDATE SET threshold = EXCLUDED.threshold, updated_at = now()
                RETURNING threshold
                """.trimIndent(),
            ).param("tenant", tenantId)
            .param("threshold", threshold)
            .query(Double::class.java)
            .single()
}
