package com.vmartis.matchgateway.gateway.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.util.stream.Stream

/** AC-7 / AC-8 (D-5, D-18): URL-level scope rules; 403 is produced before any handler or body binding. */
@Tag("security")
class ScopeAuthorizationIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    private val mapper = JsonMapper.builder().build()

    private data class Result(
        val status: Int,
        val contentType: MediaType?,
        val body: String,
    )

    private fun call(
        method: HttpMethod,
        uri: String,
        token: String,
    ): Result {
        val r =
            client
                .method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .exchange()
                .returnResult(String::class.java)
        return Result(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "")
    }

    companion object {
        private val ALL = setOf("enroll", "verify", "admin")

        @JvmStatic
        fun rules(): Stream<Arguments> =
            Stream.of(
                Arguments.of(HttpMethod.POST, "/v1/subjects/s1/enrollment", "enroll"),
                Arguments.of(HttpMethod.DELETE, "/v1/subjects/s1", "admin"),
                Arguments.of(HttpMethod.POST, "/v1/verifications", "verify"),
                Arguments.of(HttpMethod.GET, "/v1/verifications/0b9d6b1e-8c1a-4c52-9f0e-5a2f9f6a2c11", "verify"),
                Arguments.of(HttpMethod.PUT, "/v1/tenant/threshold", "admin"),
            )

        @JvmStatic
        fun listedPathsLackingScope(): Stream<Arguments> =
            Stream
                .of(
                    "/v1/subjects/s1/enrollment" to "enroll",
                    "/v1/subjects/s1" to "admin",
                    "/v1/verifications" to "verify",
                    "/v1/verifications/0b9d6b1e-8c1a-4c52-9f0e-5a2f9f6a2c11" to "verify",
                    "/v1/tenant/threshold" to "admin",
                ).flatMap { (uri, required) ->
                    listOf(HttpMethod.HEAD, HttpMethod.PATCH, HttpMethod.OPTIONS).stream().flatMap { m ->
                        (ALL - required).stream().map { lacking -> Arguments.of(m, uri, required, lacking) }
                    }
                }

        @JvmStatic
        fun rulesWithOtherScopes(): Stream<Arguments> =
            rules().flatMap { a ->
                val (m, u, required) = a.get().let { Triple(it[0], it[1], it[2] as String) }
                (ALL - required).stream().map { other -> Arguments.of(m, u, required, other) }
            }
    }

    @ParameterizedTest(name = "API5 {0} {1} with scope {2} reaches routing (404, no handler yet)")
    @MethodSource("rules")
    fun `API5 token with the required scope passes authorization`(
        method: HttpMethod,
        uri: String,
        scope: String,
    ) {
        val r = call(method, uri, JwtTestTokens.valid(scope = scope))

        assertThat(r.status).isEqualTo(404)
    }

    @ParameterizedTest(name = "API5 {0} {1} requires {2}: scope {3} only is 403")
    @MethodSource("rulesWithOtherScopes")
    fun `API5 token with only a different scope is forbidden`(
        method: HttpMethod,
        uri: String,
        @Suppress("UNUSED_PARAMETER") required: String,
        other: String,
    ) {
        val r = call(method, uri, JwtTestTokens.valid(scope = other))

        assertForbidden(r)
    }

    @ParameterizedTest(name = "API5 {0} {1} without any scope claim is 403")
    @MethodSource("rules")
    fun `API5 valid token without a scope claim is forbidden`(
        method: HttpMethod,
        uri: String,
        @Suppress("UNUSED_PARAMETER") scope: String,
    ) {
        assertForbidden(call(method, uri, JwtTestTokens.missingScope()))
    }

    @ParameterizedTest(name = "API5 {0} {1} with an empty scope claim is 403")
    @MethodSource("rules")
    fun `API5 valid token with an empty scope string is forbidden`(
        method: HttpMethod,
        uri: String,
        @Suppress("UNUSED_PARAMETER") scope: String,
    ) {
        assertForbidden(call(method, uri, JwtTestTokens.valid(scope = "")))
    }

    @ParameterizedTest(name = "AC-8 API5 {0} {1} passes with scope enroll verify admin")
    @MethodSource("rules")
    fun `API5 full scope string is parsed space separated and passes every rule`(
        method: HttpMethod,
        uri: String,
        @Suppress("UNUSED_PARAMETER") scope: String,
    ) {
        assertThat(call(method, uri, JwtTestTokens.valid(scope = "enroll verify admin")).status).isEqualTo(404)
    }

    @Test
    fun `API5 AC-7 verify-only on enrollment and delete and enroll-only on threshold are 403`() {
        assertForbidden(call(HttpMethod.POST, "/v1/subjects/s1/enrollment", JwtTestTokens.valid(scope = "verify")))
        assertForbidden(call(HttpMethod.DELETE, "/v1/subjects/s1", JwtTestTokens.valid(scope = "verify")))
        assertForbidden(call(HttpMethod.PUT, "/v1/tenant/threshold", JwtTestTokens.valid(scope = "enroll")))
    }

    @Test
    fun `API5 a scope only needs to be present among several`() {
        assertThat(call(HttpMethod.PUT, "/v1/tenant/threshold", JwtTestTokens.valid(scope = "verify admin")).status).isEqualTo(404)
    }

    @ParameterizedTest(name = "API5 authenticated token on unlisted path {0} is 403 (default deny)")
    @ValueSource(strings = ["/v1/anything", "/v1/subjects/s1/", "/v1/verifications/", "/v1/subjects", "/v1/tenant/threshold/x"])
    fun `API5 any unlisted path is denied for an authenticated token with every scope`(uri: String) {
        assertForbidden(call(HttpMethod.GET, uri, JwtTestTokens.valid(scope = "enroll verify admin")))
    }

    @ParameterizedTest(name = "API5 {0} {1} with a token lacking {2} is 403")
    @MethodSource("listedPathsLackingScope")
    fun `API5 HEAD PATCH and OPTIONS are scoped per path like every other method`(
        method: HttpMethod,
        uri: String,
        @Suppress("UNUSED_PARAMETER") required: String,
        lacking: String,
    ) {
        val r =
            client
                .method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid(scope = lacking)}")
                .exchange()
                .returnResult(String::class.java)

        assertThat(r.status.value()).isEqualTo(403)
        if (method != HttpMethod.HEAD) {
            assertThat(r.responseHeaders.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
            assertThat(r.responseBody).contains("urn:matchgw:problem:forbidden")
        }
    }

    @Test
    fun `API5 HEAD on a GET path with the verify scope reaches routing`() {
        val r = call(HttpMethod.HEAD, "/v1/verifications/0b9d6b1e-8c1a-4c52-9f0e-5a2f9f6a2c11", JwtTestTokens.valid(scope = "verify"))

        assertThat(r.status).isEqualTo(404)
    }

    @Test
    fun `API5 the 403 body is constant across endpoints and scopes and leaks nothing`() {
        val bodies =
            listOf(
                call(HttpMethod.POST, "/v1/subjects/s1/enrollment", JwtTestTokens.valid(scope = "verify")),
                call(HttpMethod.PUT, "/v1/tenant/threshold", JwtTestTokens.missingScope()),
                call(HttpMethod.DELETE, "/v1/subjects/other-subject", JwtTestTokens.valid(scope = "enroll")),
            ).map { it.body }

        assertThat(bodies.toSet()).hasSize(1)
        val json = mapper.readTree(bodies.first())
        assertThat(json["type"].asString()).isEqualTo("urn:matchgw:problem:forbidden")
        assertThat(json["status"].asInt()).isEqualTo(403)
        assertThat(bodies.first())
            .doesNotContain("SCOPE_")
            .doesNotContain("tenant-a")
            .doesNotContain("s1")
            .doesNotContain("Exception")
            .doesNotContain("admin")
    }

    @Test
    fun `API5 scope is checked before the request body is read`() {
        val r =
            client
                .post()
                .uri("/v1/subjects/s1/enrollment")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid(scope = "verify")}")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{ this is not json")
                .exchange()
                .returnResult(String::class.java)

        assertThat(r.status.value()).isEqualTo(403)
    }

    private fun assertForbidden(r: Result) {
        assertThat(r.status).isEqualTo(403)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val json = mapper.readTree(r.body)
        assertThat(json["type"].asString()).isEqualTo("urn:matchgw:problem:forbidden")
        assertThat(json["status"].asInt()).isEqualTo(403)
        assertThat(r.body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("SCOPE_")
    }
}
