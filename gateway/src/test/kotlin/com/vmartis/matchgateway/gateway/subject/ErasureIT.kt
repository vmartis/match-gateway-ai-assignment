package com.vmartis.matchgateway.gateway.subject

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Step 8: AC-47..AC-50, AC-37 (DELETE), AC-7 (delete) through the real filter chain and a real Postgres (ADR-007). */
class ErasureIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @LocalServerPort
    var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    private val tenantA = "tenant-a"
    private val tenantB = "tenant-b"
    private val decidedAt = Instant.parse("2026-01-01T00:00:00Z")

    private data class Response(
        val status: Int,
        val contentType: MediaType?,
        val body: String,
        val headers: HttpHeaders,
    )

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
    }

    private fun admin(tenant: String = tenantA) = JwtTestTokens.valid(tenant = tenant, scope = "admin")

    private fun both(tenant: String = tenantA) = JwtTestTokens.valid(tenant = tenant, scope = "enroll verify admin")

    private fun call(
        method: HttpMethod,
        rawPath: String,
        token: String?,
        body: String? = null,
    ): Response {
        val spec =
            client
                .method(method)
                .uri(URI.create("http://localhost:$port$rawPath"))
                .headers { h ->
                    token?.let { h.setBearerAuth(it) }
                    if (body != null) h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }
        val r = (if (body != null) spec.body(body) else spec).exchange().returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun erase(
        subjectId: String,
        token: String? = admin(),
    ) = call(HttpMethod.DELETE, "/v1/subjects/$subjectId", token)

    private fun enrol(
        subjectId: String,
        index: Int,
        tenant: String = tenantA,
    ) = call(HttpMethod.POST, "/v1/subjects/$subjectId/enrollment", both(tenant), """{"template":"${TestTemplates.unit(index)}"}""")

    private fun get(
        id: UUID,
        tenant: String = tenantA,
    ) = call(HttpMethod.GET, "/v1/verifications/$id", both(tenant))

    private fun insertPending(
        tenant: String,
        subject: String,
        attempts: Int = 0,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "insert into verification (id, tenant_id, subject_id, status, probe, attempts) " +
                    "values (:id, :t, :s, 'PENDING', :probe, :attempts)",
            ).param("id", id)
            .param("t", tenant)
            .param("s", subject)
            .param("probe", TestTemplates.unitBytes(3))
            .param("attempts", attempts)
            .update()
        return id
    }

    private fun insertDecided(
        tenant: String,
        subject: String,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val scored = status != "FAILED"
        jdbc
            .sql(
                "insert into verification (id, tenant_id, subject_id, status, probe, score, threshold, reason, attempts, decided_at) " +
                    "values (:id, :t, :s, :status, null, :score, :threshold, :reason, 1, :decidedAt)",
            ).param("id", id)
            .param("t", tenant)
            .param("s", subject)
            .param("status", status)
            .param("score", if (scored) (if (status == "MATCH") 0.93 else 0.12) else null)
            .param("threshold", if (scored) 0.6 else null)
            .param("reason", if (scored) null else "MAX_ATTEMPTS")
            .param("decidedAt", java.sql.Timestamp.from(decidedAt))
            .update()
        return id
    }

    private fun insertIdempotency(
        tenant: String,
        key: String,
        verificationId: UUID,
    ) {
        jdbc
            .sql("insert into idempotency_key (tenant_id, idem_key, fingerprint, verification_id) values (:t, :k, :f, :v)")
            .param("t", tenant)
            .param("k", key)
            .param("f", ByteArray(32) { 7 })
            .param("v", verificationId)
            .update()
    }

    private fun versions(
        tenant: String,
        subject: String,
    ): Int =
        jdbc
            .sql("select count(*) from template_version where tenant_id = :t and subject_id = :s")
            .param("t", tenant)
            .param("s", subject)
            .query(Int::class.java)
            .single()

    private fun subjects(
        tenant: String,
        subject: String,
    ): Int =
        jdbc
            .sql("select count(*) from subject where tenant_id = :t and subject_id = :s")
            .param("t", tenant)
            .param("s", subject)
            .query(Int::class.java)
            .single()

    private fun probes(
        tenant: String,
        subject: String,
    ): Int =
        jdbc
            .sql("select count(*) from verification where tenant_id = :t and subject_id = :s and probe is not null")
            .param("t", tenant)
            .param("s", subject)
            .query(Int::class.java)
            .single()

    private fun idempotencyKeys(tenant: String): Set<String> =
        jdbc
            .sql("select idem_key from idempotency_key where tenant_id = :t")
            .param("t", tenant)
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()

    private fun row(id: UUID): Map<String, Any?> =
        jdbc
            .sql("select * from verification where id = :id")
            .param("id", id)
            .query()
            .singleRow()
            .mapValues { norm(it.value) }

    /** ByteArray has identity equality; compare content so full-row snapshots are meaningful. */
    private fun norm(v: Any?): Any? = if (v is ByteArray) v.toList() else v

    /** Everything that must stay untouched when a delete is rejected. */
    private fun snapshot(): List<Any?> =
        listOf(
            jdbc.sql("select * from subject order by tenant_id, subject_id").query().listOfRows().map { r ->
                r.mapValues { norm(it.value) }
            },
            jdbc.sql("select * from template_version order by tenant_id, subject_id, version").query().listOfRows().map { r ->
                r.mapValues { norm(it.value) }
            },
            jdbc
                .sql("select * from verification order by id")
                .query()
                .listOfRows()
                .map { r -> r.mapValues { norm(it.value) } },
            jdbc.sql("select * from idempotency_key order by tenant_id, idem_key").query().listOfRows().map { r ->
                r.mapValues { norm(it.value) }
            },
        )

    private fun assertNotFoundProblem(r: Response) {
        assertThat(r.status).isEqualTo(404)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node["type"].asString()).isEqualTo("urn:matchgw:problem:not-found")
        assertThat(node["status"].asInt()).isEqualTo(404)
        assertThat(r.body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("trace")
    }

    // ---------------------------------------------------------------- AC-47

    @Test
    fun `AC-47 erasure removes versions and probes, finishes pending as SUBJECT_ERASED and keeps decided rows unchanged`() {
        assertThat(enrol("s1", 0).status).isEqualTo(201)
        assertThat(enrol("s1", 1).status).isEqualTo(201)
        val pending = insertPending(tenantA, "s1")
        val match = insertDecided(tenantA, "s1", "MATCH")
        val noMatch = insertDecided(tenantA, "s1", "NO_MATCH")
        val failed = insertDecided(tenantA, "s1", "FAILED")
        val decidedBefore = listOf(match, noMatch, failed).associateWith { row(it) }
        assertThat(versions(tenantA, "s1")).isEqualTo(2)
        assertThat(probes(tenantA, "s1")).isEqualTo(1)

        val r = erase("s1")

        assertThat(r.status).isEqualTo(204)
        assertThat(r.body).isEmpty()
        assertThat(subjects(tenantA, "s1")).isZero()
        assertThat(versions(tenantA, "s1")).isZero()
        assertThat(probes(tenantA, "s1")).isZero()
        decidedBefore.forEach { (id, before) -> assertThat(row(id)).isEqualTo(before) }

        val m = mapper.readTree(get(match).body)
        assertThat(m["status"].asString()).isEqualTo("MATCH")
        assertThat(m["score"].asDouble()).isEqualTo(0.93)
        assertThat(m["threshold"].asDouble()).isEqualTo(0.6)
        assertThat(Instant.parse(m["decidedAt"].asString())).isEqualTo(decidedAt)
        val n = mapper.readTree(get(noMatch).body)
        assertThat(n["status"].asString()).isEqualTo("NO_MATCH")
        assertThat(n["score"].asDouble()).isEqualTo(0.12)
        assertThat(n["threshold"].asDouble()).isEqualTo(0.6)
        assertThat(Instant.parse(n["decidedAt"].asString())).isEqualTo(decidedAt)
        val f = mapper.readTree(get(failed).body)
        assertThat(f["status"].asString()).isEqualTo("FAILED")
        assertThat(f["reason"].asString()).isEqualTo("MAX_ATTEMPTS")

        val p = mapper.readTree(get(pending).body)
        assertThat(p["status"].asString()).isEqualTo("FAILED")
        assertThat(p["reason"].asString()).isEqualTo("SUBJECT_ERASED")
        assertThat(p["subjectId"].asString()).isEqualTo("s1")
        assertThat(p["score"].isNull).isTrue()
        assertThat(Instant.parse(p["decidedAt"].asString())).isAfter(decidedAt)
        val pendingRow = row(pending)
        assertThat(pendingRow["status"]).isEqualTo("FAILED")
        assertThat(pendingRow["reason"]).isEqualTo("SUBJECT_ERASED")
        assertThat(pendingRow["probe"]).isNull()
        assertThat(pendingRow["lease_token"]).isNull()
        assertThat(pendingRow["lease_until"]).isNull()
        assertThat(pendingRow["decided_at"]).isNotNull()
    }

    @Test
    fun `AC-47 a claimed pending verification is also finished as SUBJECT_ERASED with lease cleared`() {
        enrol("s1", 0)
        val pending = insertPending(tenantA, "s1", attempts = 1)
        jdbc
            .sql("update verification set lease_token = :t, lease_until = now() + interval '10 seconds' where id = :id")
            .param("t", UUID.randomUUID())
            .param("id", pending)
            .update()

        assertThat(erase("s1").status).isEqualTo(204)

        val r = row(pending)
        assertThat(r["status"]).isEqualTo("FAILED")
        assertThat(r["reason"]).isEqualTo("SUBJECT_ERASED")
        assertThat(r["probe"]).isNull()
        assertThat(r["lease_token"]).isNull()
        assertThat(r["lease_until"]).isNull()
    }

    @Test
    fun `AC-47 idempotency rows of the erased subject are deleted and those of other subjects and tenants stay`() {
        enrol("s1", 0)
        enrol("s2", 1)
        enrol("s1", 2, tenantB)
        val a1 = insertPending(tenantA, "s1")
        val a1done = insertDecided(tenantA, "s1", "MATCH")
        val a2 = insertPending(tenantA, "s2")
        val b1 = insertPending(tenantB, "s1")
        insertIdempotency(tenantA, "k-a1", a1)
        insertIdempotency(tenantA, "k-a1-done", a1done)
        insertIdempotency(tenantA, "k-a2", a2)
        insertIdempotency(tenantB, "k-b1", b1)
        val others = snapshotOf(tenantA, "s2", tenantB, "s1", a2, b1)

        assertThat(erase("s1").status).isEqualTo(204)

        assertThat(idempotencyKeys(tenantA)).containsExactly("k-a2")
        assertThat(idempotencyKeys(tenantB)).containsExactly("k-b1")
        assertThat(snapshotOf(tenantA, "s2", tenantB, "s1", a2, b1)).isEqualTo(others)
    }

    private fun snapshotOf(
        t1: String,
        s1: String,
        t2: String,
        s2: String,
        v1: UUID,
        v2: UUID,
    ): List<Any?> = listOf(versions(t1, s1), versions(t2, s2), row(v1), row(v2), subjects(t1, s1), subjects(t2, s2))

    @Test
    fun `AC-47 erasure is atomic - a failure in its last statement leaves the subject, versions, probe and fingerprint intact`() {
        enrol("s1", 0)
        enrol("s1", 1)
        val pending = insertPending(tenantA, "s1")
        insertIdempotency(tenantA, "k-a1", pending)
        val before = snapshotOf(tenantA, "s1", tenantB, "s1", pending, pending)
        // Fault injection: the idempotency delete (the last of the three statements) fails after the others ran.
        jdbc
            .sql(
                """
                CREATE FUNCTION erasure_fault() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'injected fault'; END $$
                """.trimIndent(),
            ).update()
        jdbc.sql("CREATE TRIGGER erasure_fault BEFORE DELETE ON idempotency_key FOR EACH ROW EXECUTE FUNCTION erasure_fault()").update()
        try {
            assertThat(erase("s1").status).isEqualTo(500)
        } finally {
            jdbc.sql("DROP TRIGGER erasure_fault ON idempotency_key").update()
            jdbc.sql("DROP FUNCTION erasure_fault()").update()
        }

        assertThat(snapshotOf(tenantA, "s1", tenantB, "s1", pending, pending)).isEqualTo(before)
        assertThat(versions(tenantA, "s1")).isEqualTo(2)
        assertThat(probes(tenantA, "s1")).isEqualTo(1)
        assertThat(idempotencyKeys(tenantA)).containsExactly("k-a1")
        // The client can retry and complete the erasure.
        assertThat(erase("s1").status).isEqualTo(204)
    }

    @Test
    fun `AC-47 a subject without verifications is erased with 204`() {
        enrol("s1", 0)

        assertThat(erase("s1").status).isEqualTo(204)

        assertThat(subjects(tenantA, "s1")).isZero()
        assertThat(versions(tenantA, "s1")).isZero()
    }

    @Test
    fun `AC-47 erasing s1 does not touch s10, s1x or another tenant's s1`() {
        listOf("s1", "s10", "s1x").forEach { enrol(it, 0) }
        enrol("s1", 1, tenantB)
        val pendingOther = listOf("s10", "s1x").map { insertPending(tenantA, it) }
        val pendingB = insertPending(tenantB, "s1")
        val beforeOthers = (pendingOther + pendingB).map { row(it) }

        assertThat(erase("s1").status).isEqualTo(204)

        assertThat(versions(tenantA, "s10")).isEqualTo(1)
        assertThat(versions(tenantA, "s1x")).isEqualTo(1)
        assertThat(versions(tenantB, "s1")).isEqualTo(1)
        assertThat((pendingOther + pendingB).map { row(it) }).isEqualTo(beforeOthers)
        assertThat(probes(tenantA, "s10")).isEqualTo(1)
        assertThat(probes(tenantB, "s1")).isEqualTo(1)
    }

    // ---------------------------------------------------------------- AC-48

    @Test
    fun `AC-48 unknown subject gives 404 problem detail`() {
        val r = erase("nobody")

        assertNotFoundProblem(r)
        assertThat(r.body).doesNotContain("nobody")
    }

    @Test
    fun `AC-48 second delete of an erased subject gives 404 identical to unknown`() {
        enrol("s1", 0)
        assertThat(erase("s1").status).isEqualTo(204)

        val second = erase("s1")
        val unknown = erase("never-existed")

        assertNotFoundProblem(second)
        assertThat(second.body).isEqualTo(unknown.body)
        assertThat(second.contentType).isEqualTo(unknown.contentType)
    }

    @ParameterizedTest(name = "AC-48 malformed subject id [{0}] gives the constant 400 invalid-request problem and changes nothing")
    @ValueSource(
        strings = ["bad%20id", "s%24", "caf%C3%A9", "x0123456789012345678901234567890123456789012345678901234567890123"],
    )
    fun `AC-48 malformed subject id gives 400 invalid-request and changes nothing`(id: String) {
        // D-26 (human): a malformed id is a 400 like on enrolment (ARCHITECTURE API table), not the 404 of an unknown id.
        enrol("s1", 0)
        insertPending(tenantA, "s1")
        val before = snapshot()

        val r = erase(id)

        assertThat(r.status).isEqualTo(400)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node["type"].asString()).isEqualTo("urn:matchgw:problem:invalid-request")
        assertThat(r.body).doesNotContain(id).doesNotContain("Exception").doesNotContain("trace")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `AC-48 a 64 character subject id is accepted and erased`() {
        val id = "a".repeat(64)
        enrol(id, 0)

        assertThat(erase(id).status).isEqualTo(204)
        assertThat(subjects(tenantA, id)).isZero()
    }

    // ---------------------------------------------------------------- AC-49

    private val claimedLease =
        "update verification set attempts = attempts + 1, lease_token = :token, " +
            "lease_until = now() + interval '10 seconds' where id = :id"

    @Test
    fun `AC-49 a late matcher write after claim and erasure is a guarded no-op and the row stays SUBJECT_ERASED`() {
        enrol("s1", 0)
        val pending = insertPending(tenantA, "s1")
        val token = UUID.randomUUID()
        jdbc
            .sql(claimedLease)
            .param("token", token)
            .param("id", pending)
            .update()

        assertThat(erase("s1").status).isEqualTo(204)

        // JobQueue.complete() of the matcher, verbatim guarded terminal write with MATCH values.
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE verification
                       SET status = :status, score = :score, threshold = :threshold, reason = :reason,
                           probe = NULL, lease_token = NULL, lease_until = NULL, decided_at = now()
                     WHERE id = :id AND status = 'PENDING' AND lease_token = :token
                    """.trimIndent(),
                ).param("status", "MATCH")
                .param("score", 0.99)
                .param("threshold", 0.6)
                .param("reason", null)
                .param("id", pending)
                .param("token", token)
                .update()

        assertThat(updated).isZero()
        val r = row(pending)
        assertThat(r["status"]).isEqualTo("FAILED")
        assertThat(r["reason"]).isEqualTo("SUBJECT_ERASED")
        assertThat(r["score"]).isNull()
        assertThat(r["probe"]).isNull()
        val read = mapper.readTree(get(pending).body)
        assertThat(read["status"].asString()).isEqualTo("FAILED")
        assertThat(read["reason"].asString()).isEqualTo("SUBJECT_ERASED")
    }

    @Test
    fun `AC-49 an unclaimed pending verification is never claimable after erasure`() {
        enrol("s1", 0)
        val pending = insertPending(tenantA, "s1")

        assertThat(erase("s1").status).isEqualTo(204)

        // The WHERE filter of JobQueue.claim().
        val claimable =
            jdbc
                .sql(
                    "select id from verification where status = 'PENDING' and attempts < 3 and (lease_until is null or lease_until < now())",
                ).query(UUID::class.java)
                .list()
        assertThat(claimable).doesNotContain(pending)
        assertThat(row(pending)["status"]).isEqualTo("FAILED")
    }

    // ---------------------------------------------------------------- AC-50

    @Test
    fun `AC-50 re-enrolling an erased subject gives 201 with version 1`() {
        assertThat(enrol("s1", 0).status).isEqualTo(201)
        assertThat(enrol("s1", 1).status).isEqualTo(201)
        assertThat(erase("s1").status).isEqualTo(204)

        val again = enrol("s1", 2)

        assertThat(again.status).isEqualTo(201)
        assertThat(mapper.readTree(again.body)["version"].asInt()).isEqualTo(1)
        assertThat(versions(tenantA, "s1")).isEqualTo(1)
    }

    // ---------------------------------------------------------------- AC-37

    @Test
    @Tag("security")
    fun `API1 AC-37 BOLA - tenant B cannot erase tenant A's subject and gets the same 404 as unknown`() {
        enrol("s1", 0)
        enrol("s1", 1)
        val pending = insertPending(tenantA, "s1")
        val decided = insertDecided(tenantA, "s1", "MATCH")
        insertIdempotency(tenantA, "k1", pending)
        insertIdempotency(tenantA, "k2", decided)
        val before = snapshot()

        val foreign = erase("s1", both(tenantB))
        val unknown = erase("never-existed", both(tenantB))

        assertNotFoundProblem(foreign)
        assertThat(foreign.body).isEqualTo(unknown.body)
        assertThat(foreign.contentType).isEqualTo(unknown.contentType)
        assertThat(foreign.body).doesNotContain("s1").doesNotContain(tenantA)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(versions(tenantA, "s1")).isEqualTo(2)
        assertThat(idempotencyKeys(tenantA)).containsExactlyInAnyOrder("k1", "k2")
    }

    // ---------------------------------------------------------------- AC-7 / authentication

    @Test
    @Tag("security")
    fun `API5 AC-7 verify-only and enroll-only tokens get 403 on delete and nothing changes`() {
        enrol("s1", 0)
        val pending = insertPending(tenantA, "s1")
        insertIdempotency(tenantA, "k1", pending)
        val before = snapshot()

        listOf("verify", "enroll", "enroll verify").forEach { scope ->
            val r = erase("s1", JwtTestTokens.valid(scope = scope))
            assertThat(r.status).describedAs("scope $scope").isEqualTo(403)
            assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
            assertThat(mapper.readTree(r.body)["status"].asInt()).isEqualTo(403)
        }
        assertThat(erase("s1", JwtTestTokens.missingScope()).status).isEqualTo(403)

        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    @Tag("security")
    fun `API2 delete without token gives 401 with WWW-Authenticate and nothing changes`() {
        enrol("s1", 0)
        insertPending(tenantA, "s1")
        val before = snapshot()

        val r = erase("s1", token = null)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    @Tag("security")
    fun `API2 delete with any invalid token gives 401 and nothing changes`() {
        enrol("s1", 0)
        insertPending(tenantA, "s1")
        val before = snapshot()
        val tokens =
            mapOf(
                "malformed" to "not.a.jwt",
                "expired" to JwtTestTokens.expired(),
                "not yet valid" to JwtTestTokens.notYetValid(),
                "wrong key" to JwtTestTokens.wrongKey(),
                "alg none" to JwtTestTokens.algNone(),
                "wrong iss" to JwtTestTokens.wrongIssuer(),
                "wrong aud" to JwtTestTokens.wrongAudience(),
                "missing tenant" to JwtTestTokens.missingTenant(),
            )

        tokens.forEach { (name, token) ->
            val r = erase("s1", token)
            assertThat(r.status).describedAs(name).isEqualTo(401)
            assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(name).startsWith("Bearer")
        }

        assertThat(snapshot()).isEqualTo(before)
    }
}
