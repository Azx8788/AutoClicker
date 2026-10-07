package com.azx8788.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
    private var root: View? = null
    private var runningText: TextView? = null
    private var runButton: Button? = null
    private var dialogView: View? = null
    private var receiver: android.content.BroadcastReceiver? = null

    private val clickers = mutableListOf<ClickAction>()
    private val markers = mutableListOf<TextView>()

    companion object {
        private const val CHAN = "floating_panel"
        private const val NOTIF_ID = 1
        private const val MARKER_SIZE = 48
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    // ================= 界面 =================

    private fun buildPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = dialogBg()
        }
        panel.addView(TextView(this).apply {
            text = "自动点击器"
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        runningText = TextView(this).apply {
            text = "空闲"
            setTextColor(0xFF88FF88.toInt()); textSize = 12f
        }
        panel.addView(runningText)
        runButton = makeButton("▶ 开始连点") { toggleRun() }
        panel.addView(runButton)
        panel.addView(makeButton("＋ 添加点击器") { addClicker() })
        panel.addView(makeButton("－ 减少点击器") { showRemovePicker() })
        panel.addView(makeButton("⚙ 设置") { showSettingsPicker() })
        panel.addView(makeButton("☰ 主界面") {
            startActivity(Intent(this@FloatingPanelService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        panel.addView(makeButton("✕ 关闭悬浮窗") {
            ClickEngine.stop()
            stopSelf()
        })

        val params = overlayParams(focusable = false, centered = false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        panel.setOnTouchListener(DragHandler())
        root = panel
        wm.addView(panel, params)
    }

    private fun buildMarker(a: ClickAction, number: Int): TextView {
        val sizePx = dp(MARKER_SIZE)
        val params = overlayParams(focusable = false, centered = false).apply {
            x = (a.x - sizePx / 2).coerceAtLeast(0)
            y = (a.y - sizePx / 2).coerceAtLeast(0)
        }
        val tv = TextView(this).apply {
            text = number.toString()
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x661A73E8.toInt())
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

    private fun overlayParams(focusable: Boolean, centered: Boolean): WindowManager.LayoutParams {
        val flags = if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (centered) Gravity.CENTER else Gravity.TOP or Gravity.START
        }
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

    private fun dialogBg(): GradientDrawable = GradientDrawable().apply {
        setColor(0xEE222222.toInt())
        cornerRadius = dp(12).toFloat()
    }

    // ================= 点击器数据 =================

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
        val cx = (w / 2 + i * dp(30) - sizePx / 2).coerceAtLeast(0) + sizePx / 2
        val cy = (h / 2 + i * dp(30) - sizePx / 2).coerceAtLeast(0) + sizePx / 2
        val a = ClickAction(
            label = "点击器${i + 1}",
            x = cx,
            y = cy,
            intervalMs = 1000,
            pressMs = 50,
            repeatCount = 0)
        clickers.add(a)
        markers.add(buildMarker(a, clickers.size))
        saveClickers()
        toast("已添加点击器 ${clickers.size}，拖动到目标位置")
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

    // ================= 弹窗 =================

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
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = dialogBg()
        }
        root.addView(TextView(this).apply {
            text = "点击器 ${index + 1} 设置"
            setTextColor(Color.WHITE); textSize = 14f
        })
        val etInterval = EditText(this).apply {
            hint = "间隔ms(≥20)"; inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(a.intervalMs.toString())
        }
        val etPress = EditText(this).apply {
            hint = "时长ms"; inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(a.pressMs.toString())
        }
        val etRepeat = EditText(this).apply {
            hint = "次数(0=不限)"; inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(a.repeatCount.toString())
        }
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
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = dialogBg()
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE); textSize = 14f
        })
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

    private fun showDialogView(v: View) {
        dialogView = v
        wm.addView(v, overlayParams(focusable = true, centered = true))
    }

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
        runningText?.text = if (running) "运行中…" else "空闲"
        runButton?.text = if (running) "■ 停止连点" else "▶ 开始连点"
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
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
