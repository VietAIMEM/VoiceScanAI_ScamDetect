package com.example.my_app.ml

import android.content.Context

class FastTextNativeClassifier : TextScamClassifier {
    override val engineName: String
        get() = if (ready) "fasttext" else fallback.engineName
    override val isReady: Boolean
        get() = ready

    private val fallback = FastTextScamClassifierStub()
    private var ready = false

    override fun load(context: Context) {
        if (ready) return
        val modelFile =
            AssetModelStore(context).copyAssetIfNeeded(AssetModelStore.FASTTEXT_MOBILE_FTZ)
                ?: AssetModelStore(context).copyAssetIfNeeded(AssetModelStore.FASTTEXT_MOBILE_BIN)

        if (modelFile == null) {
            fallback.load(context)
            return
        }

        ready =
            runCatching {
                FastTextNative.loadModel(modelFile.absolutePath)
            }.getOrElse {
                fallback.load(context)
                false
            }
    }

    override fun predict(text: String): TextDetectionResult? {
        if (!ready) return fallback.predict(text)
        if (text.isBlank()) return null

        val normalized = normalize(text)
        val raw = runCatching { FastTextNative.predict(normalized, 2, 0.0f) }
            .getOrElse { return fallback.predict(text) }

        if (raw.size < 2) return null
        val predictions =
            raw.toList()
                .chunked(2)
                .mapNotNull { pair ->
                    val label = pair.getOrNull(0) ?: return@mapNotNull null
                    val probability = pair.getOrNull(1)?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                        ?: return@mapNotNull null
                    label to probability
                }
        if (predictions.isEmpty()) return null

        val label = predictions.first().first
        val scamScore = predictions.firstOrNull { isScamLabel(it.first) }?.second ?: 0.0

        return TextDetectionResult(
            score = scamScore.coerceIn(0.0, 1.0),
            label = label,
        )
    }

    override fun close() {
        if (ready) {
            runCatching { FastTextNative.close() }
        }
        ready = false
        fallback.close()
    }

    private fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("http\\S+|www\\.\\S+"), " ")
            .replace(Regex("\\d+"), " NUM ")
            .replace(Regex("[^\\w\\s]"), " ")
            .replace(Regex("(.)\\1{2,}"), "$1$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isScamLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return normalized == "__label__scam" ||
            normalized.endsWith("scam") ||
            normalized.contains("fraud") ||
            normalized.contains("lua")
    }
}
