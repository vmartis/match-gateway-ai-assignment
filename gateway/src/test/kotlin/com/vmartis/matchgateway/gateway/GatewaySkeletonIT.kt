package com.vmartis.matchgateway.gateway

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient

// Metrics export is off in tests by default; without it /actuator/prometheus would 401 merely for being unknown.
@AutoConfigureMetrics
class GatewaySkeletonIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `public port serves health without a token`() {
        val body =
            client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseBody
        assertThat(body).contains("\"status\":\"UP\"")
        // D-19: the public endpoint exposes the aggregate status only, never component details.
        assertThat(body).doesNotContain("components").doesNotContain("details")
    }

    @Test
    fun `management port serves health`() {
        val mgmt = RestTestClient.bindToServer().baseUrl("http://localhost:$managementPort").build()
        val body =
            mgmt
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseBody
        assertThat(body).contains("\"status\":\"UP\"")
    }

    @Test
    @Tag("security")
    fun `API8 unauthenticated api request is rejected with bearer challenge`() {
        val result =
            client
                .get()
                .uri("/v1/anything")
                .exchange()
                .expectStatus()
                .isUnauthorized
                .returnResult(String::class.java)
        assertThat(result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
        assertThat(result.responseHeaders.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val body = result.responseBody ?: ""
        assertThat(body).isNotBlank()
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("trace")
    }

    @Test
    @Tag("security")
    fun `API8 public port does not expose other actuator endpoints`() {
        client
            .get()
            .uri("/actuator/env")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    @Tag("security")
    fun `API8 prometheus is served on the management port only`() {
        val mgmt = RestTestClient.bindToServer().baseUrl("http://localhost:$managementPort").build()
        val body =
            mgmt
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseBody
        assertThat(body).contains("# TYPE")
        // Not exposed: 404 from actuator or 401 from the deny-by-default chain – never served.
        mgmt
            .get()
            .uri("/actuator/env")
            .exchange()
            .expectStatus()
            .value { assertThat(it).isIn(401, 404) }
    }

    @Test
    fun `flyway applies V1 on startup`() {
        val success =
            jdbc
                .sql("select success from flyway_schema_history where version = '1'")
                .query(Boolean::class.java)
                .single()
        assertThat(success).isTrue()
    }

    @Test
    fun `V1 creates the schema`() {
        val tables =
            jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String::class.java)
                .list()
        assertThat(tables).contains(
            "tenant_settings",
            "subject",
            "template_version",
            "verification",
            "idempotency_key",
        )
    }
}
