package com.example.my_app.ml

import android.content.Context
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.exp

class TextCnnTorchClassifier : TextScamClassifier {
    override val engineName: String
        get() = "fasttext"
    override val isReady: Boolean
        get() = module != null

    private val fallback = FastTextNativeClassifier()
    private var module: Module? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var maxLen: Int = DEFAULT_MAX_LEN

    override fun load(context: Context) {
        if (module != null) return
        val modelStore = AssetModelStore(context)
        val modelFile = modelStore.copyAssetIfNeeded(AssetModelStore.TEXTCNN_MOBILE)
        val vocabFile = modelStore.copyAssetIfNeeded(AssetModelStore.TEXTCNN_VOCAB)

        if (modelFile == null || vocabFile == null) {
            fallback.load(context)
            return
        }

        val loadedVocab =
            runCatching {
                val json = JSONObject(vocabFile.readText(Charsets.UTF_8))
                val vocabJson = json.getJSONObject("vocab")
                val entries = mutableMapOf<String, Int>()
                vocabJson.keys().forEach { key ->
                    entries[key] = vocabJson.getInt(key)
                }
                maxLen = json.optInt("max_len", DEFAULT_MAX_LEN).coerceAtLeast(1)
                entries
            }.getOrNull()

        if (loadedVocab == null || loadedVocab.isEmpty()) {
            fallback.load(context)
            return
        }

        val loadedModule = runCatching { Module.load(modelFile.absolutePath) }.getOrNull()
        if (loadedModule == null) {
            fallback.load(context)
            return
        }

        vocab = loadedVocab
        module = loadedModule
    }

    override fun predict(text: String): TextDetectionResult? {
        val loadedModule = module ?: return fallback.predict(text)
        if (text.isBlank()) return null

        val normalized = normalize(text)
        if (normalized.isBlank()) return null
        val inputIds = encode(normalized)
        val input = Tensor.fromBlob(inputIds, longArrayOf(1, maxLen.toLong()))
        val output =
            runCatching {
                loadedModule.forward(IValue.from(input)).toTensor().dataAsFloatArray
            }.getOrElse {
                return fallback.predict(text)
            }

        if (output.size < 2 || output.any { !it.isFinite() }) return null
        val normalLogit = output[0].toDouble()
        val scamLogit = output[1].toDouble()
        val scamScore = softmaxSecond(normalLogit, scamLogit)
        val label = if (scamScore >= 0.5) "__label__scam" else "__label__normal"

        return TextDetectionResult(
            score = scamScore.coerceIn(0.0, 1.0),
            label = label,
        )
    }

    override fun close() {
        module?.destroy()
        module = null
        vocab = emptyMap()
        fallback.close()
    }

    private fun encode(text: String): LongArray {
        val pad = vocab["<PAD>"] ?: 0
        val unknown = vocab["<UNK>"] ?: 1
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return LongArray(maxLen) { index ->
            if (index < tokens.size) {
                (vocab[tokens[index]] ?: unknown).toLong()
            } else {
                pad.toLong()
            }
        }
    }

    private fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("http\\S+|www\\.\\S+"), " ")
            .replace(Regex("\\b\\d{8,15}\\b"), " ")
            .replace(Regex("\\d+"), " NUM ")
            .replace(Regex("[^\\w\\s]"), " ")
            .replace(Regex("(.)\\1{2,}"), "$1$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun softmaxSecond(
        first: Double,
        second: Double,
    ): Double {
        val maxLogit = maxOf(first, second)
        val firstExp = exp(first - maxLogit)
        val secondExp = exp(second - maxLogit)
        return secondExp / (firstExp + secondExp)
    }

    private companion object {
        const val DEFAULT_MAX_LEN = 40
    }
}
