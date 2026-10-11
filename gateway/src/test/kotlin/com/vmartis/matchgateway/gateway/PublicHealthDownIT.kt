package com.vmartis.matchgateway.gateway

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient

@Import(PublicHealthDownIT.ForcedDown::class)
class PublicHealthDownIT : GatewayIntegrationTest() {
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
