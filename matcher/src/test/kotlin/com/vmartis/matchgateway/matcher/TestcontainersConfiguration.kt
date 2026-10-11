package com.vmartis.matchgateway.matcher

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17"))

    // Pinned by digest (D-28). Non-default admin credentials so a leaked "guest" or password in logs is detectable.
    @Bean
    @ServiceConnection
    fun rabbitContainer(): RabbitMQContainer =
        RabbitMQContainer(
            DockerImageName
                .parse("rabbitmq:4.3@sha256:42b190dffdd0cdb656f989f5404c30e1049d6b7d9c987089669b1905e5e3a1a6")
                .asCompatibleSubstituteFor("rabbitmq"),
        ).withAdminUser("matchgw-test").withAdminPassword("Zq7-broker-secret-for-tests")
}
