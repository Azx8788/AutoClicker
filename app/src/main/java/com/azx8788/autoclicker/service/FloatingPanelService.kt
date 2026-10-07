package com.azx8788.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.azx8788.autoclicker.MainActivity
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.model.ClickAction
import com.azx8788.autoclicker.util.Prefs

class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private var panelView: LinearLayout? = null
    private var runButton: Button? = null
    private var dialogView: View? = null
    private var receiver: android.content.BroadcastReceiver? = null
    private var panelAlpha = 90
    private var panelConsuming = false

    private val clickers = mutableListOf<ClickAction>()
    private val markers = mutableListOf<TextView>()

    companion object {
        private const val CHAN = "floating_panel"
        private const val NOTIF_ID = 1
        private const val MARKER_SIZE = 46
        private const val BTN_SIZE = 46
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
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Actions.STATE) {
                    updateRunState(i.getBooleanExtra(Actions.EXTRA_RUNNING, false))
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver,
            android.content.IntentFilter(Actions.STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        buildPanel()
        loadClickers()
        updateRunState(ClickEngine.running)
    }

    // ================= 控制面板 =================

    private fun buildPanel() {
        val panel = PanelLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = panelBg()
        }
        panelView = panel

        // 拖动条（仅符号）
        val handle = TextView(this).apply {
            text = "⋯"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18))
        }
        handle.setOnTouchListener(DragHandler())
        panel.addView(handle)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = iconButton("▶") { toggleRun() }
        row1.addView(runButton)
        row1.addView(iconButton("＋") { addClicker() })
        row1.addView(iconButton("－") { showRemovePicker() })
        panel.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(iconButton("⚙") { showSettingsMenu() })
        row2.addView(iconButton("☰") {
            startActivity(Intent(this@FloatingPanelService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        row2.addView(iconButton("✕") {
            ClickEngine.stop()
            stopSelf()
        })
        panel.addView(row2)

        val params = panelParams()
        panel.setOnTouchListener(DragHandler())
        wm.addView(panel, params)
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = symbol
            setOnClickListener { onClick() }
            setTextColor(Color.WHITE)
            textSize = 17f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(BTN_SIZE), dp(BTN_SIZE)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(10).toFloat()
            }
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

    private fun addClicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        val sizePx = dp(MARKER_SIZE)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val i = clickers.size
        val cx = (w / 2 + i * dp(30)).coerceAtMost(w - sizePx)
        val cy = (h / 2 + i * dp(30)).coerceAtMost(h - sizePx)
        val a = ClickAction(
            label = "点击器${i + 1}",
            x = cx, y = cy,
            intervalMs = 1000, pressMs = 50, repeatCount = 0)
        clickers.add(a)
        markers.add(buildMarker(a, clickers.size))
        saveClickers()
        toast("已添加点击器 ${clickers.size}，拖到目标位置")
    }

    private fun removeClickerAt(index: Int) {
        if (index < 0 || index >= clickers.size) return
        runCatching { wm.removeView(markers[index]) }
        markers.removeAt(index)
        clickers.removeAt(index)
        markers.forEachIndexed { i, tv -> tv.text = (i + 1).toString() }
        saveClickers()
        toast("已关闭点击器 ${index + 1}")
    }

    // ================= 设置 / 弹窗 =================

    private fun showSettingsMenu() {
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("设置"))
        root.addView(makeButton("点击器参数") { dismissDialog(); showSettingsPicker() })
        root.addView(makeButton("悬浮窗透明度") { dismissDialog(); showOpacityDialog() })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    private fun showOpacityDialog() {
        dismissDialog()
        val root = dialogRoot()
        val label = label("悬浮窗透明度 ${panelAlpha}%")
        root.addView(label)
        val seek = SeekBar(this).apply {
            max = 90
            progress = panelAlpha - 10
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val percent = p + 10
                label.text = "悬浮窗透明度 ${percent}%"
                applyPanelAlpha(percent)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(seek)
        root.addView(makeButton("完成") { dismissDialog() })
        showDialogView(root)
    }

    private fun applyPanelAlpha(percent: Int) {
        panelAlpha = percent.coerceIn(10, 100)
        Prefs.setPanelAlpha(this, panelAlpha)
        panelView?.background = panelBg()
    }

    private fun showRemovePicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        if (clickers.isEmpty()) { toast("没有可移除的点击器"); return }
        showNumberPicker("要关闭数字几的点击器？") { idx -> removeClickerAt(idx) }
    }

    private fun showSettingsPicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        if (clickers.isEmpty()) { toast("请先添加点击器"); return }
        showNumberPicker("设置哪个点击器？") { idx -> showSettingsFor(idx) }
    }

    private fun showSettingsFor(index: Int) {
        val a = clickers.getOrNull(index) ?: return
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("点击器 ${index + 1} 设置"))
        val etInterval = numField(a.intervalMs.toString(), "间隔ms(≥20)")
        val etPress = numField(a.pressMs.toString(), "时长ms")
        val etRepeat = numField(a.repeatCount.toString(), "次数(0=不限)")
        root.addView(etInterval); root.addView(etPress); root.addView(etRepeat)
        root.addView(makeButton("保存") {
            a.intervalMs = etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 1000
            a.pressMs = etPress.text.toString().toIntOrNull() ?: 50
            a.repeatCount = etRepeat.text.toString().toIntOrNull() ?: 0
            saveClickers()
            dismissDialog()
            toast("已保存")
        })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    private fun showNumberPicker(title: String, onPick: (Int) -> Unit) {
        dismissDialog()
        val root = dialogRoot()
        root.addView(label(title))
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        clickers.forEachIndexed { i, _ ->
            if (i > 0 && i % 5 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(row)
            }
            row.addView(numberButton(i + 1) { dismissDialog(); onPick(i) })
        }
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    private fun numField(value: String, hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(value)
        }

    private fun dialogRoot(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = dialogBg()
        }

    private fun label(t: String): TextView =
        TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 14f
        }

    private fun showDialogView(v: View) {
        dialogView = v
        wm.addView(v, dialogParams())
    }

    private fun dialogParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

    private fun dismissDialog() {
        dialogView?.let { runCatching { wm.removeView(it) } }
        dialogView = null
    }

    // ================= 运行控制 =================

    private fun toggleRun() {
        if (ClickEngine.running) {
            ClickEngine.stop()
            return
        }
        if (!ClickAccessibilityService.ready) { toast("无障碍服务未连接"); return }
        val active = clickers.filter { it.enabled }
        if (active.isEmpty()) { toast("请先添加点击器"); return }
        ClickEngine.start(this, active)
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
        cornerRadius = dp(14).toFloat()
    }

    private fun dialogBg(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF0222222.toInt())
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
            .setContentText("悬浮窗运行中（触摸屏幕或按音量减键可停止）")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止连点", stopIntent)
            .build()
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            setTextColor(Color.WHITE)
        }

    private fun numberButton(n: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = n.toString()
            setOnClickListener { onClick() }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
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

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        dismissDialog()
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        super.onDestroy()
    }
}
