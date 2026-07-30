package com.daidai.panel

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A draggable floating log window that displays real-time log output
 * from the Go backend process. Useful for debugging on-device.
 */
class LogOverlayWindow(private val context: Context) {

    companion object {
        private const val TAG = "LogOverlay"
        private const val MAX_LOG_LINES = 500
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var logTextView: TextView? = null
    private var scrollView: ScrollView? = null
    private var isShowing = false

    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    // 节流渲染：高频输出时合并多次 setText，100ms 内只刷一次
    private val renderThrottleMs = 100L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val renderRunnable = Runnable { flushRender() }
    private var renderPending = false

    fun isShowing(): Boolean = isShowing

    fun show() {
        if (isShowing) return

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(280),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START

        rootView = createView()
        setupDragBehavior(rootView!!, layoutParams)

        try {
            windowManager?.addView(rootView, layoutParams)
            isShowing = true
            Log.i(TAG, "Log overlay window shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.removeCallbacks(renderRunnable)
        renderPending = false
        try {
            windowManager?.removeView(rootView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
        isShowing = false
    }

    fun appendLog(level: String, tag: String, msg: String) {
        if (!isShowing) return

        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $level/$tag: $msg\n"

        // 跨线程安全：所有 logBuilder 访问统一在 mainHandler 上执行
        mainHandler.post {
            logBuilder.append(line)
            // Trim if too long
            val lines = logBuilder.toString().split("\n")
            if (lines.size > MAX_LOG_LINES) {
                logBuilder.setLength(0)
                lines.takeLast(MAX_LOG_LINES).forEach { l ->
                    if (l.isNotEmpty()) logBuilder.append(l).append("\n")
                }
            }
            // 节流：100ms 内只渲染最后一次
            if (!renderPending) {
                renderPending = true
                mainHandler.postDelayed(renderRunnable, renderThrottleMs)
            }
        }
    }

    private fun flushRender() {
        renderPending = false
        logTextView?.text = logBuilder.toString()
        scrollView?.post { scrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun createView(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0222222.toInt())
            setPadding(8, 8, 8, 8)
        }

        // Header bar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }

        val title = TextView(context).apply {
            text = "呆呆面板日志"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        header.addView(title)

        val scrollToggle = ToggleButton(context).apply {
            textOn = "自动滚动 开"
            textOff = "自动滚动 关"
            isChecked = true
            textSize = 11f
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    scrollView?.post { scrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(8, 0, 8, 0)
            layoutParams = params
        }
        header.addView(scrollToggle)

        val clearBtn = TextView(context).apply {
            text = "清空"
            setTextColor(0xFFFF6666.toInt())
            textSize = 13f
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                logBuilder.setLength(0)
                logTextView?.text = ""
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }
        header.addView(clearBtn)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFF4444.toInt())
            textSize = 16f
            setPadding(16, 4, 16, 4)
            setOnClickListener { hide() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }
        header.addView(closeBtn)

        container.addView(header)

        // Log scroll view
        scrollView = ScrollView(context).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
        }

        logTextView = TextView(context).apply {
            text = logBuilder.toString()
            setTextColor(0xFF00FF00.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 4, 4, 4)
        }
        scrollView?.addView(logTextView)
        container.addView(scrollView)

        return container
    }

    private fun setupDragBehavior(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx > 10 || dy > 10 || dx < -10 || dy < -10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY - dy.toInt()
                        params.gravity = Gravity.TOP or Gravity.START
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
