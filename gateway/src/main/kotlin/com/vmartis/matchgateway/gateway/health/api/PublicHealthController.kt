package com.vmartis.matchgateway.gateway.health.api

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.contributor.Status
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /actuator/health on the public API port (D-19). Actuator itself runs on the internal management
 * port (D-6) and Boot only allows single-segment additional health paths, so this delegates to the
 * health endpoint and returns the aggregate status only – never component details.
 */
@RestController
class PublicHealthController(
    private val healthEndpoint: HealthEndpoint,
) {
    @GetMapping("/actuator/health")
    fun health(): ResponseEntity<HealthResponse> {
        val status = healthEndpoint.health().status
        val httpStatus = if (status == Status.UP) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(httpStatus).body(HealthResponse(status.code))
    }

    data class HealthResponse(
        val status: String,
    )
}
