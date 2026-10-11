package com.vmartis.matchgateway.gateway.tenant

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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.net.URI
import java.util.Random
import java.util.UUID
import java.util.stream.Stream
import kotlin.math.sqrt

/**
 * Step 7: AC-41 (gateway side) and AC-44. Every /v1 endpoint and the actuator health are exercised with valid and invalid
 * templates and tokens; captured log output (shipped log levels, plus DEBUG for our own code) and every response body
 * must contain neither a submitted template, a raw token nor a "Bearer " value. Spring's own web/security DEBUG loggers
 * echo request URLs and parse errors and are never enabled in production (D-25, README "Known limitations").
 */
@Tag("security")
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(
    properties = [
        "logging.level.com.vmartis=DEBUG",
    ],
)
class LeakageIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @LocalServerPort
    var port: Int = 0

    private data class Response(
        val status: Int,
        val contentType: MediaType?,
        val body: String,
        val headers: HttpHeaders,
    )

    private val seen = mutableListOf<Response>()

    @BeforeEach
    fun cleanDatabase() {
        seen.clear()
        jdbc.sql("delete from idempotency_key").update()
        jdbc.sql("delete from verification").update()
        jdbc.sql("delete from subject").update()
        jdbc.sql("delete from tenant_settings").update()
    }

    private fun call(
        method: HttpMethod,
        path: String,
        token: String? = null,
        rawAuthorization: String? = null,
        body: String? = null,
        contentType: String = MediaType.APPLICATION_JSON_VALUE,
        accept: String? = null,
        idempotencyKey: String? = null,
    ): Response {
        val spec =
            client
                .method(method)
                .uri(URI.create("http://localhost:$port$path"))
                .headers { h ->
                    token?.let { h.setBearerAuth(it) }
                    rawAuthorization?.let { h.set(HttpHeaders.AUTHORIZATION, it) }
                    idempotencyKey?.let { h.set("Idempotency-Key", it) }
                    accept?.let { h.set(HttpHeaders.ACCEPT, it) }
                    if (body != null) h.set(HttpHeaders.CONTENT_TYPE, contentType)
                }
        val r = (if (body != null) spec.body(body) else spec).exchange().returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
            .also { seen += it }
    }

    /** Pseudo-random (fixed seed) float32 vector, so the base64 text is high-entropy and cannot occur by chance. */
    private fun randomFloats(seed: Long): FloatArray {
        val r = Random(seed)
        val v = DoubleArray(128) { r.nextGaussian() }
        val n = sqrt(v.sumOf { it * it })
        return FloatArray(128) { (v[it] / n).toFloat() }
    }

    private class Submitted(
        val name: String,
        val base64: String,
    )

    private fun valid(seed: Long) = Submitted("valid", TestTemplates.base64(TestTemplates.bytes(randomFloats(seed))))

    private fun invalidTemplates(): List<Submitted> {
        val good = randomFloats(2)
        val goodBytes = TestTemplates.bytes(good)
        return listOf(
            Submitted("too short", TestTemplates.base64(goodBytes.copyOf(100))),
            Submitted("too long", TestTemplates.base64(goodBytes.copyOf(516))),
            Submitted("bad base64", TestTemplates.base64(goodBytes).let { it.substring(0, 60) + "!!!!" + it.substring(64) }),
            Submitted("NaN component", TestTemplates.base64(TestTemplates.bytes(good.copyOf().also { it[5] = Float.NaN }))),
            Submitted(
                "infinite component",
                TestTemplates.base64(TestTemplates.bytes(good.copyOf().also { it[6] = Float.POSITIVE_INFINITY })),
            ),
            Submitted("norm 2", TestTemplates.base64(TestTemplates.bytes(FloatArray(128) { good[it] * 2f }))),
            Submitted("zero vector", TestTemplates.base64(ByteArray(512))),
        )
    }

    /** Full string plus a slice from the middle, so partial or truncated echoes are caught too. */
    private fun fragments(s: String): List<String> {
        val middle = s.substring(s.length / 2 - 24, s.length / 2 + 24)
        // A low-entropy slice (the all-zero vector is "AAAA...") would match unrelated text, so only the full string counts.
        return if (middle.toSet().size > 8) listOf(s, middle) else listOf(s)
    }

    private fun assertNoLeak(
        output: CapturedOutput,
        secrets: Collection<String>,
    ) {
        // Canary: a request-time WARN must be captured, otherwise an empty capture would pass the scan (review step 7).
        call(HttpMethod.GET, "/v1/tenant/threshold", JwtTestTokens.valid(scope = "admin"))
        val text = output.all
        assertThat(text).describedAs("output capture is live").contains("Request method 'GET' is not supported")
        secrets.filter { it.isNotEmpty() }.forEach { secret ->
            assertThat(text).describedAs("log output leaks a secret").doesNotContain(secret)
            seen.forEach { r ->
                assertThat(r.body).describedAs("response body leaks a secret").doesNotContain(secret)
                r.headers.headerNames().forEach { name ->
                    assertThat(r.headers[name].orEmpty().joinToString()).describedAs("header $name leaks a secret").doesNotContain(secret)
                }
            }
        }
        assertThat(text).doesNotContain("Bearer ")
        seen.forEach { assertThat(it.body).doesNotContain("Bearer ") }
    }

    private fun tokenSecrets(token: String): List<String> {
        val parts = token.split(".")
        return listOf(token) + parts.filter { it.length >= 20 }
    }

    // ---------------------------------------------------------------- templates

    @Test
    fun `A09 AC-41 valid and invalid templates on enrol, verify and threshold never reach logs or bodies`(output: CapturedOutput) {
        val all = listOf(valid(1)) + invalidTemplates()
        val admin = JwtTestTokens.valid(scope = "enroll verify admin")
        val statuses = mutableListOf<Int>()
        all.forEachIndexed { i, t ->
            statuses += call(HttpMethod.POST, "/v1/subjects/subj-$i/enrollment", admin, body = """{"template":"${t.base64}"}""").status
            statuses +=
                call(
                    HttpMethod.POST,
                    "/v1/verifications",
                    admin,
                    body = """{"subjectId":"subj-$i","probeTemplate":"${t.base64}"}""",
                    idempotencyKey = "key-$i",
                ).status
            statuses += call(HttpMethod.PUT, "/v1/tenant/threshold", admin, body = """{"threshold":"${t.base64}"}""").status
        }
        val subjectInPath = all[0].base64.filter { it.isLetterOrDigit() }.take(80)
        statuses +=
            call(HttpMethod.POST, "/v1/subjects/$subjectInPath/enrollment", admin, body = """{"template":"${all[0].base64}"}""").status
        statuses += call(HttpMethod.GET, "/v1/verifications/$subjectInPath", admin).status
        val accepted =
            call(
                HttpMethod.POST,
                "/v1/verifications",
                admin,
                body = """{"subjectId":"s","probeTemplate":"${all[0].base64}"}""",
                idempotencyKey = "k-ok",
            )
        statuses += accepted.status

        // sanity: the valid template was accepted, so the leak check is not vacuous
        assertThat(statuses.first()).isEqualTo(201)
        assertThat(accepted.status).isEqualTo(202)
        assertThat(statuses).contains(400)
        assertNoLeak(output, all.flatMap { fragments(it.base64) } + subjectInPath)
    }

    @Test
    fun `A09 AC-41 oversized and wrongly typed bodies never reach logs or bodies`(output: CapturedOutput) {
        val admin = JwtTestTokens.valid()
        val big = valid(3).base64.repeat(20)
        val r413 = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"$big"}""")
        val r415 =
            call(
                HttpMethod.POST,
                "/v1/subjects/s1/enrollment",
                admin,
                body = """{"template":"${valid(4).base64}"}""",
                contentType = "text/plain",
            )
        val r400 = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"${valid(5).base64}""")

        assertThat(r413.status).isEqualTo(413)
        assertThat(r415.status).isEqualTo(415)
        assertThat(r400.status).isEqualTo(400)
        assertNoLeak(output, fragments(valid(3).base64) + fragments(valid(4).base64) + fragments(valid(5).base64))
    }

    // ---------------------------------------------------------------- tokens

    @ParameterizedTest(name = "A09 AC-41 {0} token on every /v1 endpoint never reaches logs or bodies")
    @MethodSource("tokens")
    fun `A09 AC-41 tokens of every kind on every endpoint never reach logs or bodies`(
        name: String,
        token: String,
        output: CapturedOutput,
    ) {
        val t = valid(6).base64
        val statuses =
            listOf(
                call(HttpMethod.POST, "/v1/subjects/s1/enrollment", token, body = """{"template":"$t"}""").status,
                call(
                    HttpMethod.POST,
                    "/v1/verifications",
                    token,
                    body = """{"subjectId":"s1","probeTemplate":"$t"}""",
                    idempotencyKey = "k1",
                ).status,
                call(HttpMethod.GET, "/v1/verifications/${UUID.randomUUID()}", token).status,
                call(HttpMethod.PUT, "/v1/tenant/threshold", token, body = """{"threshold":0.6}""").status,
                call(HttpMethod.DELETE, "/v1/subjects/s1", token).status,
                call(HttpMethod.GET, "/actuator/health", token).status,
            )

        if (name != "valid") {
            assertThat(statuses.count { it == 401 || it == 403 }).isGreaterThanOrEqualTo(1)
        }
        assertNoLeak(output, tokenSecrets(token) + fragments(t))
    }

    @Test
    fun `A09 AC-41 a valid token used on every endpoint never reaches logs or bodies`(output: CapturedOutput) {
        val token = JwtTestTokens.valid(scope = "enroll verify admin")
        val t = valid(7).base64
        call(HttpMethod.POST, "/v1/subjects/s1/enrollment", token, body = """{"template":"$t"}""")
        val accepted =
            call(HttpMethod.POST, "/v1/verifications", token, body = """{"subjectId":"s1","probeTemplate":"$t"}""", idempotencyKey = "k1")
        val id = Regex("\"verificationId\":\"([^\"]+)\"").find(accepted.body)?.groupValues?.get(1)
        call(HttpMethod.GET, "/v1/verifications/$id", token)
        call(HttpMethod.PUT, "/v1/tenant/threshold", token, body = """{"threshold":0.6}""")
        call(HttpMethod.GET, "/actuator/health", token)

        assertThat(accepted.status).isEqualTo(202)
        assertNoLeak(output, tokenSecrets(token) + fragments(t))
    }

    @Test
    fun `A09 AC-41 non-bearer and malformed Authorization header values never reach logs or bodies`(output: CapturedOutput) {
        val secrets = listOf("Basic dXNlcjpzdXBlci1zZWNyZXQtcGFzc3dvcmQ=", "Bearer", "Bearer  ", "Token abc123SECRETabc123", "Bearer a.b")
        val credentials = listOf("dXNlcjpzdXBlci1zZWNyZXQtcGFzc3dvcmQ=", "abc123SECRETabc123")
        secrets.forEach {
            val r = call(HttpMethod.GET, "/v1/verifications/${UUID.randomUUID()}", rawAuthorization = it)
            assertThat(r.status).isEqualTo(401)
        }

        assertNoLeak(output, credentials)
    }

    // ---------------------------------------------------------------- scopes

    @Test
    fun `A09 AC-41 missing and wrong scopes yield 403 without echoing the template or token`(output: CapturedOutput) {
        val t = valid(8).base64
        val verifyOnly = JwtTestTokens.valid(scope = "verify")
        val enrollOnly = JwtTestTokens.valid(scope = "enroll")
        val none = JwtTestTokens.missingScope()
        val statuses =
            listOf(
                call(HttpMethod.POST, "/v1/subjects/s1/enrollment", verifyOnly, body = """{"template":"$t"}""").status,
                call(
                    HttpMethod.POST,
                    "/v1/verifications",
                    enrollOnly,
                    body = """{"subjectId":"s1","probeTemplate":"$t"}""",
                    idempotencyKey = "k",
                ).status,
                call(HttpMethod.GET, "/v1/verifications/${UUID.randomUUID()}", enrollOnly).status,
                call(HttpMethod.PUT, "/v1/tenant/threshold", enrollOnly, body = """{"threshold":0.6}""").status,
                call(
                    HttpMethod.POST,
                    "/v1/verifications",
                    none,
                    body = """{"subjectId":"s1","probeTemplate":"$t"}""",
                    idempotencyKey = "k",
                ).status,
            )

        assertThat(statuses).containsOnly(403)
        assertNoLeak(output, listOf(verifyOnly, enrollOnly, none).flatMap { tokenSecrets(it) } + fragments(t))
    }

    @Test
    fun `A09 AC-41 erasure of a subject never leaks templates or tokens into logs or bodies`(output: CapturedOutput) {
        val admin = JwtTestTokens.valid(scope = "enroll verify admin")
        val t = valid(10).base64
        val other = JwtTestTokens.valid(tenant = "tenant-b", scope = "enroll verify admin")
        val enrolled = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"$t"}""")
        val accepted =
            call(HttpMethod.POST, "/v1/verifications", admin, body = """{"subjectId":"s1","probeTemplate":"$t"}""", idempotencyKey = "k1")
        val statuses =
            listOf(
                call(HttpMethod.DELETE, "/v1/subjects/s1", JwtTestTokens.valid(scope = "verify")).status,
                call(HttpMethod.DELETE, "/v1/subjects/s1", other).status,
                call(HttpMethod.DELETE, "/v1/subjects/s1", admin).status,
                call(HttpMethod.DELETE, "/v1/subjects/s1", admin).status,
                call(HttpMethod.DELETE, "/v1/subjects/bad%20id", admin).status,
            )

        assertThat(enrolled.status).isEqualTo(201)
        assertThat(accepted.status).isEqualTo(202)
        assertThat(statuses).containsExactly(403, 404, 204, 404, 400) // malformed id → 400 (D-26)
        assertNoLeak(output, tokenSecrets(admin) + tokenSecrets(other) + fragments(t))
    }

    // ---------------------------------------------------------------- AC-44

    @Test
    fun `A02 AC-44 every status of every v1 endpoint carries nosniff and no-store`() {
        val admin = JwtTestTokens.valid(scope = "enroll verify admin")
        val enrolOnly = JwtTestTokens.valid(scope = "enroll")
        val t = valid(9).base64
        val responses = linkedMapOf<String, Response>()
        responses["201 enrol"] = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"$t"}""")
        responses["202 submit"] =
            call(HttpMethod.POST, "/v1/verifications", admin, body = """{"subjectId":"s1","probeTemplate":"$t"}""", idempotencyKey = "k1")
        val id = Regex("\"verificationId\":\"([^\"]+)\"").find(responses.getValue("202 submit").body)?.groupValues?.get(1)
        responses["200 get"] = call(HttpMethod.GET, "/v1/verifications/$id", admin)
        responses["200 threshold"] = call(HttpMethod.PUT, "/v1/tenant/threshold", admin, body = """{"threshold":0.7}""")
        responses["400 enrol"] = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"AAAA"}""")
        responses["400 threshold"] = call(HttpMethod.PUT, "/v1/tenant/threshold", admin, body = """{"threshold":1.01}""")
        responses["400 submit"] = call(HttpMethod.POST, "/v1/verifications", admin, body = """{"subjectId":"s1","probeTemplate":"$t"}""")
        responses["401 no token"] = call(HttpMethod.GET, "/v1/verifications/$id")
        responses["401 expired"] = call(HttpMethod.PUT, "/v1/tenant/threshold", JwtTestTokens.expired(), body = """{"threshold":0.7}""")
        responses["403 scope"] = call(HttpMethod.PUT, "/v1/tenant/threshold", enrolOnly, body = """{"threshold":0.7}""")
        responses["403 unmapped"] = call(HttpMethod.GET, "/v1/nothing-here", admin)
        responses["404 unknown"] = call(HttpMethod.GET, "/v1/verifications/${UUID.randomUUID()}", admin)
        responses["405 method"] = call(HttpMethod.GET, "/v1/tenant/threshold", admin)
        responses["406 accept"] = call(HttpMethod.GET, "/v1/verifications/$id", admin, accept = "text/html")
        responses["413 body"] = call(HttpMethod.POST, "/v1/subjects/s1/enrollment", admin, body = """{"template":"${"A".repeat(9000)}"}""")
        responses["415 type"] = call(HttpMethod.PUT, "/v1/tenant/threshold", admin, body = "0.7", contentType = "text/plain")
        responses["403 delete scope"] = call(HttpMethod.DELETE, "/v1/subjects/s1", JwtTestTokens.valid(scope = "verify"))
        responses["404 delete unknown"] = call(HttpMethod.DELETE, "/v1/subjects/unknown-subject", admin)
        responses["204 delete"] = call(HttpMethod.DELETE, "/v1/subjects/s1", admin)

        val expected =
            mapOf(
                "201 enrol" to 201,
                "202 submit" to 202,
                "200 get" to 200,
                "200 threshold" to 200,
                "400 enrol" to 400,
                "400 threshold" to 400,
                "400 submit" to 400,
                "401 no token" to 401,
                "401 expired" to 401,
                "403 scope" to 403,
                "403 unmapped" to 403,
                "404 unknown" to 404,
                "405 method" to 405,
                "406 accept" to 406,
                "413 body" to 413,
                "415 type" to 415,
                "403 delete scope" to 403,
                "404 delete unknown" to 404,
                "204 delete" to 204,
            )
        expected.forEach { (name, status) ->
            val r = responses.getValue(name)
            assertThat(r.status).describedAs("status of $name").isEqualTo(status)
            assertThat(r.headers.getFirst("X-Content-Type-Options")).describedAs("nosniff on $name").isEqualTo("nosniff")
            assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).describedAs("no-store on $name").contains("no-store")
        }
    }

    @Test
    fun `A09 AC-41 idempotent replay and 422 key reuse never leak templates or tokens into logs or bodies`(output: CapturedOutput) {
        val token = JwtTestTokens.valid(scope = "enroll verify admin")
        val t1 = valid(11).base64
        val t2 = valid(12).base64
        val first =
            call(HttpMethod.POST, "/v1/verifications", token, body = """{"subjectId":"s1","probeTemplate":"$t1"}""", idempotencyKey = "k-r")
        val replay =
            call(HttpMethod.POST, "/v1/verifications", token, body = """{"subjectId":"s1","probeTemplate":"$t1"}""", idempotencyKey = "k-r")
        val reusedProbe =
            call(HttpMethod.POST, "/v1/verifications", token, body = """{"subjectId":"s1","probeTemplate":"$t2"}""", idempotencyKey = "k-r")
        val reusedSubject =
            call(HttpMethod.POST, "/v1/verifications", token, body = """{"subjectId":"s2","probeTemplate":"$t1"}""", idempotencyKey = "k-r")

        assertThat(first.status).isEqualTo(202)
        assertThat(replay.status).isEqualTo(202)
        assertThat(replay.body).isEqualTo(first.body)
        assertThat(reusedProbe.status).isEqualTo(422)
        assertThat(reusedSubject.status).isEqualTo(422)
        assertThat(reusedProbe.body).isEqualTo(reusedSubject.body)
        assertNoLeak(output, tokenSecrets(token) + fragments(t1) + fragments(t2) + "k-r")
    }

    @Test
    fun `A02 AC-44 a 422 key reuse response carries nosniff and no-store`() {
        val token = JwtTestTokens.valid(scope = "verify")
        call(
            HttpMethod.POST,
            "/v1/verifications",
            token,
            body = """{"subjectId":"s1","probeTemplate":"${valid(13).base64}"}""",
            idempotencyKey = "k-422",
        )

        val r =
            call(
                HttpMethod.POST,
                "/v1/verifications",
                token,
                body = """{"subjectId":"s1","probeTemplate":"${valid(14).base64}"}""",
                idempotencyKey = "k-422",
            )

        assertThat(r.status).isEqualTo(422)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(r.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    @Test
    fun `A02 AC-44 the public health endpoint also carries nosniff and no-store`() {
        val r = call(HttpMethod.GET, "/actuator/health")

        assertThat(r.status).isEqualTo(200)
        assertThat(r.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    companion object {
        @JvmStatic
        fun tokens(): Stream<Arguments> =
            Stream.of(
                Arguments.of("valid", JwtTestTokens.valid(scope = "enroll verify admin")),
                Arguments.of("expired", JwtTestTokens.expired()),
                Arguments.of("not-yet-valid", JwtTestTokens.notYetValid()),
                Arguments.of("forged (wrong key)", JwtTestTokens.wrongKey()),
                Arguments.of("tampered payload", JwtTestTokens.tamperedPayload()),
                Arguments.of("alg none", JwtTestTokens.algNone()),
                Arguments.of("hs256 key confusion", JwtTestTokens.hs256WithPublicKey()),
                Arguments.of("wrong issuer", JwtTestTokens.wrongIssuer()),
                Arguments.of("wrong audience", JwtTestTokens.wrongAudience()),
                Arguments.of("missing scope", JwtTestTokens.missingScope()),
                Arguments.of("verify-only", JwtTestTokens.valid(scope = "verify")),
                Arguments.of("garbage", "garbage-token-SECRET-0123456789.not-a-jwt-SECRET-0123456789.zzz"),
            )
    }
}
