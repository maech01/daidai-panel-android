package com.daidai.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 开机自启接收器。
 *
 * 关键点：
 * - 收到 BOOT_COMPLETED 时 SystemServer 还在初始化（DB mount、PackageManagerService 还没就绪），
 *   立即 startForegroundService 在某些国产 ROM（MIUI/EMUI/ColorOS）会静默失败。
 * - 用 goAsync() 持有 BR 槽位 + 延迟 8 秒（用 SystemClock.elapsedRealtime）等待系统就绪。
 * - 同时监听 QUICKBOOT_POWERON（部分设备用这个代替 BOOT_COMPLETED）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DaidaiBootReceiver"
        // 等系统就绪后 8 秒。SystemServer 初始化通常 5 秒内完成，留余量。
        private const val DELAY_BEFORE_START_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.i(TAG, "Received boot broadcast: $action, delaying ${DELAY_BEFORE_START_MS}ms")
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val serviceIntent = Intent(context, PanelService::class.java)
                // Android 8+ 必须用 startForegroundService
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "PanelService started from boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start PanelService from boot", e)
            } finally {
                pending.finish()
            }
        }, DELAY_BEFORE_START_MS)
    }
}
