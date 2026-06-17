package com.example.my_app.ml

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SherpaZipformerSpeechRecognizerEngine : StreamingSpeechRecognizer {
    override val engineName: String
        get() = if (recognizer != null) "sherpa-zipformer" else fallback.engineName
    override val isReady: Boolean
        get() = recognizer != null

    private val fallback = SpeechRecognizerStub()
    private val bufferLock = ReentrantLock()
    private val decodeLock = Any()
    private val completedResults = ConcurrentLinkedQueue<SpeechResult>()
    private val samples = ArrayList<Float>(MAX_UTTERANCE_SAMPLES)
    private val sessionCounter = AtomicInteger(0)
    private val pendingJobs = AtomicInteger(0)

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var closed = true

    private var worker: ExecutorService? = null

    override fun load(context: Context) {
        if (recognizer != null) return
        closed = false
        ensureWorker()

        if (!AssetModelStore(context).hasAsset(AssetModelStore.SHERPA_ZIPFORMER)) {
            fallback.load(context)
            return
        }

        runCatching {
            recognizer =
                OfflineRecognizer(
                    assetManager = context.assets,
                    config =
                        OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                            modelConfig =
                                OfflineModelConfig(
                                    transducer =
                                        OfflineTransducerModelConfig(
                                            encoder = "$MODEL_DIR/encoder-epoch-20-avg-10.int8.onnx",
                                            decoder = "$MODEL_DIR/decoder-epoch-20-avg-10.int8.onnx",
                                            joiner = "$MODEL_DIR/joiner-epoch-20-avg-10.int8.onnx",
                                        ),
                                    tokens = "$MODEL_DIR/config.json",
                                    numThreads = preferredThreadCount(),
                                    debug = false,
                                ),
                            decodingMethod = "greedy_search",
                        ),
                )
            sessionCounter.incrementAndGet()
        }.onFailure {
            recognizer = null
            fallback.load(context)
        }
    }

    override fun acceptAudio(chunk: AudioChunk): SpeechResult? {
        if (recognizer == null) return fallback.acceptAudio(chunk)

        var chunkToDecode: FloatArray? = null
        bufferLock.withLock {
            val incoming = chunk.toFloatArray()
            if (samples.size + incoming.size > MAX_UTTERANCE_SAMPLES) {
                val overflow = samples.size + incoming.size - MAX_UTTERANCE_SAMPLES
                samples.subList(0, overflow.coerceAtMost(samples.size)).clear()
            }
            incoming.forEach { samples.add(it) }
            if (samples.size >= STREAM_CHUNK_SAMPLES && pendingJobs.get() < MAX_PENDING_JOBS) {
                chunkToDecode = drainStreamingChunk()
            }
        }
        chunkToDecode?.let { submitTranscription(it) }
        return null
    }

    override fun resetStream(): SpeechResult? {
        if (recognizer == null) return fallback.resetStream()

        val utterance =
            bufferLock.withLock {
                if (samples.size < MIN_TRANSCRIBE_SAMPLES) {
                    samples.clear()
                    return null
                }
                FloatArray(samples.size) { index -> samples[index] }.also {
                    samples.clear()
                }
            }

        submitTranscription(utterance)
        return null
    }

    override fun pollResult(): SpeechResult? {
        return completedResults.poll()
    }

    override fun close() {
        fallback.close()
        closed = true
        completedResults.clear()
        bufferLock.withLock { samples.clear() }

        val recognizerToRelease = recognizer
        recognizer = null
        sessionCounter.incrementAndGet()
        if (recognizerToRelease != null) {
            runCatching {
                ensureWorker().execute {
                    synchronized(decodeLock) {
                        recognizerToRelease.release()
                    }
                }
            }.onFailure {
                synchronized(decodeLock) {
                    recognizerToRelease.release()
                }
            }
        }
        worker?.shutdown()
        worker = null
    }

    private fun submitTranscription(utterance: FloatArray) {
        val currentRecognizer = recognizer ?: return
        if (closed || pendingJobs.get() >= MAX_PENDING_JOBS) return

        val sessionId = sessionCounter.get()
        try {
            pendingJobs.incrementAndGet()
            ensureWorker().execute {
                try {
                    val text =
                        synchronized(decodeLock) {
                            if (closed || recognizer == null) {
                                ""
                            } else {
                                runCatching {
                                    val stream = currentRecognizer.createStream()
                                    try {
                                        stream.acceptWaveform(utterance, SAMPLE_RATE)
                                        currentRecognizer.decode(stream)
                                        currentRecognizer.getResult(stream).text.trim()
                                    } finally {
                                        stream.release()
                                    }
                                }.getOrDefault("")
                            }
                        }

                    if (text.isNotBlank() && !closed && sessionCounter.get() == sessionId) {
                        completedResults.offer(SpeechResult(text = text, isFinal = true))
                    }
                } finally {
                    pendingJobs.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingJobs.decrementAndGet()
        }
    }

    private fun drainStreamingChunk(): FloatArray {
        val chunkSize = samples.size.coerceAtMost(STREAM_CHUNK_SAMPLES)
        val chunk = FloatArray(chunkSize) { index -> samples[index] }
        val keep = STREAM_OVERLAP_SAMPLES.coerceAtMost(samples.size)
        val tail = samples.takeLast(keep)
        samples.clear()
        samples.addAll(tail)
        return chunk
    }

    private fun ensureWorker(): ExecutorService {
        val current = worker
        if (current != null && !current.isShutdown) return current

        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ScamGuard-SherpaZipformer").apply {
                isDaemon = true
            }
        }.also {
            worker = it
        }
    }

    private fun preferredThreadCount(): Int {
        return (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
    }

    private companion object {
        const val MODEL_DIR = "models/former-30M-RNNT-6000h"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val MIN_TRANSCRIBE_SECONDS = 0.5
        const val STREAM_CHUNK_SECONDS = 1
        const val STREAM_OVERLAP_MS = 120
        const val MAX_UTTERANCE_SECONDS = 4
        const val MAX_PENDING_JOBS = 1
        const val MIN_TRANSCRIBE_SAMPLES = (SAMPLE_RATE * MIN_TRANSCRIBE_SECONDS).toInt()
        const val STREAM_CHUNK_SAMPLES = SAMPLE_RATE * STREAM_CHUNK_SECONDS
        const val STREAM_OVERLAP_SAMPLES = SAMPLE_RATE * STREAM_OVERLAP_MS / 1000
        const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * MAX_UTTERANCE_SECONDS
    }
}
