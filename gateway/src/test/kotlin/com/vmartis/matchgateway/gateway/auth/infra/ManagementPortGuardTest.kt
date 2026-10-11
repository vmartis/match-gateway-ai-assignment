package com.vmartis.matchgateway.gateway.auth.infra

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("security")
class ManagementPortGuardTest {
    @Test
    fun `API8 separate management port is accepted`() {
        assertThatCode { requireSeparateManagementPort(serverPort = 8080, managementPort = 8081) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `API8 random management port is accepted`() {
        assertThatCode { requireSeparateManagementPort(serverPort = 0, managementPort = 0) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `API8 management port equal to the public port fails startup`() {
        assertThatThrownBy { requireSeparateManagementPort(serverPort = 8080, managementPort = 8080) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("management.server.port")
    }

    @Test
    fun `API8 missing management port fails startup`() {
        assertThatThrownBy { requireSeparateManagementPort(serverPort = 8080, managementPort = null) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
