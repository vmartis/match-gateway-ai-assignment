package com.vmartis.matchgateway.gateway.subject

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Builds enrolment templates for tests: little-endian float32 x 128, standard base64 with padding. */
object TestTemplates {
    fun floats(
        value: Float,
        index: Int = 0,
    ): FloatArray = FloatArray(128).also { it[index] = value }

    fun bytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun base64(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    /** Unit vector with its single non-zero component at [index]. */
    fun unitBytes(index: Int = 0): ByteArray = bytes(floats(1f, index))

    fun unit(index: Int = 0): String = base64(unitBytes(index))

    /** Vector whose only non-zero component is [value]; its L2 norm is |value|. */
    fun single(value: Float): String = base64(bytes(floats(value)))

    /** Unit vector with one component replaced by [value] (NaN, infinity, ...). */
    fun withComponent(value: Float): String = base64(bytes(floats(1f).also { it[1] = value }))

    /** [size] bytes: the unit vector truncated or zero padded. */
    fun ofSize(size: Int): String = base64(unitBytes().copyOf(size))
}
