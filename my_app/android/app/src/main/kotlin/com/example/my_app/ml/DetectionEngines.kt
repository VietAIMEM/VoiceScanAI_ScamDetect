package com.example.my_app.ml

import android.content.Context

data class AudioChunk(
    val samples: ShortArray,
    val sampleCount: Int,
    val sampleRate: Int,
    val rms: Double,
    val inputRms: Double = rms,
    val gain: Double = 1.0,
    val sourceName: String = "unknown",
    val voiceSamples: ShortArray = samples,
) {
    fun toFloatArray(): FloatArray {
        return FloatArray(sampleCount) { index ->
            samples[index] / SHORT_MAX
        }
    }

    private companion object {
        const val SHORT_MAX = 32768.0f
    }
}

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
)

data class TextDetectionResult(
    val score: Double,
    val label: String,
)

data class VoiceDetectionResult(
    val score: Double,
    val modelName: String,
)

interface StreamingSpeechRecognizer {
    val engineName: String
    val isReady: Boolean

    fun load(context: Context)
    fun acceptAudio(chunk: AudioChunk): SpeechResult?
    fun resetStream(): SpeechResult?
    fun pollResult(): SpeechResult?
    fun close()
}

interface TextScamClassifier {
    val engineName: String
    val isReady: Boolean

    fun load(context: Context)
    fun predict(text: String): TextDetectionResult?
    fun close()
}

interface VoiceAiDetector {
    val modelName: String
    val isReady: Boolean

    fun load(context: Context)
    fun predict(chunk: AudioChunk): VoiceDetectionResult?
    fun close()
}
