package com.vmartis.matchgateway.gateway.security

import java.nio.file.Files
import java.security.SecureRandom

/** Test-only idempotency HMAC key: 32 random bytes written once per JVM to a temp file (`app.idempotency.hmac-key-location`). */
object TestHmacKey {
    val bytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    val location: String =
        Files
            .createTempFile("idempotency-hmac-test", ".key")
            .also {
                Files.write(it, bytes)
                it.toFile().deleteOnExit()
            }.toUri()
            .toString()
}
