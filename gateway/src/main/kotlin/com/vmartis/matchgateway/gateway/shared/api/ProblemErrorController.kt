package com.vmartis.matchgateway.gateway.shared.api

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Container-level errors (firewall rejections, errors outside MVC) use Boot's documented extension point – an
 * ErrorController replaces BasicErrorController – so they get the same constant problem body instead of Boot's JSON with
 * the request path (security review step 2, F-3).
 */
@RestController
class ProblemErrorController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<Any> {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500
        return Problems.response(HttpStatusCode.valueOf(status))
    }
}
