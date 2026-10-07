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
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.abs

class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private var panelView: LinearLayout? = null
    private var runButton: Button? = null
    private var dialogView: View? = null
    private var receiver: BroadcastReceiver? = null
    private var panelAlpha = 70
    private var panelScale = 100
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
        panelScale = Prefs.getPanelScale(this)
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
            orientation = if (layout == "v") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = panelBg()
        }

        val run = iconButton(if (ClickEngine.running) "■" else "▶")
        run.setOnTouchListener(TapDragHandler(panel) { toggleRun() })
        panel.addView(run)
        runButton = run

        if (layout == "min") {
            val close = iconButton("✕")
            close.setOnTouchListener(TapDragHandler(panel) { closePanel() })
            panel.addView(close)
        } else {
            val add = iconButton("＋")
            add.setOnTouchListener(TapDragHandler(panel) { addClicker() })
            panel.addView(add)

            val remove = iconButton("－")
            remove.setOnTouchListener(TapDragHandler(panel) { showRemovePicker() })
            panel.addView(remove)

            val settings = iconButton("⚙")
            settings.setOnTouchListener(TapDragHandler(panel) { showSettingsMenu() })
            panel.addView(settings)

            val main = iconButton("☰")
            main.setOnTouchListener(TapDragHandler(panel) {
                startActivity(Intent(this@FloatingPanelService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            panel.addView(main)

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

    private fun iconButton(symbol: String): Button {
        val size = dp(btnSizeDp())
        val margin = dp((2 * panelScale / 100f).toInt().coerceAtLeast(1))
        return Button(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            textSize = 15f * panelScale / 100f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(8).toFloat() * panelScale / 100f
            }
        }
    }

    private fun btnSizeDp(): Int = (BTN_SIZE * panelScale / 100f).toInt().coerceAtLeast(20)

    // ================= 点击器 =================

    private fun markerParams(): WindowManager.LayoutParams {
        val sizePx = dp(Prefs.getMarkerSize(this))
        return WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun buildMarker(a: ClickAction, number: Int): TextView {
        val markerDp = Prefs.getMarkerSize(this)
        val sizePx = dp(markerDp)
        val params = markerParams().apply {
            x = (a.x - sizePx / 2).coerceAtLeast(0)
            y = (a.y - sizePx / 2).coerceAtLeast(0)
        }
        val tv = TextView(this).apply {
            text = number.toString()
            setTextColor(Color.WHITE)
            textSize = (markerDp * 0.35f).coerceAtLeast(10f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        applyMarkerStyle(tv, a)
        tv.setOnTouchListener(DragHandler { x, y ->
            a.x = x + sizePx / 2
            a.y = y + sizePx / 2
            saveClickers()
        })
        wm.addView(tv, params)
        return tv
    }

    /** 圆点状态颜色：灰=已停用、绿=正在点击、蓝=待机（已启用未运行） */
    private fun applyMarkerStyle(tv: TextView, a: ClickAction) {
        val fill: Int
        val stroke: Int
        if (!a.enabled) {
            fill = 0x66888888.toInt(); stroke = 0xFF9E9E9E.toInt()
        } else if (ClickEngine.running) {
            fill = 0xCC22C55E.toInt(); stroke = 0xFF15803D.toInt()
        } else {
            fill = 0x991A73E8.toInt(); stroke = 0xFF1A73E8.toInt()
        }
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), stroke)
        }
    }

    private fun refreshMarkerStyles() {
        markers.forEachIndexed { i, tv -> clickers.getOrNull(i)?.let { applyMarkerStyle(tv, it) } }
    }

    private fun reloadMarkers() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        clickers.forEachIndexed { i, a -> markers.add(buildMarker(a, i + 1)) }
        updateRunState(ClickEngine.running)
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
        val cx = (w / 2 + (i % 5) * dp(30)).coerceAtMost(w - sizePx / 2)
        val cy = (h / 2 + (i % 5) * dp(30)).coerceAtMost(h - sizePx / 2)
        val a = ClickAction(label = "点击器${i + 1}", x = cx, y = cy,
            intervalMs = 1000, pressMs = 50, repeatCount = 0)
        clickers.add(a)
        markers.add(buildMarker(a, clickers.size))
        saveClickers()
        toast("已添加点击器 ${clickers.size}，拖到目标位置")
    }

    private fun showRemovePicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        if (clickers.isEmpty()) { toast("没有可移除的点击器"); return }
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("勾选要删除的点击器（可多选）"))
        val items = clickers.mapIndexed { i, _ -> "点击器 ${i + 1}" }
        val (listView, cbs) = buildCheckList(items, BooleanArray(clickers.size))
        root.addView(listView)
        root.addView(makeButton("删除选中") {
            val toRemove = cbs.mapIndexedNotNull { i, cb -> if (cb.isChecked) i else null }.sortedDescending()
            if (toRemove.isEmpty()) { toast("未勾选任何点击器"); return@makeButton }
            toRemove.forEach { removeClickerAt(it, silent = true) }
            dismissDialog()
            toast("已删除 ${toRemove.size} 个点击器")
        })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    private fun removeClickerAt(index: Int, silent: Boolean = false) {
        if (index < 0 || index >= clickers.size) return
        runCatching { wm.removeView(markers[index]) }
        markers.removeAt(index)
        clickers.removeAt(index)
        markers.forEachIndexed { i, tv -> tv.text = (i + 1).toString() }
        saveClickers()
        if (!silent) toast("已关闭点击器 ${index + 1}")
    }

    private fun reloadAll() {
        panelAlpha = Prefs.getPanelAlpha(this)
        panelScale = Prefs.getPanelScale(this)
        saveCurrentPanelPos()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        buildPanel()
        loadClickers()
        updateRunState(ClickEngine.running)
        if (clickers.isEmpty() && ClickEngine.running) ClickEngine.stop()
    }

    private fun saveCurrentPanelPos() {
        (panelView?.layoutParams as? WindowManager.LayoutParams)?.let {
            Prefs.setPanelPos(this, it.x, it.y)
        }
    }

    private fun rebuildPanel() {
        saveCurrentPanelPos()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        buildPanel()
        updateRunState(ClickEngine.running)
    }

    // ================= 设置弹窗 =================

    private fun showSettingsMenu() {
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("设置"))
        val touchBtn = makeButton("") {}
        fun refreshTouch() {
            touchBtn.text = "点击屏幕立即停止：${if (Prefs.getTouchStop(this)) "开" else "关"}"
        }
        touchBtn.setOnClickListener {
            val v = !Prefs.getTouchStop(this)
            Prefs.setTouchStop(this, v)
            refreshTouch()
        }
        refreshTouch()
        root.addView(touchBtn)
        root.addView(makeButton("启用/停用点击器") { dismissDialog(); showEnableDialog() })
        root.addView(makeButton("点击器参数") { dismissDialog(); showParamsPicker() })
        root.addView(makeButton("显示设置") { dismissDialog(); showDisplayDialog() })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    /** 多选启用/停用 */
    private fun showEnableDialog() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        if (clickers.isEmpty()) { toast("请先添加点击器"); return }
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("勾选=启用，取消勾选=停用"))
        val init = BooleanArray(clickers.size) { clickers[it].enabled }
        val items = clickers.mapIndexed { i, _ -> "点击器 ${i + 1}" }
        val (listView, cbs) = buildCheckList(items, init)
        root.addView(listView)
        root.addView(makeButton("保存") {
            cbs.forEachIndexed { i, cb ->
                clickers.getOrNull(i)?.enabled = cb.isChecked
            }
            saveClickers()
            refreshMarkerStyles()
            dismissDialog()
            toast("已更新启用状态")
        })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    /** 多选清单：返回(可滚动视图, 复选框列表) */
    private fun buildCheckList(items: List<String>, init: BooleanArray): Pair<View, List<CheckBox>> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cbs = mutableListOf<CheckBox>()
        items.forEachIndexed { i, t ->
            val cb = CheckBox(this).apply {
                text = t
                setTextColor(Color.WHITE)
                isChecked = init.getOrElse(i) { false }
            }
            cbs.add(cb)
            col.addView(cb)
        }
        val sv = ScrollView(this)
        sv.addView(col)
        sv.layoutParams = LinearLayout.LayoutParams(dp(240), (items.size * dp(44) + dp(8)).coerceAtMost(dp(340)))
        return Pair(sv, cbs)
    }

    private fun showDisplayDialog() {
        dismissDialog()
        val root = dialogRoot()

        val alphaLabel = label("悬浮窗透明度 ${panelAlpha}%")
        root.addView(alphaLabel)
        val s1 = SeekBar(this).apply { max = 90; progress = panelAlpha - 10 }
        s1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val percent = p + 10
                alphaLabel.text = "悬浮窗透明度 ${percent}%"
                applyPanelAlpha(percent)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(s1)

        val scaleLabel = label("悬浮窗大小 ${panelScale}%")
        root.addView(scaleLabel)
        val s2 = SeekBar(this).apply { max = 90; progress = panelScale - 60 }
        s2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val percent = p + 60
                scaleLabel.text = "悬浮窗大小 ${percent}%"
                applyPanelScale(percent)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(s2)

        val markerLabel = label("点击器圆点大小 ${Prefs.getMarkerSize(this)}dp")
        root.addView(markerLabel)
        val s3 = SeekBar(this).apply {
            max = 52
            progress = Prefs.getMarkerSize(this@FloatingPanelService) - 28
        }
        s3.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                markerLabel.text = "点击器圆点大小 ${p + 28}dp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Prefs.setMarkerSize(this@FloatingPanelService, s3.progress + 28)
                reloadMarkers()
            }
        })
        root.addView(s3)

        root.addView(makeButton("完成") { dismissDialog() })
        showDialogView(root)
    }

    private fun applyPanelAlpha(percent: Int) {
        panelAlpha = percent.coerceIn(10, 100)
        Prefs.setPanelAlpha(this, panelAlpha)
        panelView?.background = panelBg()
    }

    private fun applyPanelScale(percent: Int) {
        panelScale = percent.coerceIn(60, 150)
        Prefs.setPanelScale(this, panelScale)
        rebuildPanel()
    }

    private fun showParamsPicker() {
        if (clickers.isEmpty()) { toast("请先添加点击器"); return }
        dismissDialog()
        val root = dialogRoot()
        root.addView(label("勾选要设置的点击器（可多选）"))
        val items = clickers.mapIndexed { i, _ -> "点击器 ${i + 1}" }
        val (listView, cbs) = buildCheckList(items, BooleanArray(clickers.size))
        root.addView(listView)
        root.addView(makeButton("下一步") {
            val sel = cbs.mapIndexedNotNull { i, cb -> if (cb.isChecked) i else null }
            if (sel.isEmpty()) { toast("未勾选任何点击器"); return@makeButton }
            showParamsEditor(sel)
        })
        root.addView(makeButton("取消") { dismissDialog() })
        showDialogView(root)
    }

    private fun showParamsEditor(indices: List<Int>) {
        val first = clickers.getOrNull(indices.first()) ?: return
        dismissDialog()
        val root = dialogRoot()
        root.addView(label(if (indices.size == 1) "点击器 ${indices.first() + 1} 设置" else "批量设置 ${indices.size} 个点击器"))
        val etInterval = numField(first.intervalMs.toString(), "间隔ms(≥20)")
        val etPress = numField(first.pressMs.toString(), "时长ms")
        val etRepeat = numField(first.repeatCount.toString(), "次数(0=不限)")
        root.addView(etInterval); root.addView(etPress); root.addView(etRepeat)
        root.addView(makeButton("保存") {
            val interval = etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 1000
            val press = etPress.text.toString().toIntOrNull() ?: 50
            val repeat = etRepeat.text.toString().toIntOrNull() ?: 0
            indices.forEach { idx ->
                clickers.getOrNull(idx)?.let { a ->
                    a.intervalMs = interval
                    a.pressMs = press
                    a.repeatCount = repeat
                }
            }
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
        runCatching { wm.addView(v, dialogParams()) }
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
        if (clickers.isEmpty()) { toast("请先添加点击器"); return }
        val active = clickers.filter { it.enabled }
        if (active.isEmpty()) { toast("所有点击器都已停用，请在设置中启用"); return }
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
        refreshMarkerStyles()
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
            .setContentText("运行中：音量减键/点击屏幕可停止")
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
        dismissDialog()
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        super.onDestroy()
    }
}
