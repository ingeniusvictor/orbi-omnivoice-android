package com.orbiecosystem.omnivoice

import kotlin.math.max
import kotlin.math.pow

object RuleDurationEstimator {
    const val FRAME_RATE = 25f
    private const val LOW_THRESHOLD = 50f
    private const val BOOST_STRENGTH = 3f
    private const val FALLBACK_TEXT = "Nice to meet you."
    private const val FALLBACK_FRAMES = 25f

    data class Estimate(
        val frames: Int,
        val seconds: Float,
        val source: String,
        val unclampedFrames: Int
    )

    fun estimate(
        targetText: String,
        referenceText: String? = null,
        referenceFrames: Int? = null,
        speed: Float = 1f,
        manualSeconds: Float? = null
    ): Estimate {
        require(targetText.isNotBlank()) { "Texto objetivo vacío" }
        require(speed > 0f) { "speed debe ser > 0" }

        if (manualSeconds != null) {
            val frames = max(1, (manualSeconds * FRAME_RATE).toInt())
            return Estimate(frames, frames / FRAME_RATE, "MANUAL", frames)
        }

        val hasReference = !referenceText.isNullOrBlank() && (referenceFrames ?: 0) > 0
        val refText = if (hasReference) referenceText!!.trim() else FALLBACK_TEXT
        val refFrames = if (hasReference) referenceFrames!!.toFloat() else FALLBACK_FRAMES

        val refWeight = totalWeight(refText).coerceAtLeast(0.001f)
        val targetWeight = totalWeight(targetText).coerceAtLeast(0.001f)
        var estimate = targetWeight / (refWeight / refFrames)

        if (estimate < LOW_THRESHOLD) {
            val alpha = 1f / BOOST_STRENGTH
            estimate = LOW_THRESHOLD * (estimate / LOW_THRESHOLD).toDouble().pow(alpha.toDouble()).toFloat()
        }
        if (speed != 1f) estimate /= speed

        val raw = max(1, estimate.toInt())
        val bounded = raw.coerceIn(20, 200)
        return Estimate(
            frames = bounded,
            seconds = bounded / FRAME_RATE,
            source = if (hasReference) "AUTO-REFERENCE" else "AUTO-HEURISTIC",
            unclampedFrames = raw
        )
    }

    fun totalWeight(text: String): Float {
        var total = 0f
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            total += codePointWeight(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    private fun codePointWeight(cp: Int): Float {
        if (Character.isWhitespace(cp)) return 0.2f
        if (Character.isDigit(cp)) return 3.5f

        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        ) return 0f

        if (type in setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt()
            )
        ) return 0.5f

        return when (cp) {
            in 0x3040..0x30FF -> 2.2f
            in 0x1100..0x11FF, in 0x3130..0x318F, in 0xAC00..0xD7AF -> 2.5f
            in 0x3400..0x9FFF, in 0xF900..0xFAFF -> 3.0f
            in 0x0590..0x05FF -> 1.5f
            in 0x0600..0x08FF, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> 1.5f
            in 0x0900..0x0DFF -> 1.8f
            in 0x0E00..0x0EFF -> 1.5f
            in 0x1000..0x109F, in 0x1780..0x17FF -> 1.8f
            in 0x1200..0x137F -> 3.0f
            in 0xA000..0xA4CF -> 3.0f
            else -> 1.0f
        }
    }
}
