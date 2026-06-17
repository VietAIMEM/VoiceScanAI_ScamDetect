package com.example.my_app.ml

import android.content.Context
import kotlin.math.sin

class SpeechRecognizerStub : StreamingSpeechRecognizer {
    override val engineName: String = "stub"
    override val isReady: Boolean
        get() = false

    private var acceptedSamples = 0
    private var modelAvailable = false

    override fun load(context: Context) {
        modelAvailable = AssetModelStore(context).hasAsset(AssetModelStore.SHERPA_ZIPFORMER)
    }

    override fun acceptAudio(chunk: AudioChunk): SpeechResult? {
        acceptedSamples += chunk.sampleCount
        return null
    }

    override fun resetStream(): SpeechResult? {
        acceptedSamples = 0
        return null
    }

    override fun pollResult(): SpeechResult? = null

    override fun close() = Unit
}

class FastTextScamClassifierStub : TextScamClassifier {
    override val engineName: String = "stub"
    override val isReady: Boolean
        get() = false

    private var modelAvailable = false

    override fun load(context: Context) {
        modelAvailable = AssetModelStore(context).hasAsset(AssetModelStore.FASTTEXT)
    }

    override fun predict(text: String): TextDetectionResult? {
        if (text.isBlank()) return null
        val normalized = text.lowercase()
        val keywordHit =
            SCAM_HINTS.any { hint ->
                normalized.contains(hint)
            }
        return TextDetectionResult(
            score = if (keywordHit) 0.82 else if (modelAvailable) 0.36 else 0.28,
            label = if (keywordHit) "__label__scam" else "__label__normal",
        )
    }

    override fun close() = Unit

    private companion object {
        val SCAM_HINTS =
            listOf(
                "chuyen tien",
                "ma otp",
                "tai khoan",
                "cong an",
                "ngan hang",
            )
    }
}

class TorchVoiceDetectorStub(
    override val modelName: String,
) : VoiceAiDetector {
    override val isReady: Boolean
        get() = modelAvailable

    private var step = 0
    private var modelAvailable = false

    override fun load(context: Context) {
        val assetPath =
            if (modelName == "anti_ai_mobile") {
                AssetModelStore.ANTI_AI_CONFORMER
            } else {
                AssetModelStore.AASIST
            }
        modelAvailable = AssetModelStore(context).copyAssetIfNeeded(assetPath) != null
    }

    override fun predict(chunk: AudioChunk): VoiceDetectionResult? {
        if (chunk.rms < MIN_RMS) return null
        step += 1
        val audioReactiveScore = (chunk.rms * 4.0).coerceIn(0.0, 1.0)
        val slowWave = ((sin(step * 0.29) + 1.0) / 2.0).coerceIn(0.0, 1.0)
        return VoiceDetectionResult(
            score = (
                (audioReactiveScore * 0.7) +
                    (slowWave * 0.3) +
                    if (modelAvailable) 0.02 else 0.0
                ).coerceIn(0.0, 1.0),
            modelName = modelName,
        )
    }

    override fun close() = Unit

    private companion object {
        const val MIN_RMS = 0.004
    }
}
