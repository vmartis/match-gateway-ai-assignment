package com.vmartis.matchgateway.gateway.shared.api

import com.vmartis.matchgateway.common.template.InvalidTemplateException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The one @RestControllerAdvice (CLAUDE.md §3). Spring's built-in ResponseEntityExceptionHandler maps every MVC exception
 * to its status; we only replace the body with the constant problem for that status. Exceptions are never logged with
 * their message (it may contain request data).
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val status = if (ex.hasCause<PayloadTooLargeException>()) HttpStatus.PAYLOAD_TOO_LARGE else statusCode
        // The client accepts no JSON representation, so there is no body we could send.
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) return ResponseEntity.status(status).headers(headers).build()
        val problem = Problems.of(status)
        return ResponseEntity
            .status(problem.status)
            .headers(headers)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem)
    }

    @ExceptionHandler(InvalidTemplateException::class)
    fun invalidTemplate(): ResponseEntity<Any> = Problems.response(HttpStatus.BAD_REQUEST)

    // StrictHttpFirewall validates header values lazily: a rejected value surfaces inside the handler, not the filter
    // chain. It is a client error, not a 500 with an ERROR log line (security review step 9).
    @ExceptionHandler(RequestRejectedException::class)
    fun requestRejected(): ResponseEntity<Any> = Problems.response(HttpStatus.BAD_REQUEST)

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<Any> {
        log.error("Unhandled exception {}", ex.javaClass.name)
        return Problems.response(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = generateSequence(this) { it.cause }.any { it is T }

    private companion object {
        val log = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)
    }
}
