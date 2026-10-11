package com.vmartis.matchgateway.gateway.tenant

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import com.vmartis.matchgateway.gateway.subject.TestTemplates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

/** Step 7: AC-37 (GET), AC-38 (gateway side), AC-39 – tenant A's data is invisible and untouched by tenant B. */
@Tag("security")
class TenantIsolationIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @LocalServerPort
    var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    private data class Response(
        val status: Int,
        val contentType: MediaType?,
        val body: String,
        val headers: HttpHeaders,
    )

    private val tenantA = "tenant-a"
    private val tenantB = "tenant-b"

    private fun token(
        tenant: String,
        scope: String = "enroll verify admin",
    ) = JwtTestTokens.valid(tenant = tenant, scope = scope)

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
        jdbc.sql("delete from tenant_settings").update()
    }

    private fun get(
        id: String,
        tenant: String,
    ): Response {
        val r =
            client
                .get()
                .uri(URI.create("http://localhost:$port/v1/verifications/$id"))
                .headers { it.setBearerAuth(token(tenant)) }
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun enrol(
        subject: String,
        template: String,
        tenant: String,
    ): Response {
        val r =
            client
                .post()
                .uri(URI.create("http://localhost:$port/v1/subjects/$subject/enrollment"))
                .headers {
                    it.setBearerAuth(token(tenant))
                    it.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }.body("""{"template":"$template"}""")
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun insertDecided(
        id: UUID,
        tenant: String,
    ) {
        jdbc
            .sql(
                "insert into verification(id, tenant_id, subject_id, status, score, threshold, decided_at) " +
                    "values (:id, :t, 's1', 'MATCH', 0.91, 0.5, timestamptz '2026-10-11T08:00:00Z')",
            ).param("id", id)
            .param("t", tenant)
            .update()
    }

    private fun insertPending(
        id: UUID,
        tenant: String,
    ) {
        jdbc
            .sql("insert into verification(id, tenant_id, subject_id, status, probe) values (:id, :t, 's1', 'PENDING', :p)")
            .param("id", id)
            .param("t", tenant)
            .param("p", TestTemplates.unitBytes())
            .update()
    }

    private fun versions(tenant: String): List<Pair<Int, Boolean>> =
        jdbc
            .sql("select version, active from template_version where tenant_id = :t and subject_id = 's1' order by version")
            .param("t", tenant)
            .query { rs, _ -> rs.getInt("version") to rs.getBoolean("active") }
            .list()

    private fun verificationRow(id: UUID): List<Any?> =
        jdbc
            .sql("select tenant_id, subject_id, status, score, threshold, decided_at from verification where id = :id")
            .param("id", id)
            .query { rs, _ ->
                listOf(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4),
                    rs.getObject(5),
                    rs.getTimestamp(6),
                )
            }.single()

    @Test
    fun `API1 AC-37 BOLA - tenant B cannot read tenant A's decided verification and A's row is unchanged`() {
        val id = UUID.randomUUID()
        insertDecided(id, tenantA)
        val before = verificationRow(id)

        val foreign = get(id.toString(), tenantB)

        assertThat(foreign.status).isEqualTo(404)
        assertThat(foreign.body)
            .doesNotContain("0.91")
            .doesNotContain("MATCH")
            .doesNotContain("s1")
            .doesNotContain(tenantA)
        assertThat(verificationRow(id)).isEqualTo(before)
        assertThat(get(id.toString(), tenantA).status).isEqualTo(200)
    }

    @Test
    fun `API1 AC-37 BOLA - tenant B cannot read tenant A's pending verification`() {
        val id = UUID.randomUUID()
        insertPending(id, tenantA)

        val foreign = get(id.toString(), tenantB)

        assertThat(foreign.status).isEqualTo(404)
        assertThat(foreign.body).doesNotContain("PENDING").doesNotContain(id.toString())
    }

    @Test
    fun `API1 AC-39 BOLA - another tenant's id and an unknown id give identical status content type and full body`() {
        val id = UUID.randomUUID()
        insertDecided(id, tenantA)
        val unknown = UUID.randomUUID()

        val foreign = get(id.toString(), tenantB)
        val missing = get(unknown.toString(), tenantB)

        assertThat(foreign.status).isEqualTo(404)
        assertThat(missing.status).isEqualTo(404)
        assertThat(foreign.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(foreign.contentType).isEqualTo(missing.contentType)
        val f = mapper.readTree(foreign.body)
        val m = mapper.readTree(missing.body)
        assertThat(f["type"].asString()).isEqualTo("urn:matchgw:problem:not-found")
        assertThat(f["type"]).isEqualTo(m["type"])
        assertThat(f["title"].asString()).isEqualTo(m["title"].asString())
        assertThat(f["detail"]?.asString()).isEqualTo(m["detail"]?.asString())
        assertThat(foreign.body).isEqualTo(missing.body)
        assertThat(foreign.body).doesNotContain(id.toString()).doesNotContain(unknown.toString())
    }

    @Test
    fun `API1 AC-38 gateway side - tenant B enrols its own s1 as version 1 and tenant A's s1 versions are unchanged`() {
        assertThat(enrol("s1", TestTemplates.unit(0), tenantA).status).isEqualTo(201)
        assertThat(enrol("s1", TestTemplates.unit(1), tenantA).status).isEqualTo(201)
        val aBefore = versions(tenantA)
        val aTemplates = templates(tenantA)
        assertThat(aBefore).containsExactly(1 to false, 2 to true)

        val r = enrol("s1", TestTemplates.unit(0), tenantB)

        assertThat(r.status).isEqualTo(201)
        val node = mapper.readTree(r.body)
        assertThat(node["subjectId"].asString()).isEqualTo("s1")
        assertThat(node["version"].asInt()).isEqualTo(1)
        assertThat(versions(tenantB)).containsExactly(1 to true)
        assertThat(versions(tenantA)).isEqualTo(aBefore)
        assertThat(templates(tenantA)).isEqualTo(aTemplates)
    }

    @Test
    fun `API1 AC-38 B's enrolment of s1 never reuses or overwrites A's template`() {
        enrol("s1", TestTemplates.unit(0), tenantA)
        enrol("s1", TestTemplates.unit(5), tenantB)

        assertThat(templates(tenantA).single()).isEqualTo(TestTemplates.unitBytes(0).toList())
        assertThat(templates(tenantB).single()).isEqualTo(TestTemplates.unitBytes(5).toList())
    }

    private fun templates(tenant: String): List<List<Byte>> =
        jdbc
            .sql("select template from template_version where tenant_id = :t and subject_id = 's1' order by version")
            .param("t", tenant)
            .query { rs, _ -> rs.getBytes(1).toList() }
            .list()
}
