package com.example.my_app.ml

import android.content.Context
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.exp

class TorchAasistDetector : VoiceAiDetector {
    override val modelName: String = "aasist_mobile"
    override val isReady: Boolean
        get() = module != null && !disabled

    private var module: Module? = null
    private var disabled = false

    override fun load(context: Context) {
        if (module != null || disabled) return
        val modelStore = AssetModelStore(context)
        val modelFile =
            AASIST_CANDIDATES.firstNotNullOfOrNull { assetPath ->
                if (modelStore.hasAsset(assetPath)) {
                    modelStore.copyAssetIfNeeded(assetPath)
                } else {
                    null
                }
            }
        if (modelFile != null) {
            module = runCatching { Module.load(modelFile.absolutePath) }.getOrNull()
            disabled = module == null
        }
    }

    override fun predict(chunk: AudioChunk): VoiceDetectionResult? {
        if (disabled) return null
        val loadedModule = module ?: return null
        if (chunk.rms < MIN_RMS) return null

        return runCatching {
            val waveform = fixedLengthWaveform(normalizedWaveform(chunk))
            val input =
                Tensor.fromBlob(
                    waveform,
                    longArrayOf(
                        1,
                        waveform.size.toLong(),
                    ),
            )
            val output = loadedModule.forward(IValue.from(input)).toTensor().dataAsFloatArray
            if (output.any { !it.isFinite() }) return null

            VoiceDetectionResult(
                score = aiScore(output),
                modelName = modelName,
            )
        }.getOrElse {
            disabled = true
            module?.destroy()
            module = null
            null
        }
    }

    override fun close() {
        module?.destroy()
        module = null
        disabled = false
    }

    private fun sigmoid(value: Double): Double {
        return (1.0 / (1.0 + exp(-value))).coerceIn(0.0, 1.0)
    }

    private fun aiScore(output: FloatArray): Double {
        if (output.size >= 2) {
            val aiLogit = output[0].toDouble()
            val humanLogit = output[1].toDouble()
            val calibratedMargin = ((aiLogit - humanLogit) + AI_LOGIT_BIAS) / AI_LOGIT_TEMPERATURE
            return sigmoid(calibratedMargin).coerceIn(0.0, 1.0)
        }

        val humanLogit = output.firstOrNull()?.toDouble() ?: return 0.0
        return (1.0 - sigmoid(humanLogit)).coerceIn(0.0, 1.0)
    }

    private fun normalizedWaveform(chunk: AudioChunk): FloatArray {
        val samples = chunk.toFloatArray()
        if (samples.isEmpty()) return samples

        var sumSquares = 0.0
        samples.forEach { sample ->
            sumSquares += sample * sample
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size)
        val gain =
            if (rms <= 1e-6) {
                1.0
            } else {
                (TARGET_RMS / rms).coerceIn(1.0, MAX_SOFT_GAIN)
            }

        return FloatArray(samples.size) { index ->
            (samples[index] * gain).coerceIn(-1.0, 1.0).toFloat()
        }
    }

    private fun fixedLengthWaveform(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(AASIST_INPUT_SAMPLES)
        if (samples.size == AASIST_INPUT_SAMPLES) return samples
        if (samples.size > AASIST_INPUT_SAMPLES) return samples.copyOfRange(0, AASIST_INPUT_SAMPLES)

        return FloatArray(AASIST_INPUT_SAMPLES) { index ->
            samples[index % samples.size]
        }
    }

    private companion object {
        const val MIN_RMS = 0.004
        const val TARGET_RMS = 0.08
        const val MAX_SOFT_GAIN = 3.0
        const val AASIST_INPUT_SAMPLES = 64_600
        const val AI_LOGIT_BIAS = 0.85
        const val AI_LOGIT_TEMPERATURE = 0.85
        val AASIST_CANDIDATES =
            listOf(
                AssetModelStore.AASIST_MOBILE_PT,
                AssetModelStore.AASIST_MOBILE_PTL,
            )
    }
}
