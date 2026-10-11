package com.vmartis.matchgateway.matcher.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket

/** AC-2: matcher readiness includes the broker; with the broker unreachable the matcher is not ready. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BrokerDownIT.PostgresOnly::class)
class BrokerDownIT {
    @LocalServerPort
    var serverPort: Int = 0

    private val client: RestTestClient by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$serverPort").build() }

    @Test
    fun `AC-2 readiness is 503 DOWN when the broker is unreachable`() {
        val body =
            client
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .returnResult(String::class.java)
                .responseBody

        assertThat(body).isEqualTo("""{"status":"DOWN"}""")
    }

    @Test
    @Tag("security")
    fun `API8 public health is 503 DOWN with status only and no host port or credentials when the broker is unreachable`() {
        val body =
            client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .returnResult(String::class.java)
                .responseBody

        val json = JsonMapper.builder().build().readTree(body)
        assertThat(json["status"].asString()).isEqualTo("DOWN")
        assertThat(json.propertyNames().asSequence().toSet()).isSubsetOf("status", "groups")
        assertThat(body)
            .doesNotContain("components")
            .doesNotContain("details")
            .doesNotContain("rabbit")
            .doesNotContain("localhost")
            .doesNotContain("guest")
            .doesNotContain(closedPort.toString())
    }

    @Test
    fun `AC-2 liveness stays UP when the broker is unreachable`() {
        client
            .get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus()
            .isOk
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PostgresOnly {
        @Bean
        @ServiceConnection
        fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    companion object {
        val closedPort: Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.rabbitmq.host") { "localhost" }
            registry.add("spring.rabbitmq.port") { closedPort }
            registry.add("spring.rabbitmq.username") { "matchgw-test" }
            registry.add("spring.rabbitmq.password") { "unused" }
            registry.add("spring.rabbitmq.connection-timeout") { "1s" }
        }
    }
}
