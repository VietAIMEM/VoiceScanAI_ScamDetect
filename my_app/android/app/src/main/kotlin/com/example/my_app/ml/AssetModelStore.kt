package com.example.my_app.ml

import android.content.Context
import java.io.File

class AssetModelStore(private val context: Context) {
    fun copyAssetIfNeeded(assetPath: String): File? {
        val destination = File(context.filesDir, assetPath)

        return try {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destination
        } catch (_: Exception) {
            null
        }
    }

    fun hasAsset(assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            try {
                context.assets.list(assetPath)?.isNotEmpty() == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun copyAssetDirectoryIfNeeded(assetPath: String): File? {
        val destination = File(context.filesDir, assetPath)
        if (destination.exists() && destination.isDirectory && destination.list()?.isNotEmpty() == true) {
            return destination
        }

        return try {
            copyAssetDirectory(assetPath, destination)
            destination
        } catch (_: Exception) {
            null
        }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetDirectory(
                "$assetPath/$child",
                File(destination, child),
            )
        }
    }

    companion object {
        const val ANTI_AI_CONFORMER = "models/anti_ai_mobile.pt"
        const val AASIST = "models/aasist_best.pt"
        const val AASIST_MOBILE_PT = "models/aasist_mobile.pt"
        const val AASIST_MOBILE_PTL = "models/aasist_mobile.ptl"
        const val AASIST_MOBILE = AASIST_MOBILE_PTL
        const val FASTTEXT_MOBILE_FTZ = "models/scam_fasttext_mobile.ftz"
        const val FASTTEXT_MOBILE_BIN = "models/scam_fasttext_mobile.bin"
        const val FASTTEXT = FASTTEXT_MOBILE_FTZ
        const val TEXTCNN_MOBILE = "models/textcnn_mobile.pt"
        const val TEXTCNN_VOCAB = "models/textcnn_vocab.json"
        const val SHERPA_ZIPFORMER = "models/former-30M-RNNT-6000h"
    }
}
