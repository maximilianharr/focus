package com.max.focus2

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

private const val DARK = 0xFF23262B.toInt()
private const val BRIGHT = 0xFFF4F5F7.toInt()

private fun blockScreen(
    ctx: Context, title: String, body: String, detail: String,
    buttonText: String, onButton: () -> Unit,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setBackgroundColor(DARK)
    setPadding(64, 0, 64, 0)
    addView(TextView(ctx).apply {
        text = title; textSize = 34f; setTextColor(BRIGHT)
        typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
    })
    addView(TextView(ctx).apply {
        text = body; textSize = 20f; setTextColor(BRIGHT)
        gravity = Gravity.CENTER; setPadding(0, 32, 0, 0)
    })
    addView(TextView(ctx).apply {
        text = detail; textSize = 15f; setTextColor(BRIGHT and 0x00FFFFFF or (0xB0 shl 24))
        gravity = Gravity.CENTER; setPadding(0, 12, 0, 64)
    })
    addView(Button(ctx).apply {
        text = buttonText; textSize = 16f
        setTextColor(DARK); setBackgroundColor(BRIGHT)
        setPadding(80, 32, 80, 32)
        setOnClickListener { onButton() }
    })
}

private fun fullscreenParams(type: Int) = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    type,
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.OPAQUE,
)

class BlockerService : AccessibilityService() {
    companion object {
        @Volatile var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var currentPkg = ""
    private var overlay: View? = null

    override fun onServiceConnected() {
        running = true
        handler.post(tick)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        currentPkg = pkg
        evaluate()
    }

    private val tick = object : Runnable {
        override fun run() {
            accrue()
            evaluate()
            handler.postDelayed(this, 5000)
        }
    }

    // ponytail: adds a flat 5s per 5s tick while a tracked app is foreground
    // and the screen is on; exact-elapsed bookkeeping if drift ever matters.
    private fun accrue() {
        val item = Engine.appItem(currentPkg) ?: return
        if (!Engine.enforcing() || item.allowance == 0) return
        if (Engine.appBlockReason(item) != null) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        io.execute { App.dao.addSeconds(item.value, Engine.today(), 5) }
    }

    private fun evaluate() {
        val item = Engine.appItem(currentPkg)
        val reason = item?.let { Engine.appBlockReason(it) }
        if (reason != null) showOverlay(item.label, reason) else hideOverlay()
    }

    private fun showOverlay(label: String, reason: String) {
        if (overlay != null) return
        val v = blockScreen(this, "Blocked", label, reason, "Go Home") {
            performGlobalAction(GLOBAL_ACTION_HOME)
            hideOverlay()
        }
        try {
            getSystemService(WindowManager::class.java)
                .addView(v, fullscreenParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
            overlay = v
        } catch (_: Exception) {
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }
}

class WatchdogService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lock: View? = null
    private var suppressUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("wd", "Status", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            1,
            Notification.Builder(this, "wd")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Focus active")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        )
        handler.post(check)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideLock()
        super.onDestroy()
    }

    private val check = object : Runnable {
        override fun run() {
            if (Engine.shutdown) {
                hideLock()
                stopSelf()
                return
            }
            if (VpnService.prepare(this@WatchdogService) == null && !DnsVpnService.running) {
                try {
                    startService(Intent(this@WatchdogService, DnsVpnService::class.java))
                } catch (_: Exception) {
                }
            }
            val problems = mutableListOf<String>()
            if (!BlockerService.running) problems.add("Accessibility service")
            if (VpnService.prepare(this@WatchdogService) != null) problems.add("VPN")
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this@WatchdogService, AdminReceiver::class.java)
            if (dpm?.isAdminActive(admin) != true) problems.add("Device admin")

            if (problems.isEmpty()) {
                hideLock()
            } else if (!MainActivity.visible &&
                System.currentTimeMillis() > suppressUntil &&
                Settings.canDrawOverlays(this@WatchdogService)
            ) {
                showLock(problems)
            }
            handler.postDelayed(this, 15000)
        }
    }

    private fun showLock(problems: List<String>) {
        if (lock != null) return
        val v = blockScreen(
            this, "Protection disabled",
            problems.joinToString("\n"),
            "Re-enable now to keep Focus working.",
            "Fix now"
        ) {
            suppressUntil = System.currentTimeMillis() + 90_000
            hideLock()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("perm", true)
            )
        }
        try {
            getSystemService(WindowManager::class.java)
                .addView(v, fullscreenParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
            lock = v
        } catch (_: Exception) {
        }
    }

    private fun hideLock() {
        lock?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        lock = null
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking { App.prefs.edit { it[Prefs.SHUTDOWN] = false } }
                ctx.startForegroundService(Intent(ctx, WatchdogService::class.java))
            } catch (_: Exception) {
            }
            pending.finish()
        }.start()
    }
}

class AdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling this removes Focus uninstall protection."
}
