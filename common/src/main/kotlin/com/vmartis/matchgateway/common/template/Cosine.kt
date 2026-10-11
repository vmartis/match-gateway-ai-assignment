package com.vmartis.matchgateway.common.template

import kotlin.math.sqrt

/** Cosine similarity in double precision (brief L44); score = dot / (|a| * |b|). */
object Cosine {
    fun similarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        require(a.size == TemplateCodec.DIMENSIONS && b.size == TemplateCodec.DIMENSIONS) {
            "templates must have ${TemplateCodec.DIMENSIONS} dimensions"
        }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        // Non-finite norms (NaN, ±Inf, or squares overflowing to Inf) and zero vectors are processing errors, never a
        // decision: `Inf > 0` holds, so finiteness must be checked explicitly (found by CosineTest, step 5 review).
        require(normA.isFinite() && normB.isFinite() && normA > 0.0 && normB > 0.0) { "templates must be non-zero and finite" }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
