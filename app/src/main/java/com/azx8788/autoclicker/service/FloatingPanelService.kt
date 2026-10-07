package com.azx8788.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.azx8788.autoclicker.MainActivity
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.util.Prefs

class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var runningText: TextView? = null
    private var receiver: android.content.BroadcastReceiver? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    companion object {
        private const val CHAN = "floating_panel"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        buildPanel()
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Actions.STATE) {
                    updateRunState(i.getBooleanExtra(Actions.EXTRA_RUNNING, false))
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver,
            android.content.IntentFilter(Actions.STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHAN, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHAN)
            .setContentTitle("自动点击器")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    private fun buildPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xDD222222.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        panel.addView(TextView(this).apply {
            text = "自动点击器"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        runningText = TextView(this).apply {
            text = "空闲"
            setTextColor(0xFF88FF88.toInt()); textSize = 12f
        }
        panel.addView(runningText)
        panel.addView(makeButton("▶ 开始") {
            if (!ClickAccessibilityService.ready) { toast("无障碍服务未连接"); return@makeButton }
            val list = Prefs.getActions(this@FloatingPanelService).filter { it.enabled }
            if (list.isEmpty()) { toast("点列表为空"); return@makeButton }
            ClickEngine.start(this@FloatingPanelService, list)
        })
        panel.addView(makeButton("■ 停止") { ClickEngine.stop() })
        panel.addView(makeButton("☰ 主界面") {
            startActivity(Intent(this@FloatingPanelService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        panel.addView(makeButton("✕ 退出") { stopSelf() })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        lp = params
        panel.setOnTouchListener { v, ev -> handleDrag(v, params, ev) }
        root = panel
        wm.addView(panel, params)
        updateRunState(ClickEngine.running)
    }

    private fun handleDrag(v: View, params: WindowManager.LayoutParams, ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = params.x; dragStartY = params.y
                touchStartX = ev.rawX; touchStartY = ev.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = dragStartX + (ev.rawX - touchStartX).toInt()
                params.y = dragStartY + (ev.rawY - touchStartY).toInt()
                try { wm.updateViewLayout(v, params) } catch (t: Throwable) {}
                return true
            }
        }
        return false
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            setTextColor(0xFFFFFFFF.toInt())
        }

    private fun updateRunState(running: Boolean) {
        runningText?.text = if (running) "运行中…" else "空闲"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
