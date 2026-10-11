package com.vmartis.matchgateway.gateway.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Transport level hardening through the real server: bodies are not consumed before authentication
 * (form content filter and multipart parsing disabled) and container-level rejections leak nothing.
 */
@Tag("security")
class TransportHardeningIT : GatewayIntegrationTest() {
    @LocalServerPort
    var port: Int = 0

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), port).also { it.soTimeout = 5_000 }

    private fun readStatusLine(socket: Socket): String {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1).trim()
    }

    private fun readAll(socket: Socket): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val input = socket.getInputStream()
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: SocketTimeoutException) {
            // whatever arrived before the timeout is the response
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    @ParameterizedTest(name = "API4 anonymous {0} {1} with an unfinished chunked form body is 401 without reading the body")
    @CsvSource("PUT,/v1/tenant/threshold", "PATCH,/v1/subjects/x", "DELETE,/v1/subjects/x", "POST,/v1/subjects/x/enrollment")
    fun `API4 anonymous form body is not read before authentication`(
        method: String,
        path: String,
    ) {
        connect().use { socket ->
            val chunk = "a".repeat(1024)
            val request =
                "$method $path HTTP/1.1\r\nHost: localhost:$port\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "400\r\n$chunk\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()

            // The terminating chunk is never sent: a server that tried to read or parse the body would block here
            // until the read timeout instead of answering. The answer must come from the security chain alone.
            val statusLine = readStatusLine(socket)

            assertThat(statusLine).isEqualTo("HTTP/1.1 401")
        }
    }

    @Test
    fun `API4 anonymous 200 KB chunked form body on the threshold endpoint is 401`() {
        val payload = ByteArray(200_000) { 'a'.code.toByte() }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v1/tenant/threshold"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofInputStream { payload.inputStream() })
                .build()

        val response =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(-1)
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).isEqualTo("Bearer")
    }

    @Test
    fun `API4 anonymous health request with a 200 KB multipart body is rejected by the size limit without parsing`() {
        val body = "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n${"a".repeat(200_000)}\r\n--b--\r\n"
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/actuator/health"))
                .header("Content-Type", "multipart/form-data; boundary=b")
                .method("GET", HttpRequest.BodyPublishers.ofString(body))
                .build()

        val response =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())

        // The global 8 KB body limit answers before any parsing (not 400 from a multipart parser, not 500).
        assertThat(response.statusCode()).isEqualTo(413)
        assertThat(response.body()).contains("urn:matchgw:problem:payload-too-large").doesNotContain("aaaa")
    }

    @Test
    fun `API4 health request with an invalid multipart boundary is a 415 and never a 400 or 500`() {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/actuator/health"))
                .header("Content-Type", "multipart/form-data; boundary=")
                .method("GET", HttpRequest.BodyPublishers.ofString("not a multipart body"))
                .build()

        val response =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())

        // Multipart is not a supported media type: the boundary is never interpreted, so no parser error (400/500).
        assertThat(response.statusCode()).isEqualTo(415)
        assertThat(response.body()).contains("urn:matchgw:problem:unsupported-media-type")
    }

    @Test
    fun `A10 malformed request line is a 400 without stack trace or echoed input`() {
        connect().use { socket ->
            socket.getOutputStream().write("BOGUS( /x HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()

            val response = readAll(socket)

            assertThat(response).startsWith("HTTP/1.1 400")
            assertNoLeak(response, "BOGUS")
        }
    }

    @Test
    fun `API8 oversized header line is a 400 without stack trace or echoed input`() {
        connect().use { socket ->
            val request = "GET /x HTTP/1.1\r\nHost: localhost\r\nX-Big: ${"q".repeat(9 * 1024)}\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()

            val response = readAll(socket)

            assertThat(response).startsWith("HTTP/1.1 400")
            assertNoLeak(response, "qqqq")
        }
    }

    private fun assertNoLeak(
        response: String,
        echoed: String,
    ) {
        val body = response.substringAfter("\r\n\r\n", "")
        assertThat(body.lowercase()).doesNotContain("throwable").doesNotContain("exception")
        assertThat(body).doesNotContain("at org.").doesNotContain(echoed)
        assertThat(response.substringBefore("\r\n\r\n")).doesNotContain(echoed)
    }
}
