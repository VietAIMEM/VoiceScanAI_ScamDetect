package com.example.my_app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: LinearLayout? = null
    private var voiceText: TextView? = null
    private var textText: TextView? = null
    private var totalText: TextView? = null
    private var statusText: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (rootView != null || !canDrawOverlay()) return

        val view = buildView()
        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }

        params = layoutParams
        rootView = view
        windowManager.addView(view, layoutParams)
    }

    fun update(score: DetectionScore) {
        if (rootView == null) show()
        voiceText?.text = "Voice Detect: ${score.voice.toPercent()}"
        textText?.text = "Text Detect: ${score.text.toPercent()}"
        totalText?.text = "Total Detect: ${score.total.toPercent()} | ${score.riskLabel}"
        statusText?.text = score.status
        rootView?.setBackgroundColor(score.overlayColor())
        totalText?.setTextColor(score.totalTextColor())
    }

    fun hide() {
        val view = rootView ?: return
        windowManager.removeView(view)
        rootView = null
        voiceText = null
        textText = null
        totalText = null
        statusText = null
        params = null
    }

    private fun buildView(): LinearLayout {
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xE6202724.toInt())

            statusText = label("Listening")
            voiceText = label("Voice Detect: 0%")
            textText = label("Text Detect: 0%")
            totalText = label("Total Detect: 0%")

            addView(statusText)
            addView(voiceText)
            addView(textText)
            addView(totalText)

            attachDragHandler(this)
        }
    }

    private fun label(value: String): TextView {
        return TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = true
        }
    }

    private fun attachDragHandler(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun canDrawOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun Double.toPercent(): String = "${(coerceIn(0.0, 1.0) * 100).toInt()}%"

    private fun DetectionScore.overlayColor(): Int {
        return when (riskLevel) {
            "high" -> 0xEA9D1C1C.toInt()
            "medium" -> 0xEA6F5200.toInt()
            else -> 0xE6202724.toInt()
        }
    }

    private fun DetectionScore.totalTextColor(): Int {
        return when (riskLevel) {
            "high" -> 0xFFFFFFFF.toInt()
            "medium" -> 0xFFFFF3C4.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }
}
