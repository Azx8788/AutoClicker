package com.azx8788.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.model.ClickAction
import com.azx8788.autoclicker.util.Prefs
import kotlin.math.abs

class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private var panelView: LinearLayout? = null
    private var runButton: Button? = null
    private var receiver: BroadcastReceiver? = null
    private var panelAlpha = 70
    private var panelConsuming = false

    private val clickers = mutableListOf<ClickAction>()
    private val markers = mutableListOf<TextView>()

    companion object {
        private const val CHAN = "floating_panel"
        private const val NOTIF_ID = 1
        private const val MARKER_SIZE = 46
        private const val BTN_SIZE = 34
        private const val ACTION_STOP = "com.azx8788.autoclicker.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) ClickEngine.stop()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        panelAlpha = Prefs.getPanelAlpha(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Actions.STATE -> updateRunState(i.getBooleanExtra(Actions.EXTRA_RUNNING, false))
                    Actions.RELOAD -> reloadAll()
                }
            }
        }
        val f = IntentFilter()
        f.addAction(Actions.STATE)
        f.addAction(Actions.RELOAD)
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        buildPanel()
        loadClickers()
        updateRunState(ClickEngine.running)
    }

    // ================= 控制条 =================

    private fun buildPanel() {
        val layout = Prefs.getPanelLayout(this)
        val panel = PanelLayout(this).apply {
            orientation = if (layout == "h") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = panelBg()
        }
        val run = iconButton(if (ClickEngine.running) "■" else "▶")
        run.setOnTouchListener(TapDragHandler(panel) { toggleRun() })
        panel.addView(run)
        runButton = run
        if (layout != "min") {
            val close = iconButton("✕")
            close.setOnTouchListener(TapDragHandler(panel) { closePanel() })
            panel.addView(close)
        }
        panel.setOnTouchListener(DragHandler { x, y -> Prefs.setPanelPos(this, x, y) })

        val px = Prefs.getPanelX(this)
        val py = Prefs.getPanelY(this)
        val params = panelParams().apply {
            if (px != Int.MIN_VALUE) x = px
            if (py != Int.MIN_VALUE) y = py
        }
        panelView = panel
        wm.addView(panel, params)
    }

    private fun panelParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun iconButton(symbol: String): Button =
        Button(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            textSize = 15f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(BTN_SIZE), dp(BTN_SIZE)).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(8).toFloat()
            }
        }

    // ================= 点击器 =================

    private fun markerParams(): WindowManager.LayoutParams {
        val sizePx = dp(MARKER_SIZE)
        return WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun buildMarker(a: ClickAction, number: Int): TextView {
        val sizePx = dp(MARKER_SIZE)
        val params = markerParams().apply {
            x = (a.x - sizePx / 2).coerceAtLeast(0)
            y = (a.y - sizePx / 2).coerceAtLeast(0)
        }
        val tv = TextView(this).apply {
            text = number.toString()
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x991A73E8.toInt())
                setStroke(dp(2), 0xFF1A73E8.toInt())
            }
        }
        tv.setOnTouchListener(DragHandler { x, y ->
            a.x = x + sizePx / 2
            a.y = y + sizePx / 2
            saveClickers()
        })
        wm.addView(tv, params)
        return tv
    }

    private fun loadClickers() {
        clickers.clear()
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        Prefs.getActions(this).forEachIndexed { i, a ->
            clickers.add(a)
            markers.add(buildMarker(a, i + 1))
        }
    }

    private fun saveClickers() {
        Prefs.saveActions(this, clickers.toList())
    }

    private fun reloadAll() {
        panelAlpha = Prefs.getPanelAlpha(this)
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        buildPanel()
        loadClickers()
        updateRunState(ClickEngine.running)
        if (clickers.isEmpty() && ClickEngine.running) ClickEngine.stop()
    }

    // ================= 运行控制 =================

    private fun toggleRun() {
        if (ClickEngine.running) {
            ClickEngine.stop()
            return
        }
        if (!ClickAccessibilityService.ready) { toast("无障碍服务未连接"); return }
        val active = clickers.filter { it.enabled }
        if (active.isEmpty()) { toast("请先在应用里添加点击器"); return }
        ClickEngine.start(this, active)
    }

    private fun closePanel() {
        ClickEngine.stop()
        stopSelf()
    }

    private fun updateRunState(running: Boolean) {
        runButton?.text = if (running) "■" else "▶"
        val touchable = !running
        markers.forEach { setMarkerTouchable(it, touchable) }
    }

    private fun setMarkerTouchable(tv: TextView, touchable: Boolean) {
        val p = tv.layoutParams as? WindowManager.LayoutParams ?: return
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        p.flags = flags
        runCatching { wm.updateViewLayout(tv, p) }
    }

    // ================= 工具 =================

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(panelAlpha * 255 / 100, 0x22, 0x22, 0x22))
        cornerRadius = dp(12).toFloat()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHAN, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, FloatingPanelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHAN)
            .setContentTitle("自动点击器")
            .setContentText("运行中：音量减键/点击屏幕可停止")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止连点", stopIntent)
            .build()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class PanelLayout(context: Context) : LinearLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN && ClickEngine.running) {
                panelConsuming = true
                ClickEngine.stop()
                toast("已停止连点")
                return true
            }
            if (panelConsuming) {
                if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                    panelConsuming = false
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private inner class DragHandler(private val onEnd: ((Int, Int) -> Unit)? = null) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var tx = 0f
        private var ty = 0f

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y; tx = ev.rawX; ty = ev.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (ev.rawX - tx).toInt()
                    p.y = startY + (ev.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(v, p) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    onEnd?.invoke(p.x, p.y)
                    return true
                }
            }
            return false
        }
    }

    /** 按钮用：轻点=动作，拖动=移动悬浮窗（阈值 8dp） */
    private inner class TapDragHandler(private val target: View, private val onTap: () -> Unit) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var tx = 0f
        private var ty = 0f
        private var dragging = false

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val p = target.layoutParams as? WindowManager.LayoutParams ?: return false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y; tx = ev.rawX; ty = ev.rawY; dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - tx
                    val dy = ev.rawY - ty
                    if (!dragging && abs(dx) + abs(dy) > dp(8)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(target, p) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.setPanelPos(this@FloatingPanelService, p.x, p.y)
                    } else if (ev.action == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        super.onDestroy()
    }
}
