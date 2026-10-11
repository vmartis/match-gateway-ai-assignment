package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import com.vmartis.matchgateway.gateway.security.TestHmacKey
import com.vmartis.matchgateway.gateway.subject.TestTemplates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Step 9: AC-18..AC-23, ADR-008, AM-11, AM-12 through the real filter chain and a real Postgres. */
class IdempotencyIT : GatewayIntegrationTest() {
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
    ) {
        val id: String? get() = Regex("\"verificationId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
    }

    private fun verifier(tenant: String = "tenant-a") = JwtTestTokens.valid(tenant = tenant, scope = "enroll verify admin")

    private fun json(
        subjectId: String = "subj-001",
        template: String = TestTemplates.unit(),
    ) = """{"subjectId":"$subjectId","probeTemplate":"$template"}"""

    private fun submit(
        body: String = json(),
        key: String? = "k-1",
        tenant: String = "tenant-a",
    ): Response {
        val r =
            client
                .post()
                .uri(URI.create("http://localhost:$port/v1/verifications"))
                .headers { h ->
                    h.setBearerAuth(verifier(tenant))
                    key?.let { h.set("Idempotency-Key", it) }
                    h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }.body(body)
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun enrol(subject: String) {
        val r =
            client
                .method(HttpMethod.POST)
                .uri(URI.create("http://localhost:$port/v1/subjects/$subject/enrollment"))
                .headers {
                    it.setBearerAuth(verifier())
                    it.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                }.body("""{"template":"${TestTemplates.unit(9)}"}""")
                .exchange()
                .returnResult(String::class.java)
        assertThat(r.status.value()).isEqualTo(201)
    }

    private fun erase(subject: String): Int =
        client
            .method(HttpMethod.DELETE)
            .uri(URI.create("http://localhost:$port/v1/subjects/$subject"))
            .headers { it.setBearerAuth(verifier()) }
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun count(table: String): Int = jdbc.sql("select count(*) from $table").query(Int::class.java).single()

    // bytea columns become lists so that rows compare by value (ByteArray equality is identity).
    private fun idempotencyRows(): List<Map<String, Any?>> =
        jdbc
            .sql("select * from idempotency_key order by tenant_id, idem_key")
            .query()
            .listOfRows()
            .map { row -> row.mapValues { (_, v) -> if (v is ByteArray) v.toList() else v } }

    private fun verificationRow(): Map<String, Any?> =
        jdbc
            .sql("select * from verification")
            .query()
            .singleRow()
            .mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.fingerprintBytes(): ByteArray = (this as List<Byte>).toByteArray()

    private fun expectedFingerprint(
        tenant: String,
        subject: String,
        probe: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TestHmacKey.bytes, "HmacSHA256"))
        mac.update(tenant.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(subject.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(probe)
        return mac.doFinal()
    }

    private fun decide(id: String) {
        jdbc
            .sql(
                "update verification set status = 'MATCH', score = 0.91, threshold = 0.5, decided_at = now(), " +
                    "probe = null, lease_token = null, lease_until = null where id = :id",
            ).param("id", UUID.fromString(id))
            .update()
    }

    private fun assertReuseProblem(r: Response) {
        assertThat(r.status).isEqualTo(422)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node["type"].asString()).isEqualTo("urn:matchgw:problem:idempotency-key-reused")
        assertThat(node["status"].asInt()).isEqualTo(422)
        assertThat(node.has("instance")).isFalse()
        assertThat(node.has("verificationId")).isFalse()
        assertThat(r.body)
            .doesNotContain("Exception")
            .doesNotContain("at com.")
            .doesNotContain("trace")
            .doesNotContain("k-1")
            .doesNotContain("subj-")
        assertThat(r.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    // ---------------------------------------------------------------- first use + AC-23

    @Test
    fun `AC-18 first use of a key is 202 PENDING with Location and creates one verification and one idempotency row`() {
        val r = submit()

        assertThat(r.status).isEqualTo(202)
        val node = mapper.readTree(r.body)
        assertThat(node["status"].asString()).isEqualTo("PENDING")
        assertThat(r.headers.getFirst(HttpHeaders.LOCATION)).isEqualTo("/v1/verifications/${r.id}")
        assertThat(count("verification")).isEqualTo(1)
        val rows = idempotencyRows()
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["tenant_id"]).isEqualTo("tenant-a")
        assertThat(rows[0]["idem_key"]).isEqualTo("k-1")
        assertThat(rows[0]["verification_id"]).isEqualTo(UUID.fromString(r.id))
    }

    @Test
    @Tag("security")
    fun `A04 AC-23 stored fingerprint is the keyed HMAC and never the probe, a slice of it or SHA-256 of the body`() {
        val probe = TestTemplates.unitBytes(4)
        val body = json("subj-hmac", TestTemplates.base64(probe))

        submit(body)

        val fp = idempotencyRows().single()["fingerprint"].fingerprintBytes()
        assertThat(fp).hasSize(32)
        assertThat(fp).isEqualTo(expectedFingerprint("tenant-a", "subj-hmac", probe))
        assertThat(fp).isNotEqualTo(probe.copyOf(32))
        assertThat(fp).isNotEqualTo(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        assertThat(fp).isNotEqualTo(MessageDigest.getInstance("SHA-256").digest(probe))
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        // No 16-byte window of the probe appears inside the fingerprint (nor the other way round for the stored value).
        for (i in 0..probe.size - 16) {
            assertThat(hex(fp)).doesNotContain(hex(probe.copyOfRange(i, i + 16)))
        }
        assertThat(
            jdbc
                .sql("select count(*) from idempotency_key where fingerprint = :p")
                .param("p", probe)
                .query(Int::class.java)
                .single(),
        ).isZero()
    }

    // ---------------------------------------------------------------- AC-18 replay

    @Test
    fun `AC-18 replay with the same key subject and probe returns 202 with the same id while PENDING and no second row`() {
        val first = submit()

        val replay = submit()

        assertThat(replay.status).isEqualTo(202)
        assertThat(replay.id).isEqualTo(first.id)
        assertThat(mapper.readTree(replay.body)["status"].asString()).isEqualTo("PENDING")
        assertThat(replay.headers.getFirst(HttpHeaders.LOCATION)).isEqualTo("/v1/verifications/${first.id}")
        assertThat(
            mapper
                .readTree(replay.body)
                .propertyNames()
                .asSequence()
                .toSet(),
        ).isEqualTo(setOf("verificationId", "status"))
        assertThat(count("verification")).isEqualTo(1)
        assertThat(count("idempotency_key")).isEqualTo(1)
    }

    @Test
    fun `AC-18 AM-12 replay after the row is decided is 200 with the same id and the current status`() {
        val first = submit()
        decide(checkNotNull(first.id))

        val replay = submit()

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.id).isEqualTo(first.id)
        assertThat(mapper.readTree(replay.body)["status"].asString()).isEqualTo("MATCH")
        assertThat(replay.headers.getFirst(HttpHeaders.LOCATION)).isEqualTo("/v1/verifications/${first.id}")
        assertThat(replay.body).doesNotContain("score").doesNotContain("0.91")
        assertThat(count("verification")).isEqualTo(1)
        assertThat(count("idempotency_key")).isEqualTo(1)
    }

    @Test
    fun `AC-18 a replay never modifies the decided verification`() {
        val first = submit()
        decide(checkNotNull(first.id))
        val before = verificationRow()

        submit()

        assertThat(verificationRow()).isEqualTo(before)
    }

    // ---------------------------------------------------------------- AC-19

    @Test
    @Tag("security")
    fun `API4 AC-19 same key with a different subjectId is 422 and creates nothing`() {
        val first = submit()
        val idemBefore = idempotencyRows()
        val verificationBefore = verificationRow()

        val r = submit(json("subj-other"))

        assertReuseProblem(r)
        assertThat(count("verification")).isEqualTo(1)
        assertThat(idempotencyRows()).isEqualTo(idemBefore)
        assertThat(verificationRow()).isEqualTo(verificationBefore)
        assertThat(submit().id).isEqualTo(first.id)
    }

    @Test
    @Tag("security")
    fun `API4 AC-19 same key with a different probe is 422 and creates nothing and leaks no template`() {
        val other = TestTemplates.unit(7)
        submit()
        val idemBefore = idempotencyRows()

        val r = submit(json(template = other))

        assertReuseProblem(r)
        assertThat(r.body).doesNotContain(other).doesNotContain(TestTemplates.unit())
        assertThat(count("verification")).isEqualTo(1)
        assertThat(idempotencyRows()).isEqualTo(idemBefore)
    }

    @Test
    @Tag("security")
    fun `API4 AC-19 reuse with a different body after the verification was decided is still 422`() {
        decide(checkNotNull(submit().id))

        assertReuseProblem(submit(json(template = TestTemplates.unit(7))))
        assertThat(count("verification")).isEqualTo(1)
    }

    @Test
    fun `AC-18 the same body under a different key is a new verification`() {
        val first = submit(key = "k-1")

        val second = submit(key = "k-2")

        assertThat(second.status).isEqualTo(202)
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(count("verification")).isEqualTo(2)
        assertThat(count("idempotency_key")).isEqualTo(2)
    }

    // ---------------------------------------------------------------- AC-20

    @Test
    @Tag("security")
    fun `API1 AC-20 tenant B using the same key gets its own new id and tenant A's record is unchanged`() {
        val a = submit(tenant = "tenant-a")
        val aRowBefore = idempotencyRows().single()

        val b = submit(tenant = "tenant-b")

        assertThat(b.status).isEqualTo(202)
        assertThat(b.id).isNotEqualTo(a.id)
        assertThat(count("verification")).isEqualTo(2)
        val rows = idempotencyRows()
        assertThat(rows).hasSize(2)
        assertThat(rows.single { it["tenant_id"] == "tenant-a" }).isEqualTo(aRowBefore)
        assertThat(rows.single { it["tenant_id"] == "tenant-b" }["verification_id"]).isEqualTo(UUID.fromString(b.id))
        assertThat(
            rows.single { it["tenant_id"] == "tenant-b" }["fingerprint"].fingerprintBytes(),
        ).isNotEqualTo(aRowBefore["fingerprint"].fingerprintBytes())
        assertThat(submit(tenant = "tenant-a").id).isEqualTo(a.id)
        assertThat(submit(tenant = "tenant-b").id).isEqualTo(b.id)
    }

    // ---------------------------------------------------------------- AC-21

    private fun concurrently(requests: List<() -> Response>): List<Response> {
        val pool = Executors.newFixedThreadPool(requests.size)
        try {
            val ready = CountDownLatch(requests.size)
            val start = CountDownLatch(1)
            val futures =
                requests.map { request ->
                    pool.submit<Response> {
                        ready.countDown()
                        check(start.await(30, TimeUnit.SECONDS))
                        request()
                    }
                }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `AC-21 ten concurrent identical requests share one id and create exactly one verification and one idempotency row`() {
        val responses = concurrently(List(10) { { submit(key = "k-par") } })

        assertThat(responses.map { it.status }).containsOnly(202)
        assertThat(responses.map { it.id }.toSet()).hasSize(1)
        assertThat(responses.map { it.id }.distinct().single()).isNotNull()
        assertThat(count("verification")).isEqualTo(1)
        assertThat(count("idempotency_key")).isEqualTo(1)
        assertThat(idempotencyRows().single()["verification_id"]).isEqualTo(UUID.fromString(responses.first().id))
    }

    @Test
    @Tag("security")
    fun `API4 AC-21 ten concurrent requests with the same key and different probes yield one winner and nine 422`() {
        val responses = concurrently(List(10) { i -> { submit(json(template = TestTemplates.unit(i)), key = "k-race") } })

        val winners = responses.filter { it.status == 202 }
        assertThat(winners).hasSize(1)
        responses.filter { it.status != 202 }.forEach { assertReuseProblem(it) }
        assertThat(responses.count { it.status == 422 }).isEqualTo(9)
        assertThat(count("verification")).isEqualTo(1)
        assertThat(count("idempotency_key")).isEqualTo(1)
        assertThat(idempotencyRows().single()["verification_id"]).isEqualTo(UUID.fromString(winners.single().id))
    }

    // ---------------------------------------------------------------- AC-22

    @Test
    fun `AC-22 AM-11 a key first used 25 hours ago is treated as new and the idempotency row points to the new verification`() {
        val old = submit()
        jdbc.sql("update idempotency_key set created_at = now() - interval '25 hours'").update()

        val fresh = submit()

        assertThat(fresh.status).isEqualTo(202)
        assertThat(fresh.id).isNotEqualTo(old.id)
        assertThat(count("verification")).isEqualTo(2)
        assertThat(
            jdbc
                .sql(
                    "select count(*) from verification where id = :id",
                ).param("id", UUID.fromString(old.id))
                .query(Int::class.java)
                .single(),
        ).isEqualTo(1)
        val row = idempotencyRows().single()
        assertThat(row["verification_id"]).isEqualTo(UUID.fromString(fresh.id))
        assertThat(
            jdbc
                .sql("select created_at > now() - interval '1 hour' from idempotency_key")
                .query(Boolean::class.java)
                .single(),
        ).isTrue()
        assertThat(submit().id).isEqualTo(fresh.id)
    }

    @Test
    fun `AC-22 an expired key may be reused with a different body and then binds the new body`() {
        submit()
        jdbc.sql("update idempotency_key set created_at = now() - interval '25 hours'").update()
        val other = json("subj-other", TestTemplates.unit(5))

        val fresh = submit(other)

        assertThat(fresh.status).isEqualTo(202)
        assertThat(idempotencyRows().single()["fingerprint"].fingerprintBytes())
            .isEqualTo(expectedFingerprint("tenant-a", "subj-other", TestTemplates.unitBytes(5)))
        assertThat(submit(other).id).isEqualTo(fresh.id)
        assertReuseProblem(submit())
    }

    @Test
    fun `AC-22 a key first used 23 hours ago is still replayed`() {
        val first = submit()
        jdbc.sql("update idempotency_key set created_at = now() - interval '23 hours'").update()

        val replay = submit()

        assertThat(replay.id).isEqualTo(first.id)
        assertThat(count("verification")).isEqualTo(1)
    }

    // ---------------------------------------------------------------- erasure interaction

    @Test
    fun `AC-18 AC-47 replay after the subject was erased is treated as new`() {
        enrol("subj-001")
        val first = submit()
        assertThat(erase("subj-001")).isEqualTo(204)
        assertThat(count("idempotency_key")).isZero()

        val replay = submit()

        assertThat(replay.status).isEqualTo(202)
        assertThat(replay.id).isNotEqualTo(first.id)
        assertThat(idempotencyRows().single()["verification_id"]).isEqualTo(UUID.fromString(replay.id))
    }

    // ---------------------------------------------------------------- validation precedes idempotency

    @Test
    @Tag("security")
    fun `API4 an invalid body with a reused key is 400 not 422 and leaves the record unchanged`() {
        submit()
        val idemBefore = idempotencyRows()

        val bad = submit("""{"subjectId":"subj-001","probeTemplate":"AAAA"}""")
        val missing = submit("""{"subjectId":"subj-001"}""")

        assertThat(bad.status).isEqualTo(400)
        assertThat(mapper.readTree(bad.body)["type"].asString()).isEqualTo("urn:matchgw:problem:invalid-request")
        assertThat(missing.status).isEqualTo(400)
        assertThat(count("verification")).isEqualTo(1)
        assertThat(idempotencyRows()).isEqualTo(idemBefore)
    }

    @Test
    @Tag("security")
    fun `API4 missing and invalid Idempotency-Key are still 400 and create no idempotency row`() {
        val missing = submit(key = null)
        val empty = submit(key = "")
        val long = submit(key = "k".repeat(65))

        assertThat(listOf(missing.status, empty.status, long.status)).containsOnly(400)
        assertThat(count("verification")).isZero()
        assertThat(count("idempotency_key")).isZero()
    }
}
