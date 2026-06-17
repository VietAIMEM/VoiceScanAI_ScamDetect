package com.example.my_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import com.example.my_app.ml.AudioChunk
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

class AudioRecordManager(
    private val context: Context,
    private val onAudioChunk: (AudioChunk) -> Unit,
) {
    @Volatile
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var mediaProjection: MediaProjection? = null
    private var worker: Thread? = null
    private var smoothedGain = 1.0
    private val micPreprocessor = SpeechAudioPreprocessor()
    private val screenPreprocessor = SpeechAudioPreprocessor()

    fun start(preferCallAudio: Boolean = false, forceRestart: Boolean = false): Boolean {
        if (isRunning && !forceRestart) return true
        if (isRunning) stop()
        if (!hasMicrophonePermission()) return false

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBufferSize <= 0) return false

        val bufferSize = minBufferSize * 4
        val recorderConfig = createRecorderWithFallback(bufferSize, preferCallAudio) ?: return false
        val record = recorderConfig.record

        audioRecord = record
        isRunning = true
        enableAutomaticGainControl(record)
        enableNoiseSuppressor(record)
        val recordingStarted =
            runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

        if (!recordingStarted) {
            automaticGainControl?.release()
            automaticGainControl = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            record.release()
            audioRecord = null
            isRunning = false
            return false
        }

        worker =
            Thread(
                {
                    val buffer = ShortArray(bufferSize / 2)
                    while (isRunning) {
                        val count =
                            runCatching {
                                record.read(buffer, 0, buffer.size)
                            }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                        if (count > 0) {
                            val rawFrame = buffer.copyOf(count)
                            val cleanedFrame = micPreprocessor.process(rawFrame, count)
                            val inputRms = calculateRms(cleanedFrame, count)
                            val gainedFrame = applyAdaptiveGain(cleanedFrame, count, inputRms)
                            val outputRms = calculateRms(gainedFrame, count)
                            onAudioChunk(
                                AudioChunk(
                                    samples = gainedFrame,
                                    sampleCount = count,
                                    sampleRate = SAMPLE_RATE,
                                    rms = outputRms,
                                    inputRms = inputRms,
                                    gain = smoothedGain,
                                    sourceName = recorderConfig.sourceName,
                                    voiceSamples = rawFrame,
                                ),
                            )
                        } else if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_DEAD_OBJECT) {
                            isRunning = false
                        }
                    }
                },
                "ScamGuard-AudioRecord",
            ).apply { start() }

        return true
    }

    fun startPlaybackCapture(
        projection: MediaProjection,
        forceRestart: Boolean = false,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (isRunning && !forceRestart) return true
        if (isRunning) stop()

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBufferSize <= 0) return false

        val bufferSize = minBufferSize * 4
        val captureConfig =
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        val format =
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        val record =
            runCatching {
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            }.getOrNull() ?: return false

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        mediaProjection = projection
        isRunning = true

        val recordingStarted =
            runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

        if (!recordingStarted) {
            record.release()
            audioRecord = null
            mediaProjection = null
            isRunning = false
            return false
        }

        worker =
            Thread(
                {
                    val buffer = ShortArray(bufferSize / 2)
                    while (isRunning) {
                        val count =
                            runCatching {
                                record.read(buffer, 0, buffer.size)
                            }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                        if (count > 0) {
                            val rawFrame = buffer.copyOf(count)
                            val cleanedFrame = screenPreprocessor.process(rawFrame, count)
                            val inputRms = calculateRms(cleanedFrame, count)
                            onAudioChunk(
                                AudioChunk(
                                    samples = cleanedFrame,
                                    sampleCount = count,
                                    sampleRate = SAMPLE_RATE,
                                    rms = inputRms,
                                    inputRms = inputRms,
                                    gain = 1.0,
                                    sourceName = "SCREEN_AUDIO",
                                    voiceSamples = rawFrame,
                                ),
                            )
                        } else if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_DEAD_OBJECT) {
                            isRunning = false
                        }
                    }
                },
                "ScamGuard-ScreenAudioRecord",
            ).apply { start() }

        return true
    }

    private fun createRecorderWithFallback(
        bufferSize: Int,
        preferCallAudio: Boolean,
    ): RecorderConfig? {
        val sources =
            if (preferCallAudio) {
                listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                    MediaRecorder.AudioSource.MIC to "MIC",
                )
            } else {
                listOf(
                    MediaRecorder.AudioSource.MIC to "MIC",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                )
            }

        return sources.firstNotNullOfOrNull { (source, sourceName) ->
            createRecorder(source, sourceName, bufferSize)
        }
    }

    fun stop() {
        isRunning = false
        worker?.join(500)
        worker = null

        audioRecord?.run {
            runCatching { stop() }
            release()
        }
        automaticGainControl?.release()
        automaticGainControl = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        smoothedGain = 1.0
        micPreprocessor.reset()
        screenPreprocessor.reset()
    }

    private fun createRecorder(source: Int, sourceName: String, bufferSize: Int): RecorderConfig? {
        return try {
            val recorder =
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                RecorderConfig(recorder, sourceName)
            } else {
                recorder.release()
                null
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun enableAutomaticGainControl(record: AudioRecord) {
        if (!AutomaticGainControl.isAvailable()) return
        automaticGainControl =
            runCatching {
                AutomaticGainControl.create(record.audioSessionId)?.apply {
                    enabled = true
                }
            }.getOrNull()
    }

    private fun enableNoiseSuppressor(record: AudioRecord) {
        if (!NoiseSuppressor.isAvailable()) return
        noiseSuppressor =
            runCatching {
                NoiseSuppressor.create(record.audioSessionId)?.apply {
                    enabled = true
                }
            }.getOrNull()
    }

    private fun applyAdaptiveGain(buffer: ShortArray, count: Int, rms: Double): ShortArray {
        val targetGain =
            if (rms < SILENCE_RMS) {
                1.0
            } else {
                (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
            }
        smoothedGain = (GAIN_SMOOTH_OLD * smoothedGain) + (GAIN_SMOOTH_NEW * targetGain)

        if (smoothedGain <= 1.05) return buffer

        val output = ShortArray(count)
        for (index in 0 until count) {
            val amplified = (buffer[index] * smoothedGain).toInt()
            output[index] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    private fun calculateRms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (index in 0 until count) {
            val sample = buffer[index] / SHORT_MAX
            sum += sample * sample
        }
        return sqrt(sum / count).coerceIn(0.0, 1.0)
    }

    private fun hasMicrophonePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val SHORT_MAX = 32768.0
        private const val TARGET_RMS = 0.08
        private const val SILENCE_RMS = 0.004
        private const val MIN_GAIN = 1.0
        private const val MAX_GAIN = 12.0
        private const val GAIN_SMOOTH_OLD = 0.75
        private const val GAIN_SMOOTH_NEW = 0.25
    }

    private data class RecorderConfig(
        val record: AudioRecord,
        val sourceName: String,
    )

    private class SpeechAudioPreprocessor {
        private var highPassPreviousInput = 0.0
        private var highPassPreviousOutput = 0.0
        private var lowPassPreviousOutput = 0.0
        private var noiseFloor = INITIAL_NOISE_FLOOR

        fun process(buffer: ShortArray, count: Int): ShortArray {
            val output = ShortArray(count)
            val frameRms = calculateFrameRms(buffer, count)
            updateNoiseFloor(frameRms)

            val gateStart = noiseFloor * GATE_OPEN_MULTIPLIER
            val gateEnd = noiseFloor * GATE_FULL_MULTIPLIER
            val gateGain =
                when {
                    frameRms <= gateStart -> MIN_GATE_GAIN
                    frameRms >= gateEnd -> 1.0
                    else -> {
                        val ratio = (frameRms - gateStart) / maxOf(gateEnd - gateStart, 1e-6)
                        MIN_GATE_GAIN + ((1.0 - MIN_GATE_GAIN) * ratio)
                    }
                }

            for (index in 0 until count) {
                val sample = buffer[index] / SHORT_MAX
                val highPassed = highPass(sample)
                val lowPassed = lowPass(highPassed)
                val cleaned = (lowPassed * gateGain).coerceIn(-1.0, 1.0)
                output[index] = (cleaned * Short.MAX_VALUE).toInt().toShort()
            }
            return output
        }

        fun reset() {
            highPassPreviousInput = 0.0
            highPassPreviousOutput = 0.0
            lowPassPreviousOutput = 0.0
            noiseFloor = INITIAL_NOISE_FLOOR
        }

        private fun updateNoiseFloor(frameRms: Double) {
            if (frameRms <= noiseFloor * NOISE_UPDATE_MAX_MULTIPLIER) {
                noiseFloor = (NOISE_FLOOR_OLD * noiseFloor) + (NOISE_FLOOR_NEW * frameRms)
            } else {
                noiseFloor = (NOISE_FLOOR_SLOW_OLD * noiseFloor) + (NOISE_FLOOR_SLOW_NEW * frameRms)
            }
            noiseFloor = noiseFloor.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }

        private fun highPass(sample: Double): Double {
            val output = HIGH_PASS_ALPHA * (highPassPreviousOutput + sample - highPassPreviousInput)
            highPassPreviousInput = sample
            highPassPreviousOutput = output
            return output
        }

        private fun lowPass(sample: Double): Double {
            lowPassPreviousOutput += LOW_PASS_ALPHA * (sample - lowPassPreviousOutput)
            return lowPassPreviousOutput
        }

        private fun calculateFrameRms(buffer: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (index in 0 until count) {
                val sample = buffer[index] / SHORT_MAX
                sum += sample * sample
            }
            return sqrt(sum / count).coerceIn(0.0, 1.0)
        }

        private companion object {
            const val SAMPLE_RATE = AudioRecordManager.SAMPLE_RATE.toDouble()
            const val SHORT_MAX = 32768.0
            const val HIGH_PASS_CUTOFF_HZ = 90.0
            const val LOW_PASS_CUTOFF_HZ = 4_200.0
            val HIGH_PASS_ALPHA = exp((-2.0 * PI * HIGH_PASS_CUTOFF_HZ) / SAMPLE_RATE)
            val LOW_PASS_ALPHA = 1.0 - exp((-2.0 * PI * LOW_PASS_CUTOFF_HZ) / SAMPLE_RATE)
            const val INITIAL_NOISE_FLOOR = 0.003
            const val MIN_NOISE_FLOOR = 0.0015
            const val MAX_NOISE_FLOOR = 0.030
            const val NOISE_UPDATE_MAX_MULTIPLIER = 1.8
            const val NOISE_FLOOR_OLD = 0.96
            const val NOISE_FLOOR_NEW = 0.04
            const val NOISE_FLOOR_SLOW_OLD = 0.995
            const val NOISE_FLOOR_SLOW_NEW = 0.005
            const val GATE_OPEN_MULTIPLIER = 1.8
            const val GATE_FULL_MULTIPLIER = 4.0
            const val MIN_GATE_GAIN = 0.18
        }
    }
}
