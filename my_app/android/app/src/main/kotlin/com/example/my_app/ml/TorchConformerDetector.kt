package com.example.my_app.ml

import android.content.Context
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.exp

class TorchConformerDetector : VoiceAiDetector {
    override val modelName: String = "anti_ai_mobile"
    override val isReady: Boolean
        get() = module != null

    private val featureExtractor = ConformerFeatureExtractor()
    private var module: Module? = null

    override fun load(context: Context) {
        if (module != null) return
        val modelFile = AssetModelStore(context).copyAssetIfNeeded(AssetModelStore.ANTI_AI_CONFORMER)
        if (modelFile != null) {
            module = runCatching { Module.load(modelFile.absolutePath) }.getOrNull()
        }
    }

    override fun predict(chunk: AudioChunk): VoiceDetectionResult? {
        val loadedModule = module ?: return null
        if (chunk.rms < MIN_RMS) return null

        val features = featureExtractor.extract(chunk)
        val input =
            Tensor.fromBlob(
                features,
                longArrayOf(
                    1,
                    ConformerFeatureExtractor.N_MELS.toLong(),
                    ConformerFeatureExtractor.MAX_LEN.toLong(),
                ),
            )
        val output = loadedModule.forward(IValue.from(input)).toTensor().dataAsFloatArray
        val logit = output.firstOrNull() ?: return null

        return VoiceDetectionResult(
            score = 1.0 - sigmoid(logit.toDouble()),
            modelName = modelName,
        )
    }

    override fun close() {
        module?.destroy()
        module = null
    }

    private fun sigmoid(value: Double): Double {
        return (1.0 / (1.0 + exp(-value))).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val MIN_RMS = 0.004
    }
}
