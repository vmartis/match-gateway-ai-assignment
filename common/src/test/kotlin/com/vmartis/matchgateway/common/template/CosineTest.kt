package com.vmartis.matchgateway.common.template

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AC-3: cosine similarity computed in float64 over 128-dimension vectors. */
class CosineTest {
    private fun reference(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun unit(
        random: Random,
        dims: Int = 128,
    ): FloatArray {
        val v = DoubleArray(dims) { random.nextGaussian() }
        val norm = sqrt(v.sumOf { it * it })
        return FloatArray(dims) { (v[it] / norm).toFloat() }
    }

    private fun axis(index: Int): FloatArray = FloatArray(128).also { it[index] = 1f }

    @Test
    fun `identical vectors have similarity 1`() {
        val v = unit(Random(1))
        assertEquals(1.0, Cosine.similarity(v, v), 1e-9)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        assertEquals(0.0, Cosine.similarity(axis(0), axis(1)), 1e-12)
    }

    @Test
    fun `opposite vectors have similarity minus 1`() {
        val v = unit(Random(2))
        val neg = FloatArray(128) { -v[it] }
        assertEquals(-1.0, Cosine.similarity(v, neg), 1e-9)
    }

    @Test
    fun `random normalised vectors agree with the float64 reference within 1e-9`() {
        val random = Random(42)
        repeat(200) {
            val a = unit(random)
            val b = unit(random)
            val diff = abs(Cosine.similarity(a, b) - reference(a, b))
            assertTrue(diff <= 1e-9, "difference $diff")
        }
    }

    @Test
    fun `zero vector is rejected`() {
        assertFailsWith<IllegalArgumentException> { Cosine.similarity(FloatArray(128), axis(0)) }
    }

    @Test
    fun `NaN and infinite components are rejected`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Cosine.similarity(axis(1).also { it[0] = bad }, axis(1)) }
        }
    }

    @Test
    fun `length mismatch is rejected`() {
        assertFailsWith<IllegalArgumentException> { Cosine.similarity(FloatArray(128), FloatArray(127)) }
        assertFailsWith<IllegalArgumentException> { Cosine.similarity(FloatArray(127), FloatArray(128)) }
    }

    @Test
    fun `vectors that are not 128 long are rejected even when equal in length`() {
        assertFailsWith<IllegalArgumentException> { Cosine.similarity(FloatArray(64) { 1f }, FloatArray(64) { 1f }) }
    }
}
