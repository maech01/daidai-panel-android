package com.daidai.panel

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var progressBar: ProgressBar

    /**
     * 接收悬浮窗权限授权结果。
     * 用户从系统设置页授予后回到 App，触发此回调，刷新 Service 的悬浮窗状态。
     * 注：Service 自己内部用 registerReceiver 接收 REFRESH_OVERLAY，所以这里只需要
     * 触发 Service onStartCommand 重新走一遍启动逻辑。
     */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            // 让 Service 重启并显示悬浮窗。PanelService.onStartCommand 每次会检查权限并尝试 show
            try {
                val intent = Intent(this, PanelService::class.java)
                startForegroundService(intent)
            } catch (e: Exception) {
                // ignore
            }
        } else {
            Toast.makeText(this, "未授予悬浮窗权限，日志悬浮窗将无法显示", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request overlay permission for log floating window
        requestOverlayPermission()

        // Create a loading screen
        loadingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 24
            loadingLayout.addView(this, params)
        }
        loadingText = TextView(this).apply {
            text = "正在启动呆呆面板..."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        loadingLayout.addView(loadingText)
        setContentView(loadingLayout)

        // Start the panel foreground service
        val serviceIntent = Intent(this, PanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Request battery optimization exclusion
        requestBatteryOptimizationExemption()

        // Create WebView but don't set as content yet
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            userAgentString = "DaidaiPanel-Android/1.0"
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide loading screen and show WebView
                if (loadingLayout.visibility != View.GONE) {
                    loadingLayout.visibility = View.GONE
                    setContentView(webView)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Load the panel - wait for the server to be ready
        loadPanelWhenReady()
    }

    private fun loadPanelWhenReady() {
        Thread {
            var attempts = 0
            val maxAttempts = 120 // 120 seconds total (runtime extraction takes time)
            while (attempts < maxAttempts) {
                if (PanelService.isServerRunning()) {
                    break
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                attempts++

                // Update loading text with progress
                val msg = when {
                    attempts < 10 -> "正在启动呆呆面板..."
                    attempts < 30 -> "正在解压运行时环境..."
                    attempts < 60 -> "正在初始化后端服务..."
                    attempts < 90 -> "正在加载前端资源..."
                    else -> "启动时间较长，请耐心等待..."
                }
                runOnUiThread { loadingText.text = "$msg (${attempts}s)" }
            }

            runOnUiThread {
                if (PanelService.isServerRunning()) {
                    webView.loadUrl("http://127.0.0.1:${PanelService.PANEL_PORT}")
                } else {
                    Toast.makeText(
                        this,
                        "面板启动失败，请检查日志或重启App",
                        Toast.LENGTH_LONG
                    ).show()
                    loadingText.text = "启动失败，正在重试..."
                    // Retry after a delay
                    webView.postDelayed({ loadPanelWhenReady() }, 5000)
                }
            }
        }.start()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent()
                    intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                // 用 ActivityResultLauncher 接回调，用户授权回来后刷新悬浮窗
                overlayPermissionLauncher.launch(intent)
                Toast.makeText(
                    this,
                    "请授予悬浮窗权限以查看日志",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.parent != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
