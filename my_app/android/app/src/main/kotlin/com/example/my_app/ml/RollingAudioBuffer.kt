package com.example.my_app.ml

class RollingAudioBuffer(
    private val capacity: Int,
    private val sampleRate: Int,
) {
    private val buffer = ShortArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    @Synchronized
    fun append(samples: ShortArray, count: Int) {
        for (index in 0 until count) {
            buffer[writeIndex] = samples[index]
            writeIndex = (writeIndex + 1) % capacity
            if (filled < capacity) filled += 1
        }
    }

    @Synchronized
    fun snapshot(minSamples: Int = sampleRate): AudioChunk? {
        if (filled < minSamples) return null

        val output = ShortArray(filled)
        val start = (writeIndex - filled + capacity) % capacity
        for (index in 0 until filled) {
            output[index] = buffer[(start + index) % capacity]
        }

        return AudioChunk(
            samples = output,
            sampleCount = output.size,
            sampleRate = sampleRate,
            rms = rms(output),
        )
    }

    @Synchronized
    fun clear() {
        writeIndex = 0
        filled = 0
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        samples.forEach { sample ->
            val normalized = sample / SHORT_MAX
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / samples.size).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val SHORT_MAX = 32768.0
    }
}
