package com.example.my_app.ml

import com.example.my_app.DetectionScore

class DetectionAggregator {
    private var smoothedVoice: Double? = null
    private var smoothedText: Double? = null
    private var conformerScore: Double? = null
    private var aasistScore: Double? = null
    private val voiceHistory = mutableListOf<Double>()
    private val conformerHistory = mutableListOf<Double>()
    private val aasistHistory = mutableListOf<Double>()
    private val textHistory = mutableListOf<Double>()
    private var latestTextLabel: String = ""
    private var latestTranscript: String = ""
    private var hasSeenTranscript = false
    private var lastTextAtMillis = 0L

    fun updateVoice(results: List<VoiceDetectionResult>) {
        if (results.isEmpty()) return
        results.forEach { result ->
            when {
                result.modelName.contains("anti_ai", ignoreCase = true) -> {
                    val score = result.score.coerceIn(0.0, 1.0)
                    appendScoreHistory(conformerHistory, score, MAX_VOICE_HISTORY)
                    conformerScore = aggregateHistory(conformerHistory, minSamples = MIN_VOICE_HISTORY_FOR_SCORE)
                }
                result.modelName.contains("aasist", ignoreCase = true) -> {
                    val score = result.score.coerceIn(0.0, 1.0)
                    appendScoreHistory(aasistHistory, score, MAX_VOICE_HISTORY)
                    aasistScore = aggregateHistory(aasistHistory, minSamples = MIN_VOICE_HISTORY_FOR_SCORE)
                }
            }
        }
        val voice = combineVoiceScores(
            conformer = results.firstOrNull { it.modelName.contains("anti_ai", ignoreCase = true) }?.score,
            aasist = results.firstOrNull { it.modelName.contains("aasist", ignoreCase = true) }?.score,
        )
        appendScoreHistory(voiceHistory, voice, MAX_VOICE_HISTORY)
        smoothedVoice = aggregateHistory(voiceHistory, minSamples = MIN_VOICE_HISTORY_FOR_SCORE)
    }

    fun clearVoice() {
        smoothedVoice = null
        conformerScore = null
        aasistScore = null
        voiceHistory.clear()
        conformerHistory.clear()
        aasistHistory.clear()
    }

    fun hasVoiceScore(): Boolean {
        return smoothedVoice != null || conformerScore != null || aasistScore != null
    }

    fun updateText(result: TextDetectionResult?, transcript: String) {
        if (result == null || transcript.isBlank()) return
        val now = System.currentTimeMillis()
        if (lastTextAtMillis > 0L && now - lastTextAtMillis > TEXT_STALE_MS) {
            textHistory.clear()
        }
        latestTranscript = transcript
        latestTextLabel = result.label
        hasSeenTranscript = true
        lastTextAtMillis = now
        appendScoreHistory(textHistory, result.score.coerceIn(0.0, 1.0), MAX_TEXT_HISTORY)
        smoothedText = aggregateHistory(textHistory, minSamples = 1)
    }

    fun score(status: String): DetectionScore {
        val now = System.currentTimeMillis()
        val voice = smoothedVoice ?: 0.0
        val activeText = activeTextScore(now)
        val text = activeText ?: if (hasSeenTranscript) 0.0 else NEUTRAL_WAITING_TEXT
        val available = mutableListOf<Double>()
        if (smoothedVoice != null) available += voice
        if (activeText != null) available += text
        val total = if (available.isEmpty()) 0.0 else available.average()
        val risk = RiskLevel.fromScore(total)

        return DetectionScore(
            voice = voice,
            text = text,
            total = total,
            status = statusWithTranscript(status),
            updatedAtMillis = now,
            conformer = conformerScore ?: 0.0,
            aasist = aasistScore ?: 0.0,
            textLabel = latestTextLabel,
            transcript = currentTranscript(),
            riskLevel = risk.level,
            riskLabel = risk.label,
        )
    }

    fun reset() {
        smoothedVoice = null
        smoothedText = null
        conformerScore = null
        aasistScore = null
        voiceHistory.clear()
        conformerHistory.clear()
        aasistHistory.clear()
        textHistory.clear()
        latestTextLabel = ""
        latestTranscript = ""
        hasSeenTranscript = false
        lastTextAtMillis = 0L
    }

    private fun activeTextScore(now: Long): Double? {
        val text = smoothedText ?: return null
        if (lastTextAtMillis > 0L && now - lastTextAtMillis > TEXT_STALE_MS) {
            return null
        }
        return text
    }

    private fun statusWithTranscript(status: String): String {
        if (latestTranscript.isBlank()) return status
        val preview =
            if (latestTranscript.length > MAX_TRANSCRIPT_PREVIEW) {
                latestTranscript.take(MAX_TRANSCRIPT_PREVIEW - 1) + "..."
            } else {
                latestTranscript
            }
        return "$status | $preview"
    }

    private fun currentTranscript(): String {
        if (latestTranscript.length <= MAX_DEBUG_TRANSCRIPT) return latestTranscript
        return latestTranscript.takeLast(MAX_DEBUG_TRANSCRIPT)
    }

    private fun appendScoreHistory(
        history: MutableList<Double>,
        score: Double,
        maxHistory: Int,
    ) {
        history += score.coerceIn(0.0, 1.0)
        if (history.size > maxHistory) {
            history.removeAt(0)
        }
    }

    private fun aggregateHistory(
        history: List<Double>,
        minSamples: Int,
    ): Double? {
        if (history.size < minSamples) return null
        return history.average().coerceIn(0.0, 1.0)
    }

    private fun combineVoiceScores(conformer: Double?, aasist: Double?): Double {
        val conformerSafe = conformer?.coerceIn(0.0, 1.0)
        val aasistSafe = aasist?.coerceIn(0.0, 1.0)

        if (conformerSafe == null) return aasistSafe ?: 0.0
        if (aasistSafe == null) return conformerSafe

        val weighted = (
            (conformerSafe * CONFORMER_WEIGHT) +
                (aasistSafe * AASIST_WEIGHT)
            ).coerceIn(0.0, 1.0)
        val strongest = maxOf(conformerSafe, aasistSafe)

        return maxOf(weighted, strongest * DISAGREEMENT_SIGNAL_FLOOR).coerceIn(0.0, 1.0)
    }

    private data class RiskLevel(
        val level: String,
        val label: String,
    ) {
        companion object {
            fun fromScore(score: Double): RiskLevel {
                return when {
                    score >= 0.70 -> RiskLevel("high", "High risk")
                    score >= 0.50 -> RiskLevel("medium", "Watch")
                    else -> RiskLevel("low", "Normal")
                }
            }
        }
    }

    private companion object {
        const val NEUTRAL_WAITING_TEXT = 0.5
        const val CONFORMER_WEIGHT = 0.45
        const val AASIST_WEIGHT = 0.55
        const val DISAGREEMENT_SIGNAL_FLOOR = 0.80
        const val MAX_VOICE_HISTORY = 5
        const val MIN_VOICE_HISTORY_FOR_SCORE = 1
        const val MAX_TEXT_HISTORY = 5
        const val TEXT_STALE_MS = 12_000L
        const val MAX_TRANSCRIPT_PREVIEW = 36
        const val MAX_DEBUG_TRANSCRIPT = 240
    }
}
