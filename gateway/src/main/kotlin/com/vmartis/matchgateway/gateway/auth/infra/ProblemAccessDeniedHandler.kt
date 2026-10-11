package com.vmartis.matchgateway.gateway.auth.infra

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** 403 for a valid token without the required scope: constant problem body, never which scope was missing. */
@Component
class ProblemAccessDeniedHandler : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.outputStream.write(BODY.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val BODY =
            """{"type":"urn:matchgw:problem:forbidden","title":"Forbidden","status":403,""" +
                """"detail":"The token does not grant access to this operation."}"""
    }
}
