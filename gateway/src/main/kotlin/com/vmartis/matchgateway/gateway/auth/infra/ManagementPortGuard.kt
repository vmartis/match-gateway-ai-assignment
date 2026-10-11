package com.vmartis.matchgateway.gateway.auth.infra

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fails startup if actuator would share the public port. `SecurityConfig` permits health + prometheus via
 * `EndpointRequest`, which only stays off the public port while the management server runs on its own port (D-6).
 */
@Component
class ManagementPortGuard(
    environment: Environment,
) {
    init {
        requireSeparateManagementPort(
            serverPort = environment.getProperty("server.port", Int::class.java),
            managementPort = environment.getProperty("management.server.port", Int::class.java),
        )
    }
}

/** `0` means a random port (tests), which can never collide with the server port. */
internal fun requireSeparateManagementPort(
    serverPort: Int?,
    managementPort: Int?,
) {
    check(managementPort != null && (managementPort == 0 || managementPort != serverPort)) {
        "management.server.port must be set and differ from server.port (got server=$serverPort, management=$managementPort)"
    }
}
