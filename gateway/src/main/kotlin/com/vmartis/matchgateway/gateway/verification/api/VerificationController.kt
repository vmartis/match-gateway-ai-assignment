package com.vmartis.matchgateway.gateway.verification.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.vmartis.matchgateway.common.template.TemplateCodec
import com.vmartis.matchgateway.gateway.auth.api.tenantId
import com.vmartis.matchgateway.gateway.subject.api.SUBJECT_ID_PATTERN
import com.vmartis.matchgateway.gateway.verification.domain.IdempotencyOutcome
import com.vmartis.matchgateway.gateway.verification.infra.Fingerprinter
import com.vmartis.matchgateway.gateway.verification.infra.VerificationStore
import com.vmartis.matchgateway.gateway.verification.infra.VerificationView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Printable ASCII without space, 1..64 characters (brief L29: required, max 64). */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

const val IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7E]{1,64}$"

data class VerificationRequest(
    @field:NotNull @field:Pattern(regexp = SUBJECT_ID_PATTERN) val subjectId: String?,
    @field:NotNull val probeTemplate: String?,
) {
    // Spring logs the bound request at DEBUG via toString(): template data must never reach logs (AC-41).
    override fun toString() = "VerificationRequest(subjectId=$subjectId, probeTemplate=<redacted>)"
}

data class VerificationAccepted(
    val verificationId: UUID,
    val status: String,
)

data class VerificationResult(
    val verificationId: UUID,
    val subjectId: String,
    val status: String,
    val score: Double?,
    val threshold: Double?,
    val decidedAt: Instant?,
    // Present only for FAILED (D-9); the other fields are always present, null while undecided.
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val reason: String?,
)

/** Scope `verify` is enforced in the filter chain (D-18); every read and write is scoped to the JWT tenant (INV-4). */
@RestController
class VerificationController(
    private val verifications: VerificationStore,
    private val fingerprinter: Fingerprinter,
) {
    @PostMapping(
        "/v1/verifications",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun submit(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN) idempotencyKey: String,
        @Valid @RequestBody request: VerificationRequest,
        @AuthenticationPrincipal jwt: Jwt,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<VerificationAccepted> {
        // Spring joins repeated headers with ","; exactly one key header is allowed so a key always has one meaning.
        if (servletRequest.getHeaders(IDEMPOTENCY_KEY_HEADER).toList().size != 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val probe = TemplateCodec.decode(checkNotNull(request.probeTemplate))
        val tenantId = jwt.tenantId()
        val subjectId = checkNotNull(request.subjectId)
        val fingerprint = fingerprinter.fingerprint(tenantId, subjectId, probe)
        return when (val outcome = verifications.submit(tenantId, idempotencyKey, subjectId, probe, fingerprint)) {
            is IdempotencyOutcome.Created -> verificationResponse(HttpStatus.ACCEPTED, outcome.id, "PENDING")
            // Replay (AM-12): 202 while PENDING, 200 once decided, always the original id (D-10).
            is IdempotencyOutcome.Replayed ->
                verificationResponse(if (outcome.status == "PENDING") HttpStatus.ACCEPTED else HttpStatus.OK, outcome.id, outcome.status)
            IdempotencyOutcome.KeyReused -> throw ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT)
        }
    }

    private fun verificationResponse(
        status: HttpStatus,
        id: UUID,
        verificationStatus: String,
    ) = ResponseEntity
        .status(status)
        .location(URI.create("/v1/verifications/$id"))
        .body(VerificationAccepted(id, verificationStatus))

    @GetMapping("/v1/verifications/{verificationId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(
        @PathVariable verificationId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): VerificationResult {
        // A malformed or non-canonical id is indistinguishable from an unknown one (A-S2): same 404, no 400 oracle.
        val id = canonicalUuid(verificationId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val view = verifications.find(jwt.tenantId(), id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return view.toResult()
    }

    /** UUID.fromString is lenient ("1-1-1-1-1" parses); only the canonical 36-character form is accepted. */
    private fun canonicalUuid(value: String): UUID? =
        try {
            UUID.fromString(value).takeIf { it.toString() == value.lowercase() }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun VerificationView.toResult() = VerificationResult(id, subjectId, status, score, threshold, decidedAt, reason)
}
