package com.example.my_app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var scoreSink: EventChannel.EventSink? = null

    private val scoreReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DetectionScoreStore.ACTION_SCORE_UPDATE) {
                    scoreSink?.success(DetectionScoreStore.latest.toMap())
                }
            }
        }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestCorePermissions" -> {
                        requestCorePermissions()
                        result.success(null)
                    }
                    "openOverlaySettings" -> {
                        openOverlaySettings()
                        result.success(null)
                    }
                    "canDrawOverlay" -> result.success(canDrawOverlay())
                    "startManualDetection" -> {
                        ScamDetectionForegroundService.start(this)
                        result.success(null)
                    }
                    "startScreenAudioDetection" -> {
                        startScreenAudioPermission()
                        result.success(null)
                    }
                    "stopDetection" -> {
                        ScamDetectionForegroundService.stop(this)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                        scoreSink = events
                        scoreSink?.success(DetectionScoreStore.latest.toMap())
                    }

                    override fun onCancel(arguments: Any?) {
                        scoreSink = null
                    }
                },
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerScoreReceiver()
    }

    override fun onDestroy() {
        unregisterReceiver(scoreReceiver)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API, still fine for this FlutterActivity integration.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_AUDIO_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            ScamDetectionForegroundService.startScreenAudio(this, resultCode, data)
        } else {
            DetectionScoreStore.publish(
                this,
                DetectionScore(
                    voice = DetectionScoreStore.latest.voice,
                    text = DetectionScoreStore.latest.text,
                    total = DetectionScoreStore.latest.total,
                    status = "Screen audio permission denied",
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun registerScoreReceiver() {
        val filter = IntentFilter(DetectionScoreStore.ACTION_SCORE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoreReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scoreReceiver, filter)
        }
    }

    private fun requestCorePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_CORE_PERMISSIONS)
    }

    private fun openOverlaySettings() {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        startActivity(intent)
    }

    private fun canDrawOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startScreenAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            DetectionScoreStore.publish(
                this,
                DetectionScore(
                    voice = DetectionScoreStore.latest.voice,
                    text = DetectionScoreStore.latest.text,
                    total = DetectionScoreStore.latest.total,
                    status = "Screen audio requires Android 10+",
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_AUDIO_CAPTURE,
        )
    }

    companion object {
        private const val METHOD_CHANNEL = "scam_call_guard/detection"
        private const val EVENT_CHANNEL = "scam_call_guard/detection_scores"
        private const val REQUEST_CORE_PERMISSIONS = 4201
        private const val REQUEST_SCREEN_AUDIO_CAPTURE = 4202
    }
}
