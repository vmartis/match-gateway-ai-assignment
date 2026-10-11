package com.vmartis.matchgateway.matcher

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(TestcontainersConfiguration::class)
class MatcherSkeletonIT {
    @Autowired
    lateinit var jdbc: JdbcClient

    // Production shape: actuator shares the matcher server port (8081), no separate management server.
    @LocalServerPort
    var serverPort: Int = 0

    @Test
    fun `matcher starts against an empty database without migrating`() {
        val count =
            jdbc
                .sql(
                    "select count(*) from information_schema.tables where table_name = 'flyway_schema_history'",
                ).query(Long::class.java)
                .single()
        assertThat(count).isZero()
    }

    @Test
    fun `server port serves health`() {
        val mgmt = RestTestClient.bindToServer().baseUrl("http://localhost:$serverPort").build()
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
}
