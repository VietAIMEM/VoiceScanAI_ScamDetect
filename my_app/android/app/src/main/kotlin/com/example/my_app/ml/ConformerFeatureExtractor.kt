package com.example.my_app.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class ConformerFeatureExtractor {
    private val window = FloatArray(N_FFT) { index ->
        (0.5 - 0.5 * cos((2.0 * PI * index) / N_FFT)).toFloat()
    }
    private val melFilters = buildMelFilterbank()
    private val spectrum = FftPowerSpectrum(N_FFT)

    fun extract(chunk: AudioChunk): FloatArray {
        val features = FloatArray(N_MELS * MAX_LEN)
        val samples = chunk.toFloatArray()
        if (samples.isEmpty()) return features

        for (frame in 0 until MAX_LEN) {
            val start = frame * HOP
            val power = powerSpectrum(samples, start)
            for (mel in 0 until N_MELS) {
                var energy = 0.0
                val filter = melFilters[mel]
                for (bin in filter.indices) {
                    energy += power[bin] * filter[bin]
                }
                features[(mel * MAX_LEN) + frame] = amplitudeToDb(energy)
            }
        }

        normalize(features)
        return features
    }

    private fun powerSpectrum(samples: FloatArray, start: Int): DoubleArray {
        return spectrum.power(samples, start, window)
    }

    private fun amplitudeToDb(power: Double): Float {
        val magnitude = sqrt(maxOf(power, AMIN))
        return (MULTIPLIER * ln(magnitude / REF_VALUE)).toFloat()
    }

    private fun normalize(values: FloatArray) {
        var mean = 0.0
        values.forEach { mean += it }
        mean /= values.size

        var variance = 0.0
        values.forEach { value ->
            val centered = value - mean
            variance += centered * centered
        }
        val std = sqrt(variance / values.size) + 1e-6

        for (index in values.indices) {
            values[index] = ((values[index] - mean) / std).toFloat()
        }
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val melPoints = DoubleArray(N_MELS + 2) { index ->
            minMel + (maxMel - minMel) * index / (N_MELS + 1)
        }
        val hzPoints = melPoints.map(::melToHz)
        val bins = hzPoints.map { hz ->
            kotlin.math.floor((N_FFT + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, N_FREQS - 1)
        }

        return Array(N_MELS) { mel ->
            val filter = FloatArray(N_FREQS)
            val left = bins[mel]
            val center = bins[mel + 1]
            val right = bins[mel + 2]

            for (bin in left until center) {
                filter[bin] = (bin - left).toFloat() / maxOf(1, center - left)
            }
            for (bin in center until right) {
                filter[bin] = (right - bin).toFloat() / maxOf(1, right - center)
            }

            filter
        }
    }

    private fun hzToMel(hz: Double): Double {
        return 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    }

    private fun melToHz(mel: Double): Double {
        return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_MELS = 64
        const val MAX_LEN = 400
        private const val N_FFT = 512
        private const val HOP = 160
        private const val N_FREQS = N_FFT / 2 + 1
        private const val AMIN = 1e-10
        private const val REF_VALUE = 1.0
        private val MULTIPLIER = 20.0 / ln(10.0)
    }
}
