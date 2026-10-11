package com.vmartis.matchgateway.gateway.shared.api

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.io.IOException

/** Thrown when a body without (or with a lying) Content-Length exceeds the limit while being read. */
class PayloadTooLargeException : IOException("payload too large")

/**
 * Request bodies above 8 KB are rejected with 413 (NFR-9, AC-42) – a template is 684 characters. Spring/Tomcat have no
 * built-in limit for JSON bodies. Registered as a plain servlet filter, so it runs after the security filter chain:
 * unauthenticated bodies are rejected with 401 before they are read (AM-23).
 */
@Component
class BodySizeLimitFilter(
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > MAX_BODY_BYTES) {
            val problem = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE)
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = "application/problem+json"
            response.outputStream.write(jsonMapper.writeValueAsBytes(problem))
            return
        }
        filterChain.doFilter(LimitedRequest(request), response)
    }

    private class LimitedRequest(
        request: HttpServletRequest,
    ) : HttpServletRequestWrapper(request) {
        private val stream by lazy { LimitedInputStream(request.inputStream) }

        override fun getInputStream(): ServletInputStream = stream
    }

    private class LimitedInputStream(
        private val delegate: ServletInputStream,
    ) : ServletInputStream() {
        private var read = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0 && ++read > MAX_BODY_BYTES) throw PayloadTooLargeException()
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) {
                read += n
                if (read > MAX_BODY_BYTES) throw PayloadTooLargeException()
            }
            return n
        }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)
    }

    companion object {
        const val MAX_BODY_BYTES = 8192L
    }
}
