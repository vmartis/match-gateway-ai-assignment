package com.vmartis.matchgateway.common.template

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** AC-11 / AM-13: strict base64, exactly 128 finite little-endian float32, L2 norm within [0.99, 1.01]. */
class TemplateCodecTest {
    private fun bytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun single(
        value: Float,
        index: Int = 0,
    ): FloatArray = FloatArray(128).also { it[index] = value }

    private fun encode(floats: FloatArray): String = Base64.getEncoder().encodeToString(bytes(floats))

    private fun assertRejected(input: String) {
        val e = assertFailsWith<InvalidTemplateException> { TemplateCodec.decode(input) }
        // Every string contains "", so the echo check only makes sense for non-empty input.
        if (input.isNotEmpty()) assertFalse((e.message ?: "").contains(input), "exception message must not echo the input")
    }

    @Test
    fun `constants describe a 512 byte 128 dimension template encoded in 684 characters`() {
        assertEquals(684, TemplateCodec.ENCODED_LENGTH)
        assertEquals(512, TemplateCodec.BYTES)
        assertEquals(128, TemplateCodec.DIMENSIONS)
    }

    @Test
    fun `a valid unit vector decodes to its 512 raw bytes`() {
        val floats = single(1f, 7)
        val encoded = encode(floats)
        assertEquals(684, encoded.length)

        assertContentEquals(bytes(floats), TemplateCodec.decode(encoded))
    }

    @Test
    fun `a spread unit vector round trips through decode and toFloats`() {
        val v = 1f / Math.sqrt(128.0).toFloat()
        val floats = FloatArray(128) { if (it % 2 == 0) v else -v }

        val decoded = TemplateCodec.decode(encode(floats))

        assertContentEquals(floats, TemplateCodec.toFloats(decoded))
    }

    @Test
    fun `toFloats reads little endian float32`() {
        val raw = ByteArray(512)
        // 1.0f little endian = 00 00 80 3F
        raw[2] = 0x80.toByte()
        raw[3] = 0x3F

        val floats = TemplateCodec.toFloats(raw)

        assertEquals(128, floats.size)
        assertEquals(1f, floats[0])
        assertEquals(0f, floats[1])
    }

    @Test
    fun `toFloats requires exactly 512 bytes`() {
        assertFailsWith<IllegalArgumentException> { TemplateCodec.toFloats(ByteArray(511)) }
        assertFailsWith<IllegalArgumentException> { TemplateCodec.toFloats(ByteArray(513)) }
    }

    @Test
    fun `511 bytes are rejected`() {
        assertRejected(Base64.getEncoder().encodeToString(bytes(single(1f)).copyOf(511)))
    }

    @Test
    fun `513 bytes are rejected even though they also encode to 684 characters`() {
        val encoded = Base64.getEncoder().encodeToString(bytes(single(1f)).copyOf(513))
        assertEquals(684, encoded.length)

        assertRejected(encoded)
    }

    @Test
    fun `empty input is rejected`() {
        assertRejected("")
    }

    @Test
    fun `URL safe alphabet is rejected`() {
        // 0xFB 0xFF encodes to "+/8" in the standard alphabet and "-_8" in the URL-safe one.
        val raw =
            bytes(single(1f)).also {
                it[4] = 0xFB.toByte()
                it[5] = 0xFF.toByte()
            }
        val urlSafe =
            java.util.Base64
                .getUrlEncoder()
                .encodeToString(raw)
        assertEquals(684, urlSafe.length)
        assertEquals(true, urlSafe.contains('-') || urlSafe.contains('_'))

        assertRejected(urlSafe)
    }

    @Test
    fun `embedded whitespace is rejected`() {
        val ok = encode(single(1f))
        assertRejected(ok.substring(0, 100) + " " + ok.substring(101))
        assertRejected(ok.substring(0, 100) + "\n" + ok.substring(101))
        assertRejected(ok + "\n")
        assertRejected(" " + ok.substring(1))
    }

    @Test
    fun `missing padding with 683 characters is rejected`() {
        val ok = encode(single(1f))
        // 512 bytes = 3 * 170 + 2, so standard base64 ends with exactly one "=".
        assertEquals(true, ok.endsWith("=") && !ok.endsWith("=="))

        assertRejected(ok.dropLast(1))
    }

    @Test
    fun `missing all padding is rejected`() {
        assertRejected(encode(single(1f)).trimEnd('='))
    }

    @Test
    fun `non canonical base64 with non zero unused bits in the last data character is rejected`() {
        val ok = encode(single(1f))
        assertEquals(684, ok.length)
        assertEquals('=', ok.last())
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        // 512 bytes leave 2 unused bits in the last data character (index 682); canonical encoding has them zero.
        val last = ok[682]
        assertEquals(0, alphabet.indexOf(last) and 0b11)
        val tampered = ok.substring(0, 682) + alphabet[alphabet.indexOf(last) or 1] + "="
        // The JDK lenient decoder still yields the very same bytes, so only a canonical check can reject it.
        assertContentEquals(Base64.getDecoder().decode(ok), Base64.getDecoder().decode(tampered))
        assertEquals(ok.length, tampered.length)

        assertRejected(tampered)
    }

    @Test
    fun `every non zero unused bit pattern in the last data character is rejected and the canonical form accepted`() {
        val ok = encode(single(1f))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val base = alphabet.indexOf(ok[682])

        (1..3).forEach { bits -> assertRejected(ok.substring(0, 682) + alphabet[base or bits] + "=") }
        assertContentEquals(bytes(single(1f)), TemplateCodec.decode(ok))
    }

    @Test
    fun `NaN is rejected`() {
        assertRejected(encode(single(Float.NaN, 5).also { it[0] = 1f }))
    }

    @Test
    fun `positive infinity is rejected`() {
        assertRejected(encode(single(Float.POSITIVE_INFINITY, 5).also { it[0] = 1f }))
    }

    @Test
    fun `negative infinity is rejected`() {
        assertRejected(encode(single(Float.NEGATIVE_INFINITY, 5).also { it[0] = 1f }))
    }

    @Test
    fun `norm 0_98 is rejected`() {
        assertRejected(encode(single(0.98f)))
    }

    @Test
    fun `norm 1_02 is rejected`() {
        assertRejected(encode(single(1.02f)))
    }

    @Test
    fun `lower boundary norm 0_99 is accepted`() {
        assertContentEquals(bytes(single(0.99f)), TemplateCodec.decode(encode(single(0.99f))))
    }

    @Test
    fun `upper boundary norm 1_01 is accepted`() {
        assertContentEquals(bytes(single(1.01f)), TemplateCodec.decode(encode(single(1.01f))))
    }

    @Test
    fun `negative component with unit norm is accepted`() {
        assertContentEquals(bytes(single(-1f, 127)), TemplateCodec.decode(encode(single(-1f, 127))))
    }

    @Test
    fun `all zero vector is rejected`() {
        assertRejected(encode(FloatArray(128)))
    }

    @Test
    fun `non base64 characters of the right length are rejected`() {
        assertRejected("!".repeat(684))
    }

    @Test
    fun `the exception message is fixed and does not leak the input or its length`() {
        val a = assertFailsWith<InvalidTemplateException> { TemplateCodec.decode("abc") }
        val b = assertFailsWith<InvalidTemplateException> { TemplateCodec.decode(encode(single(Float.NaN))) }

        assertEquals(a.message, b.message)
        assertFalse((a.message ?: "").contains("abc"))
    }
}
