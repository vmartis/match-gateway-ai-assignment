package com.vmartis.matchgateway.gateway.subject.api

import com.vmartis.matchgateway.gateway.auth.api.tenantId
import com.vmartis.matchgateway.gateway.subject.infra.SubjectStore
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** DELETE /v1/subjects/{subjectId} – right to erasure (ADR-007); scope `admin` is enforced in the filter chain (D-18). */
@RestController
class SubjectController(
    private val subjects: SubjectStore,
) {
    @DeleteMapping("/v1/subjects/{subjectId}")
    fun erase(
        // A malformed id is a 400 like on enrolment (D-26); unknown and foreign subjects share one 404 (AC-37, AC-48).
        @PathVariable @Pattern(regexp = SUBJECT_ID_PATTERN) subjectId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Void> {
        if (!subjects.erase(jwt.tenantId(), subjectId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.noContent().build()
    }
}
