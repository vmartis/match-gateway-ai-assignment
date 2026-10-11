package com.vmartis.matchgateway.gateway.subject

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/** Step 3: AC-7, AC-9..AC-13, AC-42, AC-43 through the real filter chain and a real Postgres. */
class EnrollmentIT : GatewayIntegrationTest() {
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

    private data class Row(
        val version: Int,
        val active: Boolean,
        val template: ByteArray,
    )

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from subject").update()
    }

    /** [rawSegment] is placed into the path verbatim (already percent-encoded where needed). */
    private fun enrol(
        rawSegment: String,
        body: String,
        token: String? = JwtTestTokens.valid(scope = "enroll"),
        contentType: String = MediaType.APPLICATION_JSON_VALUE,
        accept: String? = null,
    ): Response {
        val r =
            client
                .post()
                .uri(URI.create("http://localhost:$port/v1/subjects/$rawSegment/enrollment"))
                .headers { h ->
                    token?.let { h.setBearerAuth(it) }
                    h.set(HttpHeaders.CONTENT_TYPE, contentType)
                    accept?.let { h.set(HttpHeaders.ACCEPT, it) }
                }.body(body)
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun json(template: String) = """{"template":"$template"}"""

    private fun rows(
        subject: String,
        tenant: String = "tenant-a",
    ): List<Row> =
        jdbc
            .sql("select version, active, template from template_version where tenant_id = :t and subject_id = :s order by version")
            .param("t", tenant)
            .param("s", subject)
            .query()
            .listOfRows()
            .map { Row(it["version"] as Int, it["active"] as Boolean, it["template"] as ByteArray) }

    private fun totalRows(): Int = jdbc.sql("select count(*) from template_version").query(Int::class.java).single()

    private fun assertEnrolled(
        r: Response,
        subject: String,
        version: Int,
    ) {
        assertThat(r.status).isEqualTo(201)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node.propertyNames().asSequence().toSet()).isEqualTo(setOf("subjectId", "version"))
        assertThat(node["subjectId"].asString()).isEqualTo(subject)
        assertThat(node["version"].asInt()).isEqualTo(version)
    }

    private fun assertProblem(
        r: Response,
        status: Int,
        type: String,
        vararg submitted: String,
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
        submitted.filter { it.isNotEmpty() }.forEach { assertThat(r.body).doesNotContain(it) }
    }

    private fun assertInvalidRequest(
        r: Response,
        vararg submitted: String,
    ) = assertProblem(r, 400, "urn:matchgw:problem:invalid-request", *submitted)

    // ---------------------------------------------------------------- AC-9 / AC-10

    @Test
    fun `AC-9 first enrolment returns 201 with subjectId and version 1 and stores the decoded bytes`() {
        val r = enrol("subj-001", json(TestTemplates.unit(3)))

        assertEnrolled(r, "subj-001", 1)
        val stored = rows("subj-001")
        assertThat(stored).hasSize(1)
        assertThat(stored[0].version).isEqualTo(1)
        assertThat(stored[0].active).isTrue()
        assertThat(stored[0].template).isEqualTo(TestTemplates.unitBytes(3))
        assertThat(
            jdbc
                .sql(
                    "select last_version from subject where tenant_id = 'tenant-a' and subject_id = 'subj-001'",
                ).query(Int::class.java)
                .single(),
        ).isEqualTo(1)
    }

    @Test
    fun `AC-9 the 201 response is not cacheable and carries nosniff`() {
        val r = enrol("subj-hdr", json(TestTemplates.unit()))

        assertThat(r.status).isEqualTo(201)
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
        assertThat(r.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
    }

    @Test
    fun `AC-10 second enrolment returns version 2 and keeps version 1 inactive`() {
        enrol("subj-001", json(TestTemplates.unit(0)))

        val r = enrol("subj-001", json(TestTemplates.unit(1)))

        assertEnrolled(r, "subj-001", 2)
        val stored = rows("subj-001")
        assertThat(stored.map { it.version }).containsExactly(1, 2)
        assertThat(stored.map { it.active }).containsExactly(false, true)
        assertThat(stored[0].template).isEqualTo(TestTemplates.unitBytes(0))
        assertThat(stored[1].template).isEqualTo(TestTemplates.unitBytes(1))
    }

    @Test
    fun `AC-10 a third enrolment returns version 3 with exactly one active row`() {
        repeat(2) { enrol("subj-001", json(TestTemplates.unit(it))) }

        val r = enrol("subj-001", json(TestTemplates.unit(2)))

        assertEnrolled(r, "subj-001", 3)
        assertThat(rows("subj-001").filter { it.active }.map { it.version }).containsExactly(3)
    }

    @Test
    @Tag("security")
    fun `API1 tenants are isolated - the same subjectId enrolled by two tenants yields independent version 1 rows`() {
        val a = enrol("shared", json(TestTemplates.unit(0)), JwtTestTokens.valid(tenant = "tenant-a", scope = "enroll"))
        val b = enrol("shared", json(TestTemplates.unit(1)), JwtTestTokens.valid(tenant = "tenant-b", scope = "enroll"))

        assertEnrolled(a, "shared", 1)
        assertEnrolled(b, "shared", 1)
        assertThat(rows("shared", "tenant-a").single().template).isEqualTo(TestTemplates.unitBytes(0))
        assertThat(rows("shared", "tenant-b").single().template).isEqualTo(TestTemplates.unitBytes(1))
    }

    // ---------------------------------------------------------------- AC-13

    @Test
    fun `AC-13 two concurrent enrolments of the same subject both return 201 with distinct versions and one active row`() {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (0..1).map { i ->
                    pool.submit<Response> {
                        ready.countDown()
                        check(go.await(10, TimeUnit.SECONDS))
                        enrol("subj-race", json(TestTemplates.unit(i)))
                    }
                }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            go.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(responses.map { it.status }).containsExactly(201, 201)
            val versions = responses.map { mapper.readTree(it.body)["version"].asInt() }
            assertThat(versions).containsExactlyInAnyOrder(1, 2)
            val stored = rows("subj-race")
            assertThat(stored.map { it.version }).containsExactly(1, 2)
            assertThat(stored.filter { it.active }.map { it.version }).containsExactly(2)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- AC-12

    @Test
    fun `AC-12 a 64 character subjectId is accepted`() {
        val id = "a".repeat(64)

        assertEnrolled(enrol(id, json(TestTemplates.unit())), id, 1)
        assertThat(rows(id)).hasSize(1)
    }

    @Test
    fun `AC-12 every allowed punctuation character is accepted`() {
        assertEnrolled(enrol("A.b_c-9", json(TestTemplates.unit())), "A.b_c-9", 1)
    }

    @Test
    fun `AC-12 a single character subjectId is accepted`() {
        assertEnrolled(enrol("x", json(TestTemplates.unit())), "x", 1)
    }

    @ParameterizedTest(name = "AC-12 A05 subjectId segment {0} is a 400 problem and creates nothing")
    @MethodSource("invalidSubjectIds")
    @Tag("security")
    fun `A05 invalid subjectId is rejected with a constant 400 problem`(segment: String) {
        val template = TestTemplates.unit()

        val r = enrol(segment, json(template))

        assertInvalidRequest(r, template, segment)
        assertThat(totalRows()).isZero()
        assertThat(jdbc.sql("select count(*) from subject").query(Int::class.java).single()).isZero()
    }

    @ParameterizedTest(name = "AC-12 A05 encoded slash subjectId segment {0} is the constant 400 problem")
    @MethodSource("encodedSlashSubjectIds")
    @Tag("security")
    fun `A05 encoded slash in subjectId is the constant 400 problem and creates nothing`(segment: String) {
        val template = TestTemplates.unit()

        val r = enrol(segment, json(template))

        assertInvalidRequest(r, template, segment)
        assertThat(totalRows()).isZero()
    }

    @ParameterizedTest(name = "AC-12 A05 firewall level subjectId segment {0} is a 400 without HTML or stack trace")
    @MethodSource("firewallSubjectIds")
    @Tag("security")
    fun `A05 encoded NUL in subjectId is rejected with 400 and no stack trace`(segment: String) {
        val r = enrol(segment, json(TestTemplates.unit()))

        assertThat(r.status).isEqualTo(400)
        // Tomcat rejects %00 before any webapp code; Boot's own hardened ErrorReportValve answers (include-stacktrace:
        // never → no report, no server info). The page may be HTML; it must not leak a trace, the input or the server.
        assertThat(r.body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("at org.")
        assertThat(r.body).doesNotContain("Apache Tomcat").doesNotContain("%00").doesNotContain("Invalid URI")
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 all 400 responses share one constant body independent of the offending input`() {
        val bodies =
            listOf(
                enrol("a%20b", json(TestTemplates.unit())),
                enrol("subj-1", """{"template":123}"""),
                enrol("subj-2", """{"template":"AAAA"}"""),
                enrol("subj-3", "{"),
            ).map { it.body }

        assertThat(bodies.toSet()).hasSize(1)
    }

    // ---------------------------------------------------------------- AC-11

    @ParameterizedTest(name = "AC-11 A05 {0} is a 400 problem and no version is created")
    @MethodSource("invalidTemplates")
    @Tag("security")
    fun `A05 invalid template is rejected with 400 and nothing is stored`(
        @Suppress("UNUSED_PARAMETER") name: String,
        body: String,
        submitted: String,
    ) {
        val r = enrol("subj-001", body)

        assertInvalidRequest(r, submitted, "subj-001")
        assertThat(totalRows()).isZero()
        assertThat(jdbc.sql("select count(*) from subject").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `AC-11 an invalid re-enrolment keeps the existing active version untouched`() {
        enrol("subj-001", json(TestTemplates.unit(0)))

        val r = enrol("subj-001", json(TestTemplates.single(0.98f)))

        assertInvalidRequest(r)
        val stored = rows("subj-001")
        assertThat(stored.map { it.version }).containsExactly(1)
        assertThat(stored.single().active).isTrue()
    }

    @Test
    fun `AC-11 boundary norm 0_99 is accepted`() {
        assertEnrolled(enrol("subj-lo", json(TestTemplates.single(0.99f))), "subj-lo", 1)
        assertThat(rows("subj-lo").single().template).isEqualTo(TestTemplates.bytes(TestTemplates.floats(0.99f)))
    }

    @Test
    fun `AC-11 boundary norm 1_01 is accepted`() {
        assertEnrolled(enrol("subj-hi", json(TestTemplates.single(1.01f))), "subj-hi", 1)
        assertThat(rows("subj-hi").single().template).isEqualTo(TestTemplates.bytes(TestTemplates.floats(1.01f)))
    }

    // ---------------------------------------------------------------- AC-43

    @ParameterizedTest(name = "AC-43 A05 malformed body {0} is a 400 problem")
    @MethodSource("malformedBodies")
    @Tag("security")
    fun `A05 malformed or wrongly typed JSON is a constant 400 problem`(
        @Suppress("UNUSED_PARAMETER") name: String,
        body: String,
    ) {
        val r = enrol("subj-001", body)

        assertInvalidRequest(r, "subj-001")
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 text plain content type is 415 with a constant problem body`() {
        val r = enrol("subj-001", json(TestTemplates.unit()), contentType = MediaType.TEXT_PLAIN_VALUE)

        assertProblem(r, 415, "urn:matchgw:problem:unsupported-media-type", "subj-001", TestTemplates.unit())
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 form content type is 415`() {
        val r = enrol("subj-001", "template=abc", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)

        assertProblem(r, 415, "urn:matchgw:problem:unsupported-media-type", "subj-001")
    }

    @Test
    @Tag("security")
    fun `A05 GET on the enrollment path with the enroll scope is a constant 405 problem without the path`() {
        val r =
            client
                .get()
                .uri(URI.create("http://localhost:$port/v1/subjects/subj-001/enrollment"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid(scope = "enroll")}")
                .exchange()
                .returnResult(String::class.java)

        assertProblem(
            Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders),
            405,
            "urn:matchgw:problem:method-not-allowed",
            "subj-001",
        )
    }

    // ---------------------------------------------------------------- AC-42

    @Test
    @Tag("security")
    fun `API4 body larger than 8 KB with a valid token is 413 with a constant problem body`() {
        val body = """{"template":"${"A".repeat(9000)}"}"""

        val r = enrol("subj-001", body)

        assertProblem(r, 413, "urn:matchgw:problem:payload-too-large", "subj-001", "A".repeat(100))
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API4 body of 8193 bytes is 413`() {
        assertThat(enrol("subj-001", bodyOfSize(8193)).status).isEqualTo(413)
    }

    @Test
    @Tag("security")
    fun `API4 body of exactly 8192 bytes is not rejected for size`() {
        val body = bodyOfSize(8192)
        assertThat(body.toByteArray().size).isEqualTo(8192)

        // Passes the size limit and fails template validation instead.
        assertInvalidRequest(enrol("subj-001", body), "subj-001")
    }

    @Test
    @Tag("security")
    fun `API4 unauthenticated 9 KB body is 401 because the body is not read before authentication`() {
        val r = enrol("subj-001", """{"template":"${"A".repeat(9000)}"}""", token = null)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- AC-6 / AC-7 on this endpoint

    @Test
    @Tag("security")
    fun `API2 enrolment without a token is 401 and creates nothing`() {
        val r = enrol("subj-001", json(TestTemplates.unit()), token = null)

        assertThat(r.status).isEqualTo(401)
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API5 AC-7 verify-only token is 403 and no row is created`() {
        val r = enrol("subj-001", json(TestTemplates.unit()), JwtTestTokens.valid(scope = "verify"))

        assertThat(r.status).isEqualTo(403)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(mapper.readTree(r.body)["type"].asString()).isEqualTo("urn:matchgw:problem:forbidden")
        assertThat(totalRows()).isZero()
        assertThat(jdbc.sql("select count(*) from subject").query(Int::class.java).single()).isZero()
    }

    @Test
    @Tag("security")
    fun `API5 AC-7 token without any scope is 403 and no row is created`() {
        val r = enrol("subj-001", json(TestTemplates.unit()), JwtTestTokens.missingScope())

        assertThat(r.status).isEqualTo(403)
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- content negotiation / transport

    @ParameterizedTest(name = "A05 Accept {0} on a valid enrolment is 406 and creates nothing")
    @ValueSource(strings = ["text/html", "text/plain"])
    @Tag("security")
    fun `A05 non JSON Accept is 406 before the handler and changes nothing`(accept: String) {
        val r = enrol("subj-406", json(TestTemplates.unit()), accept = accept)

        assertThat(r.status).isEqualTo(406)
        assertThat(totalRows()).isZero()
        assertThat(jdbc.sql("select count(*) from subject").query(Int::class.java).single()).isZero()
    }

    @Test
    @Tag("security")
    fun `A05 Accept text html on a re-enrolment does not change the existing version`() {
        enrol("subj-406", json(TestTemplates.unit(0)))

        val r = enrol("subj-406", json(TestTemplates.unit(1)), accept = "text/html")

        assertThat(r.status).isEqualTo(406)
        val stored = rows("subj-406")
        assertThat(stored.map { it.version }).containsExactly(1)
        assertThat(stored.single().active).isTrue()
        assertThat(
            jdbc.sql("select last_version from subject where subject_id = 'subj-406'").query(Int::class.java).single(),
        ).isEqualTo(1)
    }

    @ParameterizedTest(name = "AC-9 Accept {0} is served with 201")
    @ValueSource(strings = ["application/json", "*/*"])
    fun `AC-9 JSON and wildcard Accept are enrolled with 201`(accept: String) {
        assertEnrolled(enrol("subj-acc", json(TestTemplates.unit()), accept = accept), "subj-acc", 1)
    }

    @Test
    @Tag("security")
    fun `API4 multipart form data on enrolment is 415 and not parsed`() {
        val body = "--xyz\r\nContent-Disposition: form-data; name=\"template\"\r\n\r\n${TestTemplates.unit()}\r\n--xyz--\r\n"

        val r = enrol("subj-mp", body, contentType = "multipart/form-data; boundary=xyz")

        assertProblem(r, 415, "urn:matchgw:problem:unsupported-media-type", "subj-mp", TestTemplates.unit())
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API4 chunked JSON body above 8 KB without Content-Length is 413 problem`() {
        val payload = bodyOfSize(20_000).toByteArray()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v1/subjects/subj-chunk/enrollment"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid(scope = "enroll")}")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(payload) })
                .build()

        val response =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(-1)
        assertThat(response.statusCode()).isEqualTo(413)
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("")).startsWith("application/problem+json")
        assertThat(mapper.readTree(response.body())["type"].asString()).isEqualTo("urn:matchgw:problem:payload-too-large")
        assertThat(response.body()).doesNotContain("subj-chunk").doesNotContain("AAAAAAAAAA")
        assertThat(totalRows()).isZero()
    }

    // ---------------------------------------------------------------- store transactionality

    @Test
    @Tag("security")
    fun `A10 failure while inserting the new version rolls back and answers a constant 500`() {
        jdbc.sql("insert into subject (tenant_id, subject_id, last_version) values ('tenant-a', 's-tx', 1)").update()
        insertVersion("s-tx", 1, active = true, TestTemplates.unitBytes(0))
        // Orphan: version 2 exists although subject.last_version is still 1, so the next INSERT collides on the PK.
        insertVersion("s-tx", 2, active = false, TestTemplates.unitBytes(1))

        val r = enrol("s-tx", json(TestTemplates.unit(2)))

        assertProblem(r, 500, "urn:matchgw:problem:internal", "s-tx", "duplicate", "template_version")
        assertThat(
            jdbc.sql("select last_version from subject where subject_id = 's-tx'").query(Int::class.java).single(),
        ).isEqualTo(1)
        val stored = rows("s-tx")
        assertThat(stored.map { it.version }).containsExactly(1, 2)
        assertThat(stored.map { it.active }).containsExactly(true, false)
        assertThat(stored[0].template).isEqualTo(TestTemplates.unitBytes(0))
        assertThat(stored[1].template).isEqualTo(TestTemplates.unitBytes(1))
    }

    private fun insertVersion(
        subject: String,
        version: Int,
        active: Boolean,
        template: ByteArray,
    ) {
        jdbc
            .sql(
                "insert into template_version (tenant_id, subject_id, version, template, active) " +
                    "values ('tenant-a', :s, :v, :t, :a)",
            ).param("s", subject)
            .param("v", version)
            .param("t", template)
            .param("a", active)
            .update()
    }

    private fun bodyOfSize(size: Int): String {
        val prefix = """{"template":""""
        val suffix = """"}"""
        return prefix + "A".repeat(size - prefix.length - suffix.length) + suffix
    }

    companion object {
        @JvmStatic
        fun invalidSubjectIds(): Stream<String> = Stream.of("a".repeat(65), "a%20b", "%C3%A4", "a%3Bb", "a%40b", "a%24b")

        @JvmStatic
        fun firewallSubjectIds(): Stream<String> = Stream.of("a%00b")

        @JvmStatic
        fun encodedSlashSubjectIds(): Stream<String> = Stream.of("a%2Fb", "a%2fb")

        @JvmStatic
        fun invalidTemplates(): Stream<Arguments> {
            fun case(
                name: String,
                template: String,
            ) = Arguments.of(name, """{"template":"$template"}""", template)

            return Stream.of(
                Arguments.of("missing template field", "{}", ""),
                Arguments.of("null template", """{"template":null}""", ""),
                case("empty template", ""),
                case("not base64", "!".repeat(684)),
                case("short garbage", "AAAA"),
                case(
                    "url safe alphabet",
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
                case("511 bytes", TestTemplates.ofSize(511)),
                case("513 bytes", TestTemplates.ofSize(513)),
                case("NaN component", TestTemplates.withComponent(Float.NaN)),
                case("positive infinity", TestTemplates.withComponent(Float.POSITIVE_INFINITY)),
                case("negative infinity", TestTemplates.withComponent(Float.NEGATIVE_INFINITY)),
                case("norm 0.98", TestTemplates.single(0.98f)),
                case("norm 1.02", TestTemplates.single(1.02f)),
                case("all zero", TestTemplates.base64(ByteArray(512))),
                Arguments.of("extra template padding whitespace", """{"template":"${TestTemplates.unit()}\n"}""", TestTemplates.unit()),
            )
        }

        @JvmStatic
        fun malformedBodies(): Stream<Arguments> =
            Stream.of(
                Arguments.of("truncated JSON", """{"template":"""),
                Arguments.of("number instead of string", """{"template":123}"""),
                Arguments.of("boolean instead of string", """{"template":true}"""),
                Arguments.of("object instead of string", """{"template":{"a":1}}"""),
                Arguments.of("array instead of string", """{"template":["a"]}"""),
                Arguments.of("JSON array body", """["template"]"""),
                Arguments.of("JSON string body", "\"template\""),
                Arguments.of("empty body", ""),
                Arguments.of("not JSON at all", "not json"),
            )
    }
}
