package com.example.my_app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.my_app.ml.AudioChunk
import com.example.my_app.ml.RealtimeDetectionPipeline

class ScamDetectionForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlayController: OverlayController
    private lateinit var audioRecordManager: AudioRecordManager
    private lateinit var detectionPipeline: RealtimeDetectionPipeline
    @Volatile
    private var latestAudioLevel = 0.0
    @Volatile
    private var audioActive = false
    @Volatile
    private var modelsLoading = false
    @Volatile
    private var inactiveStatus = "Waiting for audio"
    @Volatile
    private var captureMode = CaptureMode.NONE

    private val scoreLoop =
        object : Runnable {
            override fun run() {
                val score =
                    if (audioActive) {
                        DetectionScoreStore.latest
                    } else {
                        DetectionScore(
                            voice = DetectionScoreStore.latest.voice,
                            text = DetectionScoreStore.latest.text,
                            total = DetectionScoreStore.latest.total,
                            status = inactiveStatus,
                            updatedAtMillis = System.currentTimeMillis(),
                            conformer = DetectionScoreStore.latest.conformer,
                            aasist = DetectionScoreStore.latest.aasist,
                            textLabel = DetectionScoreStore.latest.textLabel,
                            transcript = DetectionScoreStore.latest.transcript,
                            riskLevel = DetectionScoreStore.latest.riskLevel,
                            riskLabel = DetectionScoreStore.latest.riskLabel,
                        )
                    }
                DetectionScoreStore.publish(this@ScamDetectionForegroundService, score)
                overlayController.update(score)
                handler.postDelayed(this, SCORE_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        overlayController = OverlayController(applicationContext)
        detectionPipeline = RealtimeDetectionPipeline(applicationContext)
        audioRecordManager = AudioRecordManager(applicationContext) { chunk ->
            handleAudioChunk(chunk)
        }
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START_RINGING -> startRinging()
            ACTION_START_CALL_AUDIO -> startDetection(preferCallAudio = true, forceRestartAudio = true)
            ACTION_START_SCREEN_AUDIO -> startScreenAudioDetection(intent)
            else -> startDetection(preferCallAudio = false, forceRestartAudio = false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(scoreLoop)
        audioRecordManager.stop()
        detectionPipeline.close()
        overlayController.hide()
        DetectionScoreStore.publish(
            this,
            DetectionScore(
                voice = 0.0,
                text = 0.0,
                total = 0.0,
                status = "Stopped",
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRinging() {
        if (captureMode == CaptureMode.SCREEN_AUDIO) return
        startForegroundCompat()
        preloadModels()
        overlayController.show()
        handler.removeCallbacks(scoreLoop)
        audioActive = false
        inactiveStatus = "Incoming call - waiting for answer"
        val score =
            DetectionScore(
                voice = DetectionScoreStore.latest.voice,
                text = DetectionScoreStore.latest.text,
                total = DetectionScoreStore.latest.total,
                status = inactiveStatus,
                updatedAtMillis = System.currentTimeMillis(),
            )
        DetectionScoreStore.publish(this, score)
        overlayController.update(score)
        handler.post(scoreLoop)
    }

    private fun startDetection(preferCallAudio: Boolean, forceRestartAudio: Boolean) {
        if (captureMode == CaptureMode.SCREEN_AUDIO && preferCallAudio) {
            inactiveStatus = "Listening to screen audio"
            return
        }
        startForegroundCompat(foregroundType = ForegroundType.MICROPHONE)
        preloadModels()
        val audioStarted =
            audioRecordManager.start(
                preferCallAudio = preferCallAudio,
                forceRestart = forceRestartAudio,
            )
        audioActive = audioStarted
        if (audioStarted) {
            inactiveStatus = "Listening"
            captureMode = CaptureMode.MICROPHONE
        }
        overlayController.show()
        handler.removeCallbacks(scoreLoop)
        if (!audioStarted) {
            captureMode = CaptureMode.NONE
            inactiveStatus = "Audio unavailable during call"
            val score =
                DetectionScore(
                    voice = 0.0,
                    text = 0.0,
                    total = 0.0,
                    status = inactiveStatus,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            DetectionScoreStore.publish(this, score)
            overlayController.update(score)
        }
        handler.post(scoreLoop)
    }

    private fun startScreenAudioDetection(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishAudioUnavailable("Screen audio requires Android 10+")
            return
        }

        startForegroundCompat(foregroundType = ForegroundType.MEDIA_PROJECTION)
        preloadModels()
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (resultCode == 0 || resultData == null) {
            publishAudioUnavailable("Screen audio permission denied")
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection =
            runCatching {
                projectionManager.getMediaProjection(resultCode, resultData)
            }.getOrNull()

        if (projection == null) {
            publishAudioUnavailable("Screen audio capture unavailable")
            return
        }

        val audioStarted =
            audioRecordManager.startPlaybackCapture(
                projection = projection,
                forceRestart = true,
            )
        audioActive = audioStarted
        captureMode = if (audioStarted) CaptureMode.SCREEN_AUDIO else CaptureMode.NONE
        inactiveStatus =
            if (audioStarted) {
                "Listening to screen audio"
            } else {
                "Screen audio capture unavailable"
            }
        overlayController.show()
        handler.removeCallbacks(scoreLoop)
        if (!audioStarted) {
            publishAudioUnavailable(inactiveStatus)
        }
        handler.post(scoreLoop)
    }

    private fun publishAudioUnavailable(status: String) {
        audioActive = false
        captureMode = CaptureMode.NONE
        inactiveStatus = status
        val score =
            DetectionScore(
                voice = DetectionScoreStore.latest.voice,
                text = DetectionScoreStore.latest.text,
                total = DetectionScoreStore.latest.total,
                status = status,
                updatedAtMillis = System.currentTimeMillis(),
            )
        DetectionScoreStore.publish(this, score)
        overlayController.show()
        overlayController.update(score)
    }

    private fun preloadModels() {
        if (modelsLoading) return
        modelsLoading = true
        val loadingScore =
            DetectionScore(
                voice = DetectionScoreStore.latest.voice,
                text = DetectionScoreStore.latest.text,
                total = DetectionScoreStore.latest.total,
                status = "Loading offline models",
                updatedAtMillis = System.currentTimeMillis(),
            )
        DetectionScoreStore.publish(this, loadingScore)
        overlayController.update(loadingScore)

        Thread(
            {
                runCatching { detectionPipeline.load() }
                modelsLoading = false
            },
            "ScamGuard-ModelPreload",
        ).start()
    }

    private fun handleAudioChunk(chunk: AudioChunk) {
        latestAudioLevel = chunk.rms
        val score = detectionPipeline.acceptAudio(chunk) ?: return
        DetectionScoreStore.publish(this, score)
        handler.post {
            overlayController.update(score)
        }
    }

    private fun startForegroundCompat(foregroundType: ForegroundType = ForegroundType.MICROPHONE) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType =
                when (foregroundType) {
                    ForegroundType.MICROPHONE -> {
                        if (hasMicrophonePermission()) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            0
                        }
                    }
                    ForegroundType.MEDIA_PROJECTION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            if (serviceType != 0) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Scam Call Guard")
            .setContentText("Realtime detection service is running")
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Scam detection",
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "scam_detection_service"
        private const val NOTIFICATION_ID = 8102
        private const val SCORE_INTERVAL_MS = 1000L
        private const val ACTION_START = "com.example.my_app.action.START_DETECTION"
        private const val ACTION_START_RINGING = "com.example.my_app.action.START_RINGING"
        private const val ACTION_START_CALL_AUDIO = "com.example.my_app.action.START_CALL_AUDIO"
        private const val ACTION_START_SCREEN_AUDIO = "com.example.my_app.action.START_SCREEN_AUDIO"
        private const val ACTION_STOP = "com.example.my_app.action.STOP_DETECTION"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        fun start(context: Context) {
            val intent = Intent(context, ScamDetectionForegroundService::class.java)
                .setAction(ACTION_START)
            startServiceCompat(context, intent)
        }

        fun startRinging(context: Context) {
            val intent = Intent(context, ScamDetectionForegroundService::class.java)
                .setAction(ACTION_START_RINGING)
            startServiceCompat(context, intent)
        }

        fun startCallAudio(context: Context) {
            val intent = Intent(context, ScamDetectionForegroundService::class.java)
                .setAction(ACTION_START_CALL_AUDIO)
            startServiceCompat(context, intent)
        }

        fun startScreenAudio(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScamDetectionForegroundService::class.java)
                .setAction(ACTION_START_SCREEN_AUDIO)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScamDetectionForegroundService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }

    private enum class ForegroundType {
        MICROPHONE,
        MEDIA_PROJECTION,
    }

    private enum class CaptureMode {
        NONE,
        MICROPHONE,
        SCREEN_AUDIO,
    }
}
