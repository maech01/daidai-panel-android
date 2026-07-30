package com.daidai.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

class PanelService : Service() {

    companion object {
        private const val TAG = "DaidaiPanel"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "daidai_panel_service"
        // WakeLock 续期：每 5 分钟刷新一次 10 分钟 timeout。
        // 这样如果 service 异常没走 onDestroy，10 分钟后仍会释放。
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60 * 1000
        private const val WAKE_LOCK_RENEW_MS = 5L * 60 * 1000

        const val PANEL_PORT = 5700

        @Volatile
        private var serverProcess: Process? = null

        @Volatile
        private var logOverlay: LogOverlayWindow? = null

        fun isServerRunning(): Boolean {
            return try {
                val url = URL("http://127.0.0.1:$PANEL_PORT/api/v1/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (e: Exception) {
                false
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewer = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                // 续期：释放旧锁再重新获取（acquire with timeout 不支持 in-place 续期）
                lock.release()
            }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("呆呆面板正在启动..."))

        // Show log overlay for debugging (requires overlay permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            logOverlay = LogOverlayWindow(this)
            logOverlay?.show()
        }

        Thread {
            try {
                setupEnvironment()
                startServer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start panel", e)
                appendOverlay("E", TAG, "Failed to start panel: ${e.message}")
                updateNotification("呆呆面板启动失败: ${e.message}")
            }
        }.start()

        return START_STICKY
    }

    private fun appendOverlay(level: String, tag: String, msg: String) {
        logOverlay?.appendLog(level, tag, msg)
    }

    private fun setupEnvironment() {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val filesDir = filesDir.absolutePath
        val panelDir = File(filesDir, "panel")
        val binDir = File(panelDir, "bin")
        val webDir = File(panelDir, "web")
        val dataDir = File(panelDir, "data")

        binDir.mkdirs()
        webDir.mkdirs()
        dataDir.mkdirs()

        Log.i(TAG, "nativeLibDir: $nativeLibDir")
        appendOverlay("I", TAG, "nativeLibDir: $nativeLibDir")
        Log.i(TAG, "filesDir: $filesDir")
        appendOverlay("I", TAG, "filesDir: $filesDir")
        Log.i(TAG, "panelDir: ${panelDir.absolutePath}")
        appendOverlay("I", TAG, "panelDir: ${panelDir.absolutePath}")

        // The Go binary is packaged as libdaidai-server.so in jniLibs.
        // Android automatically extracts lib*.so files to nativeLibraryDir.
        val nativeServer = File(nativeLibDir, "libdaidai-server.so")
        val serverBinary = File(binDir, "daidai-server")

        if (nativeServer.exists()) {
            Log.i(TAG, "Found native server: ${nativeServer.absolutePath} (${nativeServer.length()} bytes)")
            appendOverlay("I", TAG, "Native server found: ${nativeServer.length()} bytes")
            nativeServer.copyTo(serverBinary, overwrite = true)
            serverBinary.setExecutable(true, true)
            Log.i(TAG, "Server binary copied to ${serverBinary.absolutePath}")
            appendOverlay("I", TAG, "Server binary copied to bin/")
        } else {
            Log.e(TAG, "Native server not found at ${nativeServer.absolutePath}")
            appendOverlay("E", TAG, "Native server NOT FOUND at $nativeLibDir")
            File(nativeLibDir).listFiles()?.forEach { f ->
                Log.i(TAG, "  nativeLibDir contains: ${f.name} (${f.length()} bytes)")
                appendOverlay("I", TAG, "  nativeLib: ${f.name} (${f.length()}B)")
            }
        }

        // Fallback: check assets for the binary
        if (!serverBinary.exists() || serverBinary.length() < 100000) {
            try {
                Log.i(TAG, "Trying assets for server binary...")
                appendOverlay("I", TAG, "Trying assets for server binary...")
                val assetStream = assets.open("daidai-server")
                assetStream.use { input ->
                    FileOutputStream(serverBinary).use { output ->
                        input.copyTo(output)
                    }
                }
                serverBinary.setExecutable(true, true)
                Log.i(TAG, "Extracted server binary from assets (${serverBinary.length()} bytes)")
                appendOverlay("I", TAG, "Server binary from assets: ${serverBinary.length()}B")
            } catch (e: IOException) {
                Log.e(TAG, "Server binary not found in assets either", e)
                appendOverlay("E", TAG, "Server binary not in assets: ${e.message}")
            }
        }

        // Extract web frontend assets
        val webIndexFile = File(webDir, "index.html")
        if (!webIndexFile.exists()) {
            try {
                Log.i(TAG, "Extracting web assets from web-dist.zip...")
                appendOverlay("I", TAG, "Extracting web assets...")
                val webZipStream = assets.open("web-dist.zip")
                ZipInputStream(webZipStream).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val entryFile = File(webDir, entry!!.name)
                        if (entry!!.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                }
                Log.i(TAG, "Extracted web assets to ${webDir.absolutePath}")
                appendOverlay("I", TAG, "Web assets extracted OK")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to extract web assets", e)
                appendOverlay("E", TAG, "Web extraction failed: ${e.message}")
            }
        } else {
            Log.i(TAG, "Web assets already exist at ${webDir.absolutePath}")
            appendOverlay("I", TAG, "Web assets already exist")
        }

        // Extract built-in runtimes
        val runtimeDir = File(panelDir, "runtime")
        val termuxPrefix = File(runtimeDir, "termux-prefix/usr")
        val termuxBin = File(termuxPrefix, "bin")

        if (!termuxBin.exists()) {
            extractRuntime(runtimeDir, termuxPrefix, termuxBin)
        } else {
            Log.i(TAG, "Runtime already exists at ${termuxPrefix.absolutePath}")
            appendOverlay("I", TAG, "Runtime already exists")
            makeBinariesExecutable(termuxBin, termuxPrefix)
        }
    }

