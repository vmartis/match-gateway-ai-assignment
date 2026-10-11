package com.vmartis.matchgateway.gateway.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.function.Supplier
import java.util.stream.Stream

/** AC-6: the full JWT negative set through the real filter chain (no jwt() post-processor). */
@Tag("security")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtAuthenticationIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    private data class Result(
        val status: Int,
        val challenge: String?,
        val contentType: MediaType?,
        val body: String,
    )

    private fun get(
        uri: String,
        authorization: String?,
    ): Result {
        val spec = client.get().uri(uri)
        if (authorization != null) spec.header(HttpHeaders.AUTHORIZATION, authorization)
        val r = spec.exchange().returnResult(String::class.java)
        return Result(
            r.status.value(),
            r.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE),
            r.responseHeaders.contentType,
            r.responseBody ?: "",
        )
    }

    fun negativeSet(): Stream<Arguments> {
        fun case(
            name: String,
            token: Supplier<String?>,
        ) = Arguments.of(name, token)
        return Stream.of(
            case("missing header") { null },
            case("garbage bearer") { "not-a-jwt" },
            case("expired") { JwtTestTokens.expired() },
            case("not yet valid") { JwtTestTokens.notYetValid() },
            case("signed with a different key") { JwtTestTokens.wrongKey() },
            case("alg none") { JwtTestTokens.algNone() },
            case("hs256 signed with the public key") { JwtTestTokens.hs256WithPublicKey() },
            case("wrong issuer") { JwtTestTokens.wrongIssuer() },
            case("wrong audience") { JwtTestTokens.wrongAudience() },
            case("missing tenant_id") { JwtTestTokens.missingTenant() },
            case("malformed tenant_id") { JwtTestTokens.badTenant() },
            case("missing sub") { JwtTestTokens.missingSub() },
            case("ttl longer than 900 s") { JwtTestTokens.ttlTooLong() },
            case("tampered payload with original signature") { JwtTestTokens.tamperedPayload() },
            case("expired 75 s ago") { JwtTestTokens.withTimes(-600, -600, -75) },
            case("missing iat") { JwtTestTokens.withTimes(null, null, 300) },
            // Spring derives a missing iat from exp; with exp 30 s ahead that iat is within the skew, so only the explicit
            // missing-iat check rejects it (pinned by mutation testing).
            case("missing iat with exp 30 s ahead") { JwtTestTokens.withTimes(null, null, 30) },
            case("missing exp") { JwtTestTokens.withTimes(0, 0, null) },
            case("tenant_id of 65 chars") { JwtTestTokens.valid(tenant = "a".repeat(65)) },
            case("tenant_id with newline") { JwtTestTokens.valid(tenant = "tenant\na") },
            case("tenant_id with slash") { JwtTestTokens.valid(tenant = "ten/ant") },
            case("tenant_id with single quote") { JwtTestTokens.valid(tenant = "ten'ant") },
            case("tenant_id as a number") { JwtTestTokens.withTenantClaim(12345) },
            case("iat 30 days in the future without nbf") {
                JwtTestTokens.withTimes(30L * 86_400, null, 30L * 86_400 + 900)
            },
        )
    }

    @ParameterizedTest(name = "API2 {0} is rejected with 401 and a constant problem body")
    @MethodSource("negativeSet")
    fun `API2 invalid token is rejected with a constant 401`(
        @Suppress("UNUSED_PARAMETER") name: String,
        token: Supplier<String?>,
    ) {
        val jwt = token.get()

        val r = get("/v1/anything", jwt?.let { "Bearer $it" })

        assertThat(r.status).isEqualTo(401)
        assertThat(r.challenge).isEqualTo("Bearer")
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(r.body).contains("urn:matchgw:problem:unauthorized")
        if (jwt != null) {
            jwt.split(".").filter { it.length > 8 }.forEach { assertThat(r.body).doesNotContain(it) }
        }
        assertThat(r.body).doesNotContain("error_description").doesNotContain("Exception").doesNotContain("expired")
    }

    @Test
    fun `API2 the 401 body is byte-identical for every rejection reason`() {
        val bodies =
            negativeSet()
                .map { args ->
                    @Suppress("UNCHECKED_CAST")
                    val jwt = (args.get()[1] as Supplier<String?>).get()
                    get("/v1/anything", jwt?.let { "Bearer $it" }).body
                }.toList()

        assertThat(bodies).hasSize(24)
        assertThat(bodies.toSet()).hasSize(1)
        assertThat(bodies.first()).isNotBlank()
    }

    @Test
    fun `API2 non-bearer authorization schemes are rejected with 401`() {
        val basic = get("/v1/anything", "Basic dXNlcjpwYXNz")

        assertThat(basic.status).isEqualTo(401)
        assertThat(basic.challenge).isEqualTo("Bearer")
    }

    private fun routed(token: String): Result = get(ROUTED_PATH, "Bearer $token")

    @Test
    fun `API2 valid token passes authentication and reaches routing which has no handler`() {
        val r = routed(JwtTestTokens.valid(scope = "verify"))

        assertThat(r.status).isEqualTo(404)
        assertThat(r.challenge).isNull()
    }

    @Test
    fun `API2 token with a 64 char tenant_id is accepted at the boundary`() {
        assertThat(routed(JwtTestTokens.valid(tenant = "a".repeat(64), scope = "verify")).status).isEqualTo(404)
    }

    @Test
    fun `API2 token expired 30 s ago is accepted within the 60 s clock skew`() {
        assertThat(routed(JwtTestTokens.withTimes(-600, -600, -30)).status).isEqualTo(404)
    }

    @Test
    fun `API2 token with nbf 30 s ahead is accepted within the 60 s clock skew`() {
        assertThat(routed(JwtTestTokens.withTimes(0, 30, 600)).status).isEqualTo(404)
    }

    @Test
    fun `API2 token with nbf 120 s ahead is rejected`() {
        assertThat(routed(JwtTestTokens.withTimes(0, 120, 600)).status).isEqualTo(401)
    }

    @Test
    fun `API2 token lifetime of exactly 900 s is accepted and 901 s is rejected`() {
        assertThat(routed(JwtTestTokens.withTimes(0, 0, 900)).status).isEqualTo(404)
        assertThat(routed(JwtTestTokens.withTimes(0, 0, 901)).status).isEqualTo(401)
    }

    @Test
    fun `API2 token with iat 30 s ahead is accepted and 120 s ahead is rejected`() {
        assertThat(routed(JwtTestTokens.withTimes(30, null, 630)).status).isEqualTo(404)
        assertThat(routed(JwtTestTokens.withTimes(120, null, 720)).status).isEqualTo(401)
    }

    @Test
    fun `API5 token carrying only scp and no scope claim gets no authorities and is forbidden`() {
        val r = routed(JwtTestTokens.scpOnly("enroll", "verify", "admin"))

        assertThat(r.status).isEqualTo(403)
        assertThat(r.body).contains("urn:matchgw:problem:forbidden")
    }

    @Test
    fun `API2 access_token query parameter is not accepted as credentials`() {
        val r = get("/v1/anything?access_token=${JwtTestTokens.valid()}", null)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.challenge).isEqualTo("Bearer")
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private companion object {
        const val ROUTED_PATH = "/v1/verifications/0b9d6b1e-8c1a-4c52-9f0e-5a2f9f6a2c11"
    }
}
