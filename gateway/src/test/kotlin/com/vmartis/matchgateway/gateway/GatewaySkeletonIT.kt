package com.vmartis.matchgateway.gateway

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient

class GatewaySkeletonIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

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
        assertThat(tables).contains("tenant_settings", "subject", "template_version", "verification", "idempotency_key")
    }
}
