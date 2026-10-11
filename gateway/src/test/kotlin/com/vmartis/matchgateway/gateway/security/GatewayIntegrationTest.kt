package com.vmartis.matchgateway.gateway.security

import com.vmartis.matchgateway.gateway.TestcontainersConfiguration
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
abstract class GatewayIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwtProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.jwt.public-key-location") { JwtTestTokens.publicKeyLocation }
        }
    }
}
