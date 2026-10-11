package com.vmartis.matchgateway.gateway.shared.api

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * RFC 9457 problem body with constant content per status. A plain body type on purpose: for a returned
 * `ProblemDetail`, Spring MVC fills a missing `instance` with the request path, which would echo input
 * (ARCHITECTURE "API": bodies never echo input or path).
 */
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
)

object Problems {
    private val DETAILS =
        mapOf(
            400 to ("invalid-request" to "The request is invalid."),
            404 to ("not-found" to "The resource was not found."),
            405 to ("method-not-allowed" to "The method is not allowed for this resource."),
            406 to ("not-acceptable" to "The requested representation is not available."),
            413 to ("payload-too-large" to "The request body is too large."),
            415 to ("unsupported-media-type" to "The content type is not supported."),
            422 to ("idempotency-key-reused" to "The Idempotency-Key was already used with a different request."),
            500 to ("internal" to "An unexpected error occurred."),
        )

    /** Keeps the real status; unknown codes get a generic client/server problem type, never a different status. */
    fun of(status: HttpStatusCode): Problem {
        val code = status.value()
        val (slug, detail) =
            DETAILS[code] ?: if (status.is4xxClientError) ("client-error" to "The request cannot be processed.") else DETAILS.getValue(500)
        val title = HttpStatus.resolve(code)?.reasonPhrase ?: "Error"
        return Problem("urn:matchgw:problem:$slug", title, code, detail)
    }

    fun response(status: HttpStatusCode): ResponseEntity<Any> {
        val problem = of(status)
        return ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
}
