package com.vmartis.matchgateway.gateway.auth.infra

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * 401 for a missing or invalid token: plain `WWW-Authenticate: Bearer` and a constant problem body.
 * Never reveals why a token was rejected (no `error_description`, ADR-004, AM-14).
 */
@Component
class ProblemAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        // Bytes, not getWriter(): the writer makes Tomcat append an ISO-8859-1 charset; JSON is UTF-8.
        response.outputStream.write(BODY.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val BODY =
            """{"type":"urn:matchgw:problem:unauthorized","title":"Unauthorized","status":401,""" +
                """"detail":"A valid bearer token is required."}"""
    }
}
