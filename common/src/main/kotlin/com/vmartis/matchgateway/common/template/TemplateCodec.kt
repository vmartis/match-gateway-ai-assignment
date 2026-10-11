package com.vmartis.matchgateway.common.template

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/** Thrown for any invalid template; the message never contains the input (biometric data must not leak, BR-12). */
class InvalidTemplateException : RuntimeException("invalid template")

/**
 * Template wire format (brief L9, L35): standard base64 of 128 little-endian float32 = 512 bytes, all finite,
 * L2 norm in [0.99, 1.01]. Strict: exactly 684 characters of the standard alphabet with padding (AM-13).
 */
object TemplateCodec {
    const val ENCODED_LENGTH = 684
    const val BYTES = 512
    const val DIMENSIONS = 128
    private const val MIN_NORM = 0.99
    private const val MAX_NORM = 1.01
    private val STANDARD_PADDED = Regex("^[A-Za-z0-9+/]{683}=$")

    /** Returns the validated 512 raw bytes. */
    fun decode(base64: String): ByteArray {
        if (base64.length != ENCODED_LENGTH || !STANDARD_PADDED.matches(base64)) throw InvalidTemplateException()
        val bytes =
            try {
                Base64.getDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                throw InvalidTemplateException()
            }
        // Canonical only: unused low bits of the last character must be zero, so one template has one encoding.
        if (bytes.size != BYTES || Base64.getEncoder().encodeToString(bytes) != base64) throw InvalidTemplateException()
        var sumOfSquares = 0.0
        for (value in toFloats(bytes)) {
            if (!value.isFinite()) throw InvalidTemplateException()
            sumOfSquares += value.toDouble() * value.toDouble()
        }
        val norm = sqrt(sumOfSquares)
        if (norm < MIN_NORM || norm > MAX_NORM) throw InvalidTemplateException()
        return bytes
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        require(bytes.size == BYTES) { "template must be $BYTES bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIMENSIONS) { buffer.getFloat() }
    }
}