    private fun extractRuntime(runtimeDir: File, termuxPrefix: File, termuxBin: File) {
        try {
            Log.i(TAG, "Extracting runtime from runtime-arm64.tar.gz...")
            appendOverlay("I", TAG, "Extracting runtime (37.9MB)...")
            val runtimeStream = assets.open("runtime-arm64.tar.gz")
            runtimeDir.mkdirs()

            val tempArchive = File(runtimeDir, "runtime-arm64.tar.gz")
            runtimeStream.use { input ->
                FileOutputStream(tempArchive).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Runtime archive written to ${tempArchive.absolutePath} (${tempArchive.length()} bytes)")
            appendOverlay("I", TAG, "Archive written: ${tempArchive.length()}B")

            val tarProcess = ProcessBuilder(
                "/system/bin/tar", "xzf", tempArchive.absolutePath, "-C", runtimeDir.absolutePath
            ).redirectErrorStream(true).start()
            val tarExit = tarProcess.waitFor()
            val tarOutput = tarProcess.inputStream.bufferedReader().readText()
            Log.i(TAG, "Tar exit code: $tarExit, output: $tarOutput")
            appendOverlay("I", TAG, "Tar exit=$tarExit")

            tempArchive.delete()
            makeBinariesExecutable(termuxBin, termuxPrefix)

            Log.i(TAG, "Termux runtime extraction complete at ${termuxPrefix.absolutePath}")
            appendOverlay("I", TAG, "Runtime extraction complete")
            if (termuxBin.exists()) {
                val bins = termuxBin.listFiles()
                Log.i(TAG, "Bin directory contains ${bins?.size ?: 0} files")
                appendOverlay("I", TAG, "Bin dir: ${bins?.size ?: 0} files")
                bins?.take(10)?.forEach { f ->
                    Log.i(TAG, "  bin/${f.name}")
                    appendOverlay("I", TAG, "  bin/${f.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract runtime", e)
            appendOverlay("E", TAG, "Runtime extraction failed: ${e.message}")
        }
    }

    private fun makeBinariesExecutable(termuxBin: File, termuxPrefix: File) {
        if (termuxBin.exists()) {
            termuxBin.listFiles()?.forEach { file ->
                file.setExecutable(true, true)
            }
        }
        val termuxLib = File(termuxPrefix, "lib")
        if (termuxLib.exists()) {
            termuxLib.listFiles()?.forEach { file ->
                file.setReadable(true, true)
                if (file.name.endsWith(".so")) {
                    file.setExecutable(true, true)
                }
            }
        }
    }

    private fun startServer() {
        val filesDir = filesDir.absolutePath
        val panelDir = File(filesDir, "panel")
        val binDir = File(panelDir, "bin")
        val webDir = File(panelDir, "web")
        val dataDir = File(panelDir, "data")
        val scriptsDir = File(dataDir, "scripts")
        val logDir = File(dataDir, "logs")

        scriptsDir.mkdirs()
        logDir.mkdirs()
        File(panelDir, "tmp").mkdirs()

        val serverBinary = File(binDir, "daidai-server")
        if (!serverBinary.exists() || serverBinary.length() < 100000) {
            Log.e(TAG, "Server binary not found or too small: ${serverBinary.absolutePath} (${if (serverBinary.exists()) serverBinary.length() else 0} bytes)")
            appendOverlay("E", TAG, "Server binary not found!")
            throw IOException("Server binary not found at ${serverBinary.absolutePath}")
        }

        Log.i(TAG, "Server binary: ${serverBinary.absolutePath} (${serverBinary.length()} bytes)")
        appendOverlay("I", TAG, "Server binary: ${serverBinary.length()}B OK")

        val runtimeDir = File(panelDir, "runtime")
        val termuxPrefix = File(runtimeDir, "termux-prefix/usr")
        val termuxBin = File(termuxPrefix, "bin")
        val termuxLib = File(termuxPrefix, "lib")

        val pathEnv = listOf(
            binDir.absolutePath,
            termuxBin.absolutePath,
            "/system/bin",
            "/system/xbin",
            "/vendor/bin"
        ).joinToString(":")

        val ldLibraryPath = listOf(
            termuxLib.absolutePath,
            "/system/lib64",
            "/system/lib",
            "/vendor/lib64",
            "/vendor/lib"
        ).joinToString(":")

        val env = mutableMapOf<String, String>()
        env["DAIDAI_CONFIG"] = ""
        env["SERVER_PORT"] = PANEL_PORT.toString()
        env["DB_PATH"] = File(dataDir, "daidai.db").absolutePath
        env["WEB_DIR"] = webDir.absolutePath
        env["PATH"] = pathEnv
        env["LD_LIBRARY_PATH"] = ldLibraryPath
        env["HOME"] = panelDir.absolutePath
        env["PREFIX"] = termuxPrefix.absolutePath
        env["TERMUX_PREFIX"] = termuxPrefix.absolutePath
        env["PYTHONHOME"] = termuxPrefix.absolutePath
        env["NODE_PATH"] = File(termuxPrefix, "lib/node_modules").absolutePath
        env["TMPDIR"] = File(panelDir, "tmp").absolutePath
        env["TZ"] = "Asia/Shanghai"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["DAIDAI_ANDROID_APP"] = "1"
        env["DAIDAI_DATA_DIR"] = dataDir.absolutePath
        env["DAIDAI_SCRIPTS_DIR"] = scriptsDir.absolutePath
        env["DAIDAI_LOG_DIR"] = logDir.absolutePath
        env["DAIDAI_RUNTIME_BIN_DIR"] = termuxBin.absolutePath

        val configFile = File(panelDir, "config.yaml")
        configFile.writeText("""
server:
  port: $PANEL_PORT
  mode: release
  web_dir: ${webDir.absolutePath}
database:
  path: ${dataDir.absolutePath}/daidai.db
jwt:
  secret: ""
  access_token_expire: 480h
  refresh_token_expire: 1440h
data:
  dir: ${dataDir.absolutePath}
  scripts_dir: ${scriptsDir.absolutePath}
  log_dir: ${logDir.absolutePath}
cors:
  origins:
    - "http://127.0.0.1:$PANEL_PORT"
    - "http://localhost:$PANEL_PORT"
        """.trimIndent())

        Log.i(TAG, "Config written to ${configFile.absolutePath}")
        appendOverlay("I", TAG, "Config written OK")
        Log.i(TAG, "Starting server: ${serverBinary.absolutePath}")
        appendOverlay("I", TAG, "Starting server...")
        Log.i(TAG, "PATH: $pathEnv")
        appendOverlay("I", TAG, "PATH=$pathEnv")
        Log.i(TAG, "LD_LIBRARY_PATH: $ldLibraryPath")
        appendOverlay("I", TAG, "LD_LIB_PATH=$ldLibraryPath")

        val processBuilder = ProcessBuilder(listOf(serverBinary.absolutePath))
        processBuilder.directory(panelDir)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(env)

        val process = processBuilder.start()
        serverProcess = process

        // Read server output and forward to both logcat and overlay
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "server: $line")
                    appendOverlay("I", "server", line ?: "")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error reading server output", e)
                appendOverlay("E", TAG, "Read error: ${e.message}")
            }
        }.start()

        // Wait for server to be ready
        var attempts = 0
        while (attempts < 120) {
            if (isServerRunning()) {
                updateNotification("呆呆面板运行中 (端口 $PANEL_PORT)")
                Log.i(TAG, "Server is running on port $PANEL_PORT")
                appendOverlay("I", TAG, "Server running on :$PANEL_PORT ✓")
                return
            }

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                Log.e(TAG, "Server process exited with code $exitCode")
                appendOverlay("E", TAG, "Server EXITED code=$exitCode")
                updateNotification("呆呆面板启动失败 (exit=$exitCode)")
                return
            }

            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
            attempts++
            if (attempts % 10 == 0) {
                Log.i(TAG, "Waiting for server... ($attempts seconds)")
                appendOverlay("I", TAG, "Waiting... ${attempts}s")
            }
        }

        val exitCode = if (process.isAlive) -1 else process.exitValue()
        updateNotification("呆呆面板启动超时 (exit=$exitCode)")
        Log.e(TAG, "Server failed to start within 120 seconds, exit code: $exitCode")
        appendOverlay("E", TAG, "Server timeout after 120s, exit=$exitCode")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "呆呆面板服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持呆呆面板后台运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("呆呆面板")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("呆呆面板")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DaidaiPanel::PanelService"
        ).apply {
            setReferenceCounted(false)
            // 10 分钟超时 + 5 分钟自动续期。即使 service 异常没走 onDestroy，10 分钟后也会自动释放。
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        mainHandler.removeCallbacks(wakeLockRenewer)
        mainHandler.postDelayed(wakeLockRenewer, WAKE_LOCK_RENEW_MS)
    }

    override fun onDestroy() {
        logOverlay?.hide()
        logOverlay = null
        serverProcess?.destroy()
        serverProcess = null
        mainHandler.removeCallbacks(wakeLockRenewer)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
