package com.vmartis.matchgateway.gateway.subject.api

import com.vmartis.matchgateway.common.template.TemplateCodec
import com.vmartis.matchgateway.gateway.auth.api.tenantId
import com.vmartis.matchgateway.gateway.subject.infra.SubjectStore
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

const val SUBJECT_ID_PATTERN = "^[A-Za-z0-9._-]{1,64}$"

data class EnrollmentRequest(
    @field:NotNull val template: String?,
) {
    // Spring logs the bound request at DEBUG via toString(): template data must never reach logs (AC-41).
    override fun toString() = "EnrollmentRequest(template=<redacted>)"
}

data class EnrollmentResponse(
    val subjectId: String,
    val version: Int,
)

/** POST /v1/subjects/{subjectId}/enrollment – scope `enroll` is enforced in the filter chain (D-18). */
@RestController
class EnrollmentController(
    private val subjects: SubjectStore,
) {
    // produces: an unacceptable Accept header fails with 406 before the handler runs, never after the enrolment is stored.
    @PostMapping(
        "/v1/subjects/{subjectId}/enrollment",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun enrol(
        @PathVariable @Pattern(regexp = SUBJECT_ID_PATTERN) subjectId: String,
        @Valid @RequestBody request: EnrollmentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<EnrollmentResponse> {
        val template = TemplateCodec.decode(checkNotNull(request.template))
        val version = subjects.enrol(jwt.tenantId(), subjectId, template)
        return ResponseEntity.status(HttpStatus.CREATED).body(EnrollmentResponse(subjectId, version))
    }
}
