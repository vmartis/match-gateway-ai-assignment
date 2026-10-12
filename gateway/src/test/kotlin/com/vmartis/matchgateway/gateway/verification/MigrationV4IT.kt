package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import javax.sql.DataSource

/** Step 14 (D-28, ADR-009): V4 backfills the outbox for PENDING jobs and drops the lease columns and their constraint. */
class MigrationV4IT : GatewayIntegrationTest() {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jdbc: JdbcClient

    private val schema = "v4check"
    private val dropped = setOf("lease_token", "lease_until", "attempts")

    private fun flyway(target: String? = null): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .schemas(schema)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

    @AfterEach
    fun dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS $schema CASCADE").update()
    }

    private fun columns(inSchema: String): Set<String> =
        jdbc
            .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = :s AND table_name = 'verification'")
            .param("s", inSchema)
            .query(String::class.java)
            .set()
            .filterNotNullTo(mutableSetOf())

    private fun leaseConstraints(inSchema: String): List<String> =
        jdbc
            .sql(
                "SELECT c.conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid " +
                    "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                    "WHERE n.nspname = :s AND t.relname = 'verification' AND c.conname = 'verification_lease_only_while_pending'",
            ).param("s", inSchema)
            .query(String::class.java)
            .list()
            .filterNotNull()

    @Test
    fun `ADR-009 V4 V4 backfills one outbox row per PENDING verification and none for decided ones`() {
        flyway("3").migrate()
        val pending = UUID.randomUUID()
        val decided = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO $schema.verification (id, tenant_id, subject_id, status, probe) VALUES (:id, 't', 's', 'PENDING', :p)")
            .param("id", pending)
            .param("p", ByteArray(512))
            .update()
        jdbc
            .sql(
                "INSERT INTO $schema.verification (id, tenant_id, subject_id, status, score, threshold, decided_at) " +
                    "VALUES (:id, 't', 's', 'MATCH', 0.9, 0.5, now())",
            ).param("id", decided)
            .update()
        assertThat(columns(schema)).containsAll(dropped)

        flyway().migrate()

        val outbox = jdbc.sql("SELECT verification_id FROM $schema.outbox").query(UUID::class.java).list()
        assertThat(outbox).containsExactly(pending)
        assertThat(columns(schema)).doesNotContainAnyElementsOf(dropped)
        assertThat(leaseConstraints(schema)).isEmpty()
        // The remaining D-11 invariants survive the migration.
        assertThat(jdbc.sql("SELECT count(*) FROM $schema.verification").query(Long::class.java).single()).isEqualTo(2L)
    }

    @Test
    fun `ADR-009 V4 V4 backfill does not duplicate an outbox row that already exists`() {
        flyway("3").migrate()
        val pending = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO $schema.verification (id, tenant_id, subject_id, status, probe) VALUES (:id, 't', 's', 'PENDING', :p)")
            .param("id", pending)
            .param("p", ByteArray(512))
            .update()
        jdbc.sql("INSERT INTO $schema.outbox (verification_id) VALUES (:id)").param("id", pending).update()

        flyway().migrate()

        val outbox = jdbc.sql("SELECT verification_id FROM $schema.outbox").query(UUID::class.java).list()
        assertThat(outbox).containsExactly(pending)
    }

    @Test
    fun `ADR-009 V4 the application schema has no lease columns and no lease constraint`() {
        assertThat(columns("public")).isNotEmpty().doesNotContainAnyElementsOf(dropped)
        assertThat(leaseConstraints("public")).isEmpty()
    }
}
