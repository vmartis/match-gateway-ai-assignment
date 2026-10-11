package com.vmartis.matchgateway.gateway.tenant.api

import com.vmartis.matchgateway.gateway.auth.api.tenantId
import com.vmartis.matchgateway.gateway.tenant.infra.TenantSettingsStore
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class ThresholdRequest(
    @field:NotNull @field:DecimalMin("0.0") @field:DecimalMax("1.0") val threshold: Double?,
)

data class ThresholdResponse(
    val threshold: Double,
)

/** PUT /v1/tenant/threshold – scope `admin` is enforced in the filter chain (D-18); the tenant comes from the JWT only. */
@RestController
class ThresholdController(
    private val settings: TenantSettingsStore,
) {
    @PutMapping("/v1/tenant/threshold", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun setThreshold(
        @Valid @RequestBody request: ThresholdRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ThresholdResponse = ThresholdResponse(settings.setThreshold(jwt.tenantId(), checkNotNull(request.threshold)))
}
