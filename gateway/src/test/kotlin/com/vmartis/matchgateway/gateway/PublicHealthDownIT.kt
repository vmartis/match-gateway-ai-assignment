package com.vmartis.matchgateway.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class, PublicHealthDownIT.ForcedDown::class)
class PublicHealthDownIT {
    @Autowired
    lateinit var client: RestTestClient

    @Test
    fun `public health reports 503 with status only when a component is down`() {
        val body =
            client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .returnResult(String::class.java)
                .responseBody
        assertThat(body).contains("\"status\":\"DOWN\"")
        assertThat(body).doesNotContain("forcedDown").doesNotContain("components")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ForcedDown {
        @Bean
        fun forcedDown(): HealthIndicator = HealthIndicator { Health.down().build() }
    }
}
