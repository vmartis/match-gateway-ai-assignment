package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import com.vmartis.matchgateway.gateway.subject.TestTemplates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID
import java.util.stream.Stream

/** Step 4: AC-14..AC-17, AC-30..AC-33 through the real filter chain and a real Postgres. */
class VerificationIT : GatewayIntegrationTest() {
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

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
    }

    private fun submit(
        body: String,
        token: String? = JwtTestTokens.valid(scope = "verify"),
        idempotencyKey: String? = "key-1",
        contentType: String = MediaType.APPLICATION_JSON_VALUE,
        accept: String? = null,
    ): Response {
        val r =
            client
                .post()
                .uri(URI.create("http://localhost:$port/v1/verifications"))
                .headers { h ->
                    token?.let { h.setBearerAuth(it) }
                    idempotencyKey?.let { h.set("Idempotency-Key", it) }
                    h.set(HttpHeaders.CONTENT_TYPE, contentType)
                    accept?.let { h.set(HttpHeaders.ACCEPT, it) }
                }.body(body)
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    /** [rawSegment] is placed into the path verbatim. */
    private fun read(
        rawSegment: String,
        token: String? = JwtTestTokens.valid(scope = "verify"),
    ): Response {
        val r =
            client
                .get()
                .uri(URI.create("http://localhost:$port/v1/verifications/$rawSegment"))
                .headers { h -> token?.let { h.setBearerAuth(it) } }
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun json(
        subjectId: String = "subj-001",
        template: String = TestTemplates.unit(),
    ) = """{"subjectId":"$subjectId","probeTemplate":"$template"}"""

    @Test
    @Tag("security")
    @ExtendWith(OutputCaptureExtension::class)
    fun `AC-15 A10 a non-ASCII UTF-8 Idempotency-Key is a 400, not a 500, and logs no error`(output: CapturedOutput) {
        // StrictHttpFirewall checks header values lazily, inside the handler (security review step 9, LOW-1).
        val status = rawSubmit("Idempotency-Key: k-ключ".toByteArray(Charsets.UTF_8))

        assertThat(status).startsWith("HTTP/1.1 400")
        assertThat(totalRows()).isZero()
        assertThat(output.all).doesNotContain("ERROR").doesNotContain("RequestRejectedException")
    }

    /** Raw-socket submit: [keyHeaders] are sent verbatim (the HTTP client would normalise or merge them). */
    private fun rawSubmit(keyHeaders: ByteArray): String {
        val body = json().toByteArray(Charsets.UTF_8)
        val head =
            "POST /v1/verifications HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${JwtTestTokens.valid(scope = "verify")}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n"
        return java.net.Socket("localhost", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(keyHeaders)
                write("\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
            socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
        }
    }

    private fun totalRows(): Int = jdbc.sql("select count(*) from verification").query(Int::class.java).single()

    private fun keys(r: Response): Set<String> =
        mapper
            .readTree(r.body)
            .propertyNames()
            .asSequence()
            .toSet()

    private fun assertProblem(
        r: Response,
        status: Int,
        type: String,
        vararg forbidden: String,
    ) {
        assertThat(r.status).isEqualTo(status)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node["type"].asString()).isEqualTo(type)
        assertThat(node["status"].asInt()).isEqualTo(status)
        assertThat(node.has("instance")).isFalse()
        assertThat(r.body)
            .doesNotContain("Exception")
            .doesNotContain("at com.")
            .doesNotContain("trace")
            .doesNotContain("instance")
            .doesNotContain("path")
        forbidden.filter { it.isNotEmpty() }.forEach { assertThat(r.body).doesNotContain(it) }
    }

    private fun assertInvalidRequest(
        r: Response,
        vararg forbidden: String,
    ) = assertProblem(r, 400, "urn:matchgw:problem:invalid-request", *forbidden)

    private fun insertPending(
        id: UUID,
        tenant: String = "tenant-a",
        subject: String = "subj-001",
    ) {
        jdbc
            .sql("insert into verification(id, tenant_id, subject_id, status, probe) values (:id, :t, :s, 'PENDING', :p)")
            .param("id", id)
            .param("t", tenant)
            .param("s", subject)
            .param("p", TestTemplates.unitBytes())
            .update()
    }

    private fun insertDecided(
        id: UUID,
        status: String,
        tenant: String = "tenant-a",
        subject: String = "subj-001",
    ) {
        jdbc
            .sql(
                "insert into verification(id, tenant_id, subject_id, status, score, threshold, decided_at) " +
                    "values (:id, :t, :s, :st, 0.87, 0.5, timestamptz '2026-10-11T08:00:00Z')",
            ).param("id", id)
            .param("t", tenant)
            .param("s", subject)
            .param("st", status)
            .update()
    }

    private fun insertFailed(
        id: UUID,
        reason: String = "SUBJECT_NOT_ENROLLED",
    ) {
        jdbc
            .sql(
                "insert into verification(id, tenant_id, subject_id, status, reason, decided_at) " +
                    "values (:id, 'tenant-a', 'subj-001', 'FAILED', :r, timestamptz '2026-10-11T08:00:00Z')",
            ).param("id", id)
            .param("r", reason)
            .update()
    }

    // ---------------------------------------------------------------- AC-14 submit

    @Test
    fun `AC-14 valid submission is 202 with exactly verificationId and PENDING and a relative Location`() {
        val r = submit(json())

        assertThat(r.status).isEqualTo(202)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        assertThat(keys(r)).isEqualTo(setOf("verificationId", "status"))
        val node = mapper.readTree(r.body)
        assertThat(node["status"].asString()).isEqualTo("PENDING")
        val id = UUID.fromString(node["verificationId"].asString())
        assertThat(id.version()).isEqualTo(4)
        assertThat(r.headers.getFirst(HttpHeaders.LOCATION)).isEqualTo("/v1/verifications/$id")
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    @Test
    fun `AC-14 submission persists a PENDING row with tenant subject and the decoded probe`() {
        val probe = TestTemplates.unitBytes(3)
        val r = submit(json("subj.x_1-A", TestTemplates.base64(probe)), JwtTestTokens.valid(tenant = "tenant-z", scope = "verify"))

        assertThat(r.status).isEqualTo(202)
        val id = UUID.fromString(mapper.readTree(r.body)["verificationId"].asString())
        val row =
            jdbc
                .sql("select * from verification where id = :id")
                .param("id", id)
                .query()
                .singleRow()
        assertThat(row["tenant_id"]).isEqualTo("tenant-z")
        assertThat(row["subject_id"]).isEqualTo("subj.x_1-A")
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat(row["probe"] as ByteArray).isEqualTo(probe)
        assertThat(row["attempts"]).isEqualTo(0)
        assertThat(row["decided_at"]).isNull()
        assertThat(totalRows()).isEqualTo(1)
    }

    @Test
    fun `AC-17 an unknown subject is still accepted with 202 and a PENDING row`() {
        val r = submit(json("never-enrolled"))

        assertThat(r.status).isEqualTo(202)
        assertThat(jdbc.sql("select status from verification where subject_id = 'never-enrolled'").query(String::class.java).single())
            .isEqualTo("PENDING")
    }

    @Test
    fun `AC-16 subject id of 64 characters is accepted`() {
        assertThat(submit(json("a".repeat(64))).status).isEqualTo(202)
    }

    // ---------------------------------------------------------------- Idempotency-Key header validation

    @Test
    fun `AC-15 Idempotency-Key of 64 printable ASCII characters is accepted`() {
        assertThat(submit(json(), idempotencyKey = "~!".repeat(32)).status).isEqualTo(202)
    }

    @Test
    fun `AC-15 Idempotency-Key of 1 character is accepted`() {
        assertThat(submit(json(), idempotencyKey = "k").status).isEqualTo(202)
    }

    @Test
    @Tag("security")
    fun `API4 missing Idempotency-Key is a constant 400 and creates no row`() {
        val r = submit(json(), idempotencyKey = null)

        assertInvalidRequest(r, "subj-001", TestTemplates.unit())
        assertThat(totalRows()).isZero()
    }

    @ParameterizedTest(name = "AC-15 API4 Idempotency-Key [{0}] is 400 and creates no row")
    @MethodSource("badKeys")
    @Tag("security")
    fun `API4 invalid Idempotency-Key is a constant 400 and creates no row`(key: String) {
        val r = submit(json(), idempotencyKey = key)

        assertInvalidRequest(r, "subj-001", TestTemplates.unit(), key)
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- invalid body

    @ParameterizedTest(name = "AC-16 API4 invalid subjectId {0} is 400 and creates no row")
    @MethodSource("invalidSubjectBodies")
    @Tag("security")
    fun `API4 invalid subjectId is a constant 400 and creates no row`(
        name: String,
        body: String,
        submitted: String,
    ) {
        val r = submit(body)

        assertInvalidRequest(r, submitted, TestTemplates.unit())
        assertThat(totalRows()).isZero()
    }

    @ParameterizedTest(name = "AC-16 API4 invalid probeTemplate {0} is 400 and creates no row")
    @MethodSource("invalidProbeBodies")
    @Tag("security")
    fun `API4 invalid probeTemplate is a constant 400 and creates no row`(
        name: String,
        body: String,
        submitted: String,
    ) {
        val r = submit(body)

        assertInvalidRequest(r, "subj-001", submitted)
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API4 malformed JSON is a 400 problem and creates no row`() {
        val r = submit("""{"subjectId":""")

        assertInvalidRequest(r, "subjectId")
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 text plain content type is 415 and creates no row`() {
        val r = submit(json(), contentType = MediaType.TEXT_PLAIN_VALUE)

        assertProblem(r, 415, "urn:matchgw:problem:unsupported-media-type", "subj-001", TestTemplates.unit())
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 Accept text html is 406 and creates no row`() {
        val r = submit(json(), accept = "text/html")

        assertThat(r.status).isEqualTo(406)
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API4 body larger than 8 KB is 413 and creates no row`() {
        val r = submit(json(template = "A".repeat(9000)))

        assertProblem(r, 413, "urn:matchgw:problem:payload-too-large", "subj-001", "A".repeat(100))
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- submit authn / authz

    @Test
    @Tag("security")
    fun `API5 submit with the enroll scope only is 403 and creates no row`() {
        val r = submit(json(), JwtTestTokens.valid(scope = "enroll"))

        assertThat(r.status).isEqualTo(403)
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API2 submit without a token is 401 with WWW-Authenticate and creates no row`() {
        val r = submit(json(), token = null)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API2 submit with an expired token is 401 and creates no row`() {
        assertThat(submit(json(), JwtTestTokens.expired()).status).isEqualTo(401)
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- AC-30..AC-33 read

    @Test
    fun `AC-31 own PENDING verification is 200 with exactly six keys and no reason`() {
        val id = UUID.randomUUID()
        insertPending(id)

        val r = read(id.toString())

        assertThat(r.status).isEqualTo(200)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        assertThat(keys(r)).isEqualTo(setOf("verificationId", "subjectId", "status", "score", "threshold", "decidedAt"))
        val node = mapper.readTree(r.body)
        assertThat(node["verificationId"].asString()).isEqualTo(id.toString())
        assertThat(node["subjectId"].asString()).isEqualTo("subj-001")
        assertThat(node["status"].asString()).isEqualTo("PENDING")
        assertThat(node["score"].isNull).isTrue()
        assertThat(node["threshold"].isNull).isTrue()
        assertThat(node["decidedAt"].isNull).isTrue()
    }

    @ParameterizedTest(name = "AC-30 own {0} verification is 200 with score threshold and decidedAt")
    @ValueSource(strings = ["MATCH", "NO_MATCH"])
    fun `AC-30 decided verification shows numeric score threshold and ISO decidedAt without reason`(status: String) {
        val id = UUID.randomUUID()
        insertDecided(id, status)

        val r = read(id.toString())

        assertThat(r.status).isEqualTo(200)
        assertThat(keys(r)).isEqualTo(setOf("verificationId", "subjectId", "status", "score", "threshold", "decidedAt"))
        val node = mapper.readTree(r.body)
        assertThat(node["status"].asString()).isEqualTo(status)
        assertThat(node["score"].asDouble()).isEqualTo(0.87)
        assertThat(node["threshold"].asDouble()).isEqualTo(0.5)
        assertThat(node["decidedAt"].asString()).isEqualTo("2026-10-11T08:00:00Z")
        assertThat(r.body).doesNotContain("reason")
    }

    @ParameterizedTest(name = "AC-32 own FAILED verification with reason {0} carries the reason and has seven keys")
    @ValueSource(strings = ["SUBJECT_NOT_ENROLLED", "SUBJECT_ERASED", "MAX_ATTEMPTS"])
    fun `AC-32 own FAILED verification carries the reason and has seven keys`(reason: String) {
        val id = UUID.randomUUID()
        insertFailed(id, reason)

        val r = read(id.toString())

        assertThat(r.status).isEqualTo(200)
        assertThat(keys(r))
            .isEqualTo(setOf("verificationId", "subjectId", "status", "score", "threshold", "decidedAt", "reason"))
        val node = mapper.readTree(r.body)
        assertThat(node["status"].asString()).isEqualTo("FAILED")
        assertThat(node["reason"].asString()).isEqualTo(reason)
        assertThat(node["score"].isNull).isTrue()
        assertThat(node["threshold"].isNull).isTrue()
        assertThat(node["decidedAt"].asString()).isEqualTo("2026-10-11T08:00:00Z")
    }

    @Test
    fun `AC-14 AC-30 a submitted verification can be read back via its Location`() {
        val submitted = submit(json("round-trip"))
        val location = checkNotNull(submitted.headers.getFirst(HttpHeaders.LOCATION))

        val r = read(location.removePrefix("/v1/verifications/"))

        assertThat(r.status).isEqualTo(200)
        val node = mapper.readTree(r.body)
        assertThat(node["subjectId"].asString()).isEqualTo("round-trip")
        assertThat(node["status"].asString()).isEqualTo("PENDING")
    }

    @Test
    @Tag("security")
    fun `AC-33 API1 BOLA - another tenant's, unknown and malformed ids give byte identical 404 problems`() {
        val foreign = UUID.randomUUID()
        insertPending(foreign, tenant = "tenant-b")
        val unknown = UUID.randomUUID()

        val rForeign = read(foreign.toString())
        val rUnknown = read(unknown.toString())
        val rMalformed = read("not-a-uuid")

        assertProblem(rForeign, 404, "urn:matchgw:problem:not-found", foreign.toString())
        assertProblem(rUnknown, 404, "urn:matchgw:problem:not-found", unknown.toString())
        assertProblem(rMalformed, 404, "urn:matchgw:problem:not-found", "not-a-uuid")
        assertThat(rUnknown.body).isEqualTo(rForeign.body)
        assertThat(rMalformed.body).isEqualTo(rForeign.body)
    }

    @Test
    @Tag("security")
    fun `API1 BOLA - the owning tenant reads it while the other tenant gets 404`() {
        val id = UUID.randomUUID()
        insertPending(id, tenant = "tenant-b")

        assertThat(read(id.toString(), JwtTestTokens.valid(tenant = "tenant-b", scope = "verify")).status).isEqualTo(200)
        assertThat(read(id.toString(), JwtTestTokens.valid(tenant = "tenant-a", scope = "verify")).status).isEqualTo(404)
    }

    @Test
    @Tag("security")
    fun `API5 read with the enroll scope only is 403`() {
        val id = UUID.randomUUID()
        insertPending(id)

        assertThat(read(id.toString(), JwtTestTokens.valid(scope = "enroll")).status).isEqualTo(403)
    }

    @Test
    @Tag("security")
    fun `API2 read without a token is 401 with WWW-Authenticate`() {
        val r = read(UUID.randomUUID().toString(), token = null)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
    }

    @Test
    @Tag("security")
    fun `API2 read with a wrong key signature is 401`() {
        assertThat(read(UUID.randomUUID().toString(), JwtTestTokens.wrongKey()).status).isEqualTo(401)
    }

    // The HTTP client replaces non-ASCII header characters with '?' (a valid key), so the bytes go over a raw socket.
    @ParameterizedTest(name = "AC-15 API4 Idempotency-Key with non-ASCII bytes {0} is 400 and creates no row")
    @ValueSource(strings = ["latin1", "utf8"])
    @Tag("security")
    fun `API4 non-ASCII Idempotency-Key bytes are rejected`(encoding: String) {
        val key = if (encoding == "latin1") byteArrayOf(0x6B, 0xE9.toByte(), 0x79) else "kéy".toByteArray(Charsets.UTF_8)
        val statusLine = rawSubmit("Idempotency-Key: ".toByteArray(Charsets.ISO_8859_1) + key)
        assertThat(statusLine).startsWith("HTTP/1.1 400")
        assertThat(totalRows()).isZero()
    }

    @Test
    fun `AC-31 a claimed verification is still reported as PENDING without decision fields`() {
        val id = UUID.randomUUID()
        insertPending(id)
        jdbc
            .sql("update verification set attempts = 2, lease_token = :lt, lease_until = now() + interval '10 seconds' where id = :id")
            .param("lt", UUID.randomUUID())
            .param("id", id)
            .update()

        val r = read(id.toString())

        assertThat(r.status).isEqualTo(200)
        assertThat(keys(r)).isEqualTo(setOf("verificationId", "subjectId", "status", "score", "threshold", "decidedAt"))
        val node = mapper.readTree(r.body)
        assertThat(node["status"].asString()).isEqualTo("PENDING")
        assertThat(node["score"].isNull && node["threshold"].isNull && node["decidedAt"].isNull).isTrue()
    }

    @Test
    @Tag("security")
    fun `API3 mass assignment - extra body fields cannot set status, score, tenant or id`() {
        val forged = UUID.randomUUID()
        val body =
            """{"subjectId":"subj-001","probeTemplate":"${TestTemplates.unit()}","status":"MATCH","score":0.99,"threshold":0.1,""" +
                """"tenant_id":"tenant-b","tenantId":"tenant-b","id":"$forged","verificationId":"$forged","reason":"X","attempts":3}"""

        val r = submit(body)

        assertThat(r.status).isEqualTo(202)
        val id = mapper.readTree(r.body)["verificationId"].asString()
        assertThat(id).isNotEqualTo(forged.toString())
        val row =
            jdbc
                .sql("select tenant_id, status, score, threshold, reason, attempts from verification where id = :id")
                .param("id", UUID.fromString(id))
                .query {
                    rs,
                    _,
                    ->
                    listOf(rs.getString(1), rs.getString(2), rs.getObject(3), rs.getObject(4), rs.getObject(5), rs.getInt(6))
                }.single()
        assertThat(row).containsExactly("tenant-a", "PENDING", null, null, null, 0)
    }

    @ParameterizedTest(name = "AC-15 API4 repeated Idempotency-Key headers {0} are 400 and create no row")
    @ValueSource(strings = ["two values", "value and empty"])
    @Tag("security")
    fun `AC-15 API4 repeated Idempotency-Key headers are rejected`(variant: String) {
        val second = if (variant == "two values") "k2" else ""
        val statusLine = rawSubmit("Idempotency-Key: k1\r\nIdempotency-Key: $second".toByteArray(Charsets.ISO_8859_1))
        assertThat(statusLine).startsWith("HTTP/1.1 400")
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `AC-33 API1 non-canonical UUID aliases of an existing id are 404 while the canonical and upper-case forms resolve`() {
        val id = UUID.fromString("00000001-0001-0001-0001-000000000001")
        insertPending(id)

        assertThat(read(id.toString()).status).isEqualTo(200)
        assertThat(read(id.toString().uppercase()).status).isEqualTo(200)
        assertThat(read("1-1-1-1-1").status).isEqualTo(404)
    }

    @Test
    @Tag("security")
    fun `API3 responses never contain the probe or template bytes`() {
        val probe = TestTemplates.unit(5)
        val submitted = submit(json(template = probe))
        val id = mapper.readTree(submitted.body)["verificationId"].asString()

        val r = read(id)

        assertThat(submitted.body).doesNotContain(probe).doesNotContain("probe")
        assertThat(r.status).isEqualTo(200)
        assertThat(r.body).doesNotContain(probe).doesNotContain("probe").doesNotContain("template")
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    companion object {
        @JvmStatic
        fun badKeys(): Stream<Arguments> =
            Stream.of(
                Arguments.of(""),
                Arguments.of("k".repeat(65)),
                Arguments.of("has space"),
            )

        @JvmStatic
        fun invalidSubjectBodies(): Stream<Arguments> {
            val t = TestTemplates.unit()
            return Stream.of(
                Arguments.of("65 chars", """{"subjectId":"${"a".repeat(65)}","probeTemplate":"$t"}""", "a".repeat(65)),
                Arguments.of("with space", """{"subjectId":"a b","probeTemplate":"$t"}""", "a b"),
                Arguments.of("missing", """{"probeTemplate":"$t"}""", ""),
                Arguments.of("null", """{"subjectId":null,"probeTemplate":"$t"}""", ""),
                Arguments.of("number", """{"subjectId":12345,"probeTemplate":"$t"}""", "12345"),
                Arguments.of("float", """{"subjectId":1.5,"probeTemplate":"$t"}""", "1.5"),
                Arguments.of("boolean", """{"subjectId":true,"probeTemplate":"$t"}""", "true"),
                Arguments.of("duplicate key", """{"subjectId":"dup-a","subjectId":"dup-b","probeTemplate":"$t"}""", "dup-b"),
                Arguments.of("empty", """{"subjectId":"","probeTemplate":"$t"}""", ""),
            )
        }

        @JvmStatic
        fun invalidProbeBodies(): Stream<Arguments> {
            fun case(
                name: String,
                template: String,
            ) = Arguments.of(name, """{"subjectId":"subj-001","probeTemplate":"$template"}""", template)

            return Stream.of(
                Arguments.of("missing", """{"subjectId":"subj-001"}""", ""),
                Arguments.of("null", """{"subjectId":"subj-001","probeTemplate":null}""", ""),
                case("511 bytes", TestTemplates.ofSize(511)),
                case("NaN component", TestTemplates.withComponent(Float.NaN)),
                case("norm 1.02", TestTemplates.single(1.02f)),
                case(
                    "non-canonical url safe alphabet",
                    TestTemplates
                        .base64(
                            TestTemplates.unitBytes().also {
                                it[4] = 0xFB.toByte()
                                it[5] = 0xFF.toByte()
                            },
                        ).replace('+', '-')
                        .replace('/', '_'),
                ),
                case("missing padding", TestTemplates.unit().dropLast(1)),
            )
        }
    }
}
