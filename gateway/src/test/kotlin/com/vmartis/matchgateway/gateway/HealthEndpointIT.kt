package com.vmartis.matchgateway.gateway

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper

/** The standard actuator health endpoint on the API port (D-23): public, status only, probes, nothing else exposed. */
class HealthEndpointIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var groups: HealthEndpointGroups

    private fun get(
        path: String,
        bearer: String? = null,
    ) = client
        .get()
        .uri(path)
        .headers { h -> bearer?.let { h.setBearerAuth(it) } }
        .exchange()
        .returnResult(String::class.java)

    @Test
    fun `health is served without a token with status UP and no component details`() {
        val result = get("/actuator/health")
        assertThat(result.status.value()).isEqualTo(200)
        // Boot always lists the health group names on the root response once probes are enabled (no property to hide
        // them); they are static metadata. Component details must never appear.
        val body = JsonMapper.builder().build().readTree(result.responseBody)
        assertThat(body.propertyNames().asSequence().toSet()).isEqualTo(setOf("status", "groups"))
        assertThat(body["status"].asString()).isEqualTo("UP")
        val groups = body["groups"]
        assertThat((0 until groups.size()).map { groups[it].asString() }).containsExactlyInAnyOrder("liveness", "readiness")
        assertThat(result.responseBody).doesNotContain("components").doesNotContain("details")
    }

    @Test
    fun `D-24 readiness group includes the database contributor and the readiness state`() {
        val readiness = checkNotNull(groups.get("readiness"))

        assertThat(readiness.isMember("db")).isTrue()
        assertThat(readiness.isMember("readinessState")).isTrue()
    }

    @Test
    fun `D-24 liveness group does not include the database contributor`() {
        val liveness = checkNotNull(groups.get("liveness"))

        assertThat(liveness.isMember("db")).isFalse()
        assertThat(liveness.isMember("livenessState")).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/health/liveness", "/actuator/health/readiness"])
    fun `probes are served without a token`(path: String) {
        val result = get(path)
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody).isEqualTo("""{"status":"UP"}""")
    }

    @Test
    @Tag("security")
    fun `API8 component health paths reveal nothing`() {
        // show-components=never: individual contributors are not addressable.
        assertThat(get("/actuator/health/db").status.value()).isEqualTo(404)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/actuator", "/actuator/env", "/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator/heapdump"],
    )
    @Tag("security")
    fun `API8 no other actuator endpoint is reachable without a token`(path: String) {
        assertThat(get(path).status.value()).isIn(401, 404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/env", "/actuator/prometheus", "/actuator/heapdump"])
    @Tag("security")
    fun `API8 no other actuator endpoint is reachable even with a valid token`(path: String) {
        assertThat(get(path, JwtTestTokens.valid()).status.value()).isIn(403, 404)
    }
}
