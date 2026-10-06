package com.orbiecosystem.omnivoice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Conservative post-processing for generated speech only.
 * Keeps the codec diagnostic untouched.
 */
object AudioPostProcessor {
    fun process(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples

        var peak = 0f
        for (s in samples) peak = max(peak, abs(s))
        if (peak < 1e-5f) return samples

        val activeThreshold = max(0.0025f, peak * 0.018f)
        val longSilence = (sampleRate * 0.30f).toInt()
        val maxTailBurst = (sampleRate * 0.28f).toInt()
        val leadKeep = (sampleRate * 0.08f).toInt()
        val trailKeep = (sampleRate * 0.14f).toInt()

        fun active(i: Int) = abs(samples[i]) >= activeThreshold

        var first = 0
        while (first < samples.size && !active(first)) first++
        if (first >= samples.size) return samples

        var last = samples.lastIndex
        while (last > first && !active(last)) last--

        // If a short isolated burst occurs after a long final silence, treat it as a tail artifact.
        var voiceSeen = 0
        var silenceStart = -1
        var cutAt = -1
        var i = first
        while (i <= last) {
            if (active(i)) {
                voiceSeen++
                silenceStart = -1
            } else {
                if (silenceStart < 0) silenceStart = i
                val silenceLen = i - silenceStart + 1
                if (voiceSeen > sampleRate / 8 && silenceLen >= longSilence) {
                    var nextActive = i + 1
                    while (nextActive <= last && !active(nextActive)) nextActive++
                    if (nextActive <= last) {
                        var tailEnd = nextActive
                        while (tailEnd <= last && (tailEnd - nextActive) <= maxTailBurst) tailEnd++
                        val tailSpan = last - nextActive + 1
                        if (tailSpan <= maxTailBurst) {
                            cutAt = silenceStart
                            break
                        }
                    }
                }
            }
            i++
        }

        if (cutAt > 0) last = min(last, cutAt - 1)

        val start = max(0, first - leadKeep)
        val endExclusive = min(samples.size, last + 1 + trailKeep)
        if (endExclusive <= start) return samples

        val out = samples.copyOfRange(start, endExclusive)
        val fade = min((sampleRate * 0.012f).toInt(), out.size / 4)
        if (fade > 1) {
            for (n in 0 until fade) {
                val g = n.toFloat() / fade.toFloat()
                out[n] *= g
                out[out.lastIndex - n] *= g
            }
        }
        return out
    }
}
