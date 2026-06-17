package com.example.my_app.ml

import android.content.Context
import com.example.my_app.DetectionScore
import java.util.ArrayDeque

class RealtimeDetectionPipeline(
    private val context: Context,
) {
    private val speechRecognizer: StreamingSpeechRecognizer = SherpaZipformerSpeechRecognizerEngine()
    private val textClassifier: TextScamClassifier = TextCnnTorchClassifier()
    private val voiceDetectors: List<VoiceAiDetector> =
        listOf(
            TorchConformerDetector(),
            TorchAasistDetector(),
        )
    private val aggregator = DetectionAggregator()
    private val voiceWindow =
        RollingAudioBuffer(
            capacity = AudioRecordWindow.SAMPLES,
            sampleRate = AudioRecordWindow.SAMPLE_RATE,
        )
    private val speechGate = SpeechActivityGate()
    private val voiceGate = VoiceActivityGate()
    private val transcriptBuffer = StringBuilder()
    private var latestPartial = ""
    private var loaded = false
    private var lastVoiceInferenceAt = 0L
    private var lastTextInferenceAt = 0L
    private var diagnostics = "Loading models"

    @Synchronized
    fun load() {
        if (loaded) return
        runCatching { speechRecognizer.load(context) }
        runCatching { textClassifier.load(context) }
        voiceDetectors.forEach { detector ->
            runCatching { detector.load(context) }
        }
        diagnostics = buildDiagnostics()
        loaded = true
    }

    fun acceptAudio(chunk: AudioChunk): DetectionScore? {
        load()
        val gatedSpeech = speechGate.accept(chunk)
        val now = System.currentTimeMillis()
        val voiceState = voiceGate.accept(chunk, speechGate.isOpen, now)
        if (voiceState.keepAudio) {
            voiceWindow.append(chunk.voiceSamples, chunk.sampleCount)
        } else if (voiceState.clearWindow) {
            voiceWindow.clear()
        }
        if (gatedSpeech.resetRecognizer) {
            speechRecognizer.resetStream()?.let { speech ->
                handleSpeech(speech)
            }
            latestPartial = ""
        }
        runCatching {
            gatedSpeech.chunks.forEach { speechChunk ->
                speechRecognizer.acceptAudio(speechChunk)?.let { speech ->
                    handleSpeech(speech)
                }
            }
            drainSpeechResults()
        }

        if (now - lastVoiceInferenceAt >= VOICE_INFERENCE_INTERVAL_MS) {
            lastVoiceInferenceAt = now
            if (voiceGate.canScore(now)) {
                val voiceChunk = voiceWindow.snapshot(minSamples = AudioRecordWindow.MIN_SCORING_SAMPLES)
                if (voiceChunk == null || voiceChunk.rms < VOICE_WINDOW_MIN_RMS) {
                    clearVoiceIfEmpty()
                } else {
                    val voiceResults =
                        voiceDetectors.mapNotNull { detector ->
                            runCatching { detector.predict(voiceChunk) }.getOrNull()
                        }
                    if (voiceResults.isEmpty()) {
                        clearVoiceIfEmpty()
                    } else {
                        aggregator.updateVoice(voiceResults)
                    }
                }
            } else {
                voiceWindow.clear()
                clearVoiceIfEmpty()
            }
        }

        return aggregator.score("${statusForAudio(chunk)} | $diagnostics")
    }

    fun close() {
        speechRecognizer.close()
        textClassifier.close()
        voiceDetectors.forEach { it.close() }
        aggregator.reset()
        voiceWindow.clear()
        speechGate.reset()
        voiceGate.reset()
        transcriptBuffer.clear()
        latestPartial = ""
        loaded = false
        lastVoiceInferenceAt = 0L
        lastTextInferenceAt = 0L
        diagnostics = "Stopped"
    }

    private fun appendTranscript(text: String) {
        if (text.isBlank()) return
        if (transcriptBuffer.isNotEmpty()) transcriptBuffer.append(' ')
        transcriptBuffer.append(text.trim())
        if (transcriptBuffer.length > MAX_TRANSCRIPT_CHARS) {
            transcriptBuffer.delete(0, transcriptBuffer.length - MAX_TRANSCRIPT_CHARS)
        }
    }

    private fun handleSpeech(speech: SpeechResult) {
        val normalized = speech.text.trim()
        if (normalized.isBlank()) return

        if (speech.isFinal) {
            appendTranscript(normalized)
            latestPartial = ""
            updateTextScore(transcriptBuffer.toString(), force = true)
            return
        }

        latestPartial = normalized
        if (normalized.length >= MIN_PARTIAL_CHARS && canRunPartialTextInference()) {
            updateTextScore(currentTextWindow(), force = false)
        }
    }

    private fun drainSpeechResults() {
        repeat(MAX_SPEECH_RESULTS_PER_TICK) {
            val speech = speechRecognizer.pollResult() ?: return
            handleSpeech(speech)
        }
    }

    private fun currentTextWindow(): String {
        return if (transcriptBuffer.isBlank()) {
            latestPartial
        } else if (latestPartial.isBlank()) {
            transcriptBuffer.toString()
        } else {
            "${transcriptBuffer} $latestPartial"
        }
    }

    private fun updateTextScore(text: String, force: Boolean) {
        if (text.isBlank()) return
        val classifierText = textWindowForClassifier(text)
        if (!hasEnoughWords(classifierText)) return
        if (!force && !canRunPartialTextInference()) return
        lastTextInferenceAt = System.currentTimeMillis()
        val textResult = runCatching { textClassifier.predict(classifierText) }.getOrNull()
        aggregator.updateText(textResult, classifierText)
    }

    private fun clearVoiceIfEmpty() {
        if (!aggregator.hasVoiceScore()) {
            aggregator.clearVoice()
        }
    }

    private fun canRunPartialTextInference(): Boolean {
        return System.currentTimeMillis() - lastTextInferenceAt >= TEXT_INFERENCE_INTERVAL_MS
    }

    private fun hasEnoughWords(text: String): Boolean {
        return text.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= MIN_TEXT_WORDS
    }

    private fun textWindowForClassifier(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= MAX_TEXT_WORDS) return words.joinToString(" ")
        return words.takeLast(MAX_TEXT_WORDS).joinToString(" ")
    }

    private fun statusForAudio(chunk: AudioChunk): String {
        return if (chunk.rms > LOW_AUDIO_RMS) {
            val textState = if (latestPartial.isBlank() && transcriptBuffer.isBlank()) {
                if (speechGate.isOpen) "waiting text" else "waiting speech"
            } else {
                "text streaming"
            }
            "Listening (${(chunk.rms * 100).toInt()}% audio, gain ${String.format("%.1f", chunk.gain)}x, ${chunk.sourceName}, $textState)"
        } else {
            "Listening (low audio, gain ${String.format("%.1f", chunk.gain)}x, ${chunk.sourceName})"
        }
    }

    private class SpeechActivityGate {
        var isOpen: Boolean = false
            private set

        private val pending = ArrayDeque<AudioChunk>()
        private var voicedMillis = 0L
        private var silenceMillis = 0L
        private var pendingMillis = 0L

        fun accept(chunk: AudioChunk): GateResult {
            val chunkMillis = chunk.durationMillis()
            val voiced = isVoiceLike(chunk)

            if (voiced) {
                silenceMillis = 0L
                voicedMillis += chunkMillis
                if (isOpen) return GateResult(chunks = listOf(chunk))

                addPending(chunk, chunkMillis)
                if (voicedMillis >= MIN_START_VOICE_MS) {
                    isOpen = true
                    val chunks = pending.toList()
                    pending.clear()
                    pendingMillis = 0L
                    return GateResult(chunks = chunks)
                }
                return GateResult()
            }

            voicedMillis = 0L
            pending.clear()
            pendingMillis = 0L

            if (!isOpen) return GateResult()

            silenceMillis += chunkMillis
            if (silenceMillis <= END_SILENCE_HANGOVER_MS) {
                return GateResult(chunks = listOf(chunk))
            }

            isOpen = false
            silenceMillis = 0L
            return GateResult(resetRecognizer = true)
        }

        fun reset() {
            isOpen = false
            pending.clear()
            voicedMillis = 0L
            silenceMillis = 0L
            pendingMillis = 0L
        }

        private fun addPending(chunk: AudioChunk, chunkMillis: Long) {
            pending.addLast(chunk)
            pendingMillis += chunkMillis
            while (pendingMillis > PRE_ROLL_MS && pending.isNotEmpty()) {
                val removed = pending.removeFirst()
                pendingMillis -= removed.durationMillis()
            }
        }

        private fun isVoiceLike(chunk: AudioChunk): Boolean {
            return chunk.inputRms >= START_INPUT_RMS || (
                chunk.inputRms >= MIN_INPUT_RMS_WITH_GAIN &&
                    chunk.rms >= START_OUTPUT_RMS
                )
        }

        private fun AudioChunk.durationMillis(): Long {
            if (sampleRate <= 0) return 0L
            return (sampleCount * 1000L) / sampleRate
        }

        data class GateResult(
            val chunks: List<AudioChunk> = emptyList(),
            val resetRecognizer: Boolean = false,
        )

        private companion object {
            const val START_INPUT_RMS = 0.010
            const val MIN_INPUT_RMS_WITH_GAIN = 0.004
            const val START_OUTPUT_RMS = 0.030
            const val MIN_START_VOICE_MS = 320L
            const val END_SILENCE_HANGOVER_MS = 850L
            const val PRE_ROLL_MS = 500L
        }
    }

    private class VoiceActivityGate {
        private var activeVoiceMillis = 0L
        private var lastVoiceAtMillis = 0L
        private var windowIsClear = true

        fun accept(
            chunk: AudioChunk,
            speechGateOpen: Boolean,
            now: Long,
        ): VoiceState {
            val chunkMillis = chunk.durationMillis()
            val voiceLike = speechGateOpen || isVoiceLike(chunk)

            if (voiceLike) {
                activeVoiceMillis += chunkMillis
                lastVoiceAtMillis = now
                windowIsClear = false
                return VoiceState(keepAudio = true)
            }

            val inHangover =
                lastVoiceAtMillis > 0L &&
                    now - lastVoiceAtMillis <= KEEP_AUDIO_AFTER_VOICE_MS
            if (inHangover) return VoiceState(keepAudio = true)

            activeVoiceMillis = 0L
            val shouldClear = !windowIsClear
            windowIsClear = true
            return VoiceState(clearWindow = shouldClear)
        }

        fun canScore(now: Long): Boolean {
            return activeVoiceMillis >= MIN_ACTIVE_VOICE_MS &&
                lastVoiceAtMillis > 0L &&
                now - lastVoiceAtMillis <= SCORE_AFTER_VOICE_MS
        }

        fun reset() {
            activeVoiceMillis = 0L
            lastVoiceAtMillis = 0L
            windowIsClear = true
        }

        private fun isVoiceLike(chunk: AudioChunk): Boolean {
            return chunk.inputRms >= START_INPUT_RMS || (
                chunk.inputRms >= MIN_INPUT_RMS_WITH_GAIN &&
                    chunk.rms >= START_OUTPUT_RMS
                )
        }

        private fun AudioChunk.durationMillis(): Long {
            if (sampleRate <= 0) return 0L
            return (sampleCount * 1000L) / sampleRate
        }

        data class VoiceState(
            val keepAudio: Boolean = false,
            val clearWindow: Boolean = false,
        )

        private companion object {
            const val START_INPUT_RMS = 0.010
            const val MIN_INPUT_RMS_WITH_GAIN = 0.004
            const val START_OUTPUT_RMS = 0.030
            const val MIN_ACTIVE_VOICE_MS = 2_500L
            const val KEEP_AUDIO_AFTER_VOICE_MS = 900L
            const val SCORE_AFTER_VOICE_MS = 1_200L
        }
    }

    private fun buildDiagnostics(): String {
        val voiceStatus =
            voiceDetectors.joinToString(separator = ", ") { detector ->
                "${detector.modelName}:${if (detector.isReady) "ready" else "stub"}"
            }
        val sttStatus = "${speechRecognizer.engineName}:${if (speechRecognizer.isReady) "ready" else "fallback"}"
        val textStatus = "${textClassifier.engineName}:${if (textClassifier.isReady) "ready" else "fallback"}"
        return "stt[$sttStatus] text[$textStatus] voice[$voiceStatus]"
    }

    private companion object {
        const val VOICE_INFERENCE_INTERVAL_MS = 2_000L
        const val VOICE_WINDOW_MIN_RMS = 0.004
        const val TEXT_INFERENCE_INTERVAL_MS = 650L
        const val LOW_AUDIO_RMS = 0.01
        const val MAX_TRANSCRIPT_CHARS = 600
        const val MIN_PARTIAL_CHARS = 8
        const val MIN_TEXT_WORDS = 5
        const val MAX_TEXT_WORDS = 40
        const val MAX_SPEECH_RESULTS_PER_TICK = 3
    }

    private object AudioRecordWindow {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES = 64_600
        const val MIN_SCORING_SAMPLES = SAMPLES
    }
}
