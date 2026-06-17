package com.example.my_app

import android.content.Context
import android.content.Intent

data class DetectionScore(
    val voice: Double,
    val text: Double,
    val total: Double,
    val status: String,
    val updatedAtMillis: Long,
    val conformer: Double = 0.0,
    val aasist: Double = 0.0,
    val textLabel: String = "",
    val transcript: String = "",
    val riskLevel: String = "idle",
    val riskLabel: String = "Idle",
) {
    fun toMap(): Map<String, Any> =
        mapOf(
            "voice" to voice,
            "text" to text,
            "total" to total,
            "status" to status,
            "updatedAtMillis" to updatedAtMillis,
            "conformer" to conformer,
            "aasist" to aasist,
            "textLabel" to textLabel,
            "transcript" to transcript,
            "riskLevel" to riskLevel,
            "riskLabel" to riskLabel,
        )
}

object DetectionScoreStore {
    const val ACTION_SCORE_UPDATE = "com.example.my_app.ACTION_SCORE_UPDATE"

    @Volatile
    var latest: DetectionScore =
        DetectionScore(
            voice = 0.0,
            text = 0.0,
            total = 0.0,
            status = "Idle",
            updatedAtMillis = 0L,
        )
        private set

    fun publish(context: Context, score: DetectionScore) {
        latest = score
        context.sendBroadcast(Intent(ACTION_SCORE_UPDATE).setPackage(context.packageName))
    }
}
