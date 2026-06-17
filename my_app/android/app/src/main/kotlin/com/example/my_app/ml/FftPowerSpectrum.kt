package com.example.my_app.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

class FftPowerSpectrum(
    private val nFft: Int,
) {
    private val nFreqs = nFft / 2 + 1
    private val bitReverse = buildBitReverseTable()
    private val real = DoubleArray(nFft)
    private val imag = DoubleArray(nFft)

    fun power(samples: FloatArray, start: Int, window: FloatArray): DoubleArray {
        val power = DoubleArray(nFreqs)

        for (index in 0 until nFft) {
            val sampleIndex = start + index
            real[index] =
                if (sampleIndex < samples.size) {
                    samples[sampleIndex] * window[index].toDouble()
                } else {
                    0.0
                }
            imag[index] = 0.0
        }

        fftInPlace()

        for (bin in 0 until nFreqs) {
            power[bin] = (real[bin] * real[bin] + imag[bin] * imag[bin]) / nFft
        }
        return power
    }

    private fun fftInPlace() {
        for (index in 0 until nFft) {
            val reversed = bitReverse[index]
            if (reversed > index) {
                val tempReal = real[index]
                val tempImag = imag[index]
                real[index] = real[reversed]
                imag[index] = imag[reversed]
                real[reversed] = tempReal
                imag[reversed] = tempImag
            }
        }

        var size = 2
        while (size <= nFft) {
            val halfSize = size / 2
            val phaseStep = -2.0 * PI / size
            val stepReal = cos(phaseStep)
            val stepImag = kotlin.math.sin(phaseStep)

            var start = 0
            while (start < nFft) {
                var twiddleReal = 1.0
                var twiddleImag = 0.0

                for (offset in 0 until halfSize) {
                    val even = start + offset
                    val odd = even + halfSize

                    val oddReal = (twiddleReal * real[odd]) - (twiddleImag * imag[odd])
                    val oddImag = (twiddleReal * imag[odd]) + (twiddleImag * real[odd])

                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag

                    val nextTwiddleReal = (twiddleReal * stepReal) - (twiddleImag * stepImag)
                    twiddleImag = (twiddleReal * stepImag) + (twiddleImag * stepReal)
                    twiddleReal = nextTwiddleReal
                }
                start += size
            }
            size *= 2
        }
    }

    private fun buildBitReverseTable(): IntArray {
        val bits = (ln(nFft.toDouble()) / ln(2.0)).toInt()
        return IntArray(nFft) { value ->
            var reversed = 0
            var input = value
            repeat(bits) {
                reversed = (reversed shl 1) or (input and 1)
                input = input shr 1
            }
            reversed
        }
    }
}
