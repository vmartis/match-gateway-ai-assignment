package com.vmartis.matchgateway.gateway.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.client.RestTestClient

/** API8: hardening headers on every response and no cross-origin access (CLAUDE.md §4). */
@Tag("security")
class SecurityHeadersIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    private fun assertHardened(h: HttpHeaders) {
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(h.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'")
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(h.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    @Test
    fun `API8 security headers are present on a 401 api response`() {
        val r =
            client
                .get()
                .uri("/v1/anything")
                .exchange()
                .expectStatus()
                .isUnauthorized
                .returnResult(String::class.java)

        assertHardened(r.responseHeaders)
    }

    @Test
    fun `API8 security headers are present on a 403 api response`() {
        val r =
            client
                .delete()
                .uri("/v1/subjects/s1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid(scope = "verify")}")
                .exchange()
                .expectStatus()
                .isForbidden
                .returnResult(String::class.java)

        assertHardened(r.responseHeaders)
    }

    @Test
    fun `API8 security headers are present on public actuator health`() {
        val r =
            client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)

        assertHardened(r.responseHeaders)
    }

    @Test
    fun `API8 cors preflight from a foreign origin gets no allow-origin header`() {
        val r =
            client
                .options()
                .uri("/v1/verifications")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .returnResult(String::class.java)

        assertThat(r.responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
    }

    @Test
    fun `API8 simple cross-origin request gets no allow-origin header`() {
        val r =
            client
                .get()
                .uri("/v1/anything")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${JwtTestTokens.valid()}")
                .exchange()
                .returnResult(String::class.java)

        assertThat(r.responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
    }
}
