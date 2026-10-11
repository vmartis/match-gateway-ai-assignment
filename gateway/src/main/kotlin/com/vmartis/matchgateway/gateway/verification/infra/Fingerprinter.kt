package com.vmartis.matchgateway.gateway.verification.infra

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed hash of a verification request (D-10, ADR-008): HmacSHA256 over tenant ‖ 0x00 ‖ subjectId ‖ 0x00 ‖ probe bytes.
 * Only this is stored for idempotency, never the probe (AC-23). The JDK Mac is not thread-safe, so one per call.
 */
class Fingerprinter(
    key: ByteArray,
) {
    init {
        require(key.size == KEY_BYTES) { "idempotency HMAC key must be $KEY_BYTES bytes" }
    }

    private val secret = SecretKeySpec(key, ALGORITHM)

    fun fingerprint(
        tenantId: String,
        subjectId: String,
        probe: ByteArray,
    ): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(secret)
            update(tenantId.toByteArray(Charsets.UTF_8))
            update(SEPARATOR)
            update(subjectId.toByteArray(Charsets.UTF_8))
            update(SEPARATOR)
            doFinal(probe)
        }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val KEY_BYTES = 32
        const val SEPARATOR: Byte = 0
    }
}
