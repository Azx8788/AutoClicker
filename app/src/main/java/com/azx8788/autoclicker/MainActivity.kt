package com.azx8788.autoclicker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.model.ClickAction
import com.azx8788.autoclicker.service.ClickAccessibilityService
import com.azx8788.autoclicker.service.FloatingPanelService
import com.azx8788.autoclicker.shizuku.ShizukuAutoGrant
import com.azx8788.autoclicker.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var alphaLabel: TextView
    private lateinit var alphaSlider: Slider
    private lateinit var scaleLabel: TextView
    private lateinit var scaleSlider: Slider
    private lateinit var markerLabel: TextView
    private lateinit var markerSlider: Slider
    private lateinit var tgLayout: MaterialButtonToggleGroup
    private lateinit var swTouchStop: MaterialSwitch
    private var receiver: BroadcastReceiver? = null
    private var syncing = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { sendReload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        countText = findViewById(R.id.clicker_count)
        alphaLabel = findViewById(R.id.alpha_label)
        alphaSlider = findViewById(R.id.slider_alpha)
        scaleLabel = findViewById(R.id.scale_label)
        scaleSlider = findViewById(R.id.slider_scale)
        markerLabel = findViewById(R.id.marker_label)
        markerSlider = findViewById(R.id.slider_marker)
        tgLayout = findViewById(R.id.tg_layout)
        swTouchStop = findViewById(R.id.sw_touch_stop)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { addClicker() }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { showRemovePicker() }
        findViewById<Button>(R.id.btn_toggle).setOnClickListener { showToggleDialog() }
        findViewById<Button>(R.id.btn_params).setOnClickListener { showParamsPicker() }
        findViewById<Button>(R.id.btn_show_panel).setOnClickListener { showPanel() }
        findViewById<Button>(R.id.btn_close_panel).setOnClickListener { closePanel() }
        findViewById<Button>(R.id.btn_help).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("使用说明")
                .setMessage(R.string.help_text)
                .setPositiveButton("知道了", null)
                .show()
        }

        tgLayout.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncing) return@addOnButtonCheckedListener
            val v = when (checkedId) {
                R.id.btn_h -> "h"
                R.id.btn_min -> "min"
                else -> "v"
            }
            Prefs.setPanelLayout(this, v)
            sendReload()
        }

        alphaSlider.addOnChangeListener { _, value, fromUser ->
            alphaLabel.text = "悬浮窗透明度 ${value.toInt()}%"
            if (fromUser) {
                Prefs.setPanelAlpha(this, value.toInt())
                scheduleReload()
            }
        }

        scaleSlider.addOnChangeListener { _, value, fromUser ->
            scaleLabel.text = "悬浮窗大小 ${value.toInt()}%"
            if (fromUser) {
                Prefs.setPanelScale(this, value.toInt())
                scheduleReload()
            }
        }

        markerSlider.addOnChangeListener { _, value, fromUser ->
            markerLabel.text = "点击器圆点大小 ${value.toInt()}dp"
            if (fromUser) {
                Prefs.setMarkerSize(this, value.toInt())
                scheduleReload()
            }
        }

        swTouchStop.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            Prefs.setTouchStop(this, checked)
        }

        findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
            ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = true)
        }
        findViewById<Button>(R.id.btn_open_acc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ClickAccessibilityService.STATE_ACTION) updateStatus()
            }
        }
        ContextCompat.registerReceiver(this, receiver,
            IntentFilter(ClickAccessibilityService.STATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        ShizukuAutoGrant.boot(this)
        updateStatus()
        refreshManager()
        ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = false)
    }

    private fun scheduleReload() {
        uiHandler.removeCallbacks(reloadRunnable)
        uiHandler.postDelayed(reloadRunnable, 400)
    }

    // ================= 状态 / 刷新 =================

    private fun updateStatus() {
        val acc = ClickAccessibilityService.ready
        statusText.text = if (acc) "无障碍服务：已连接 ✓" else "无障碍服务：未开启"
        statusText.setTextColor(if (acc) Color.GREEN else Color.RED)
    }

    private fun refreshManager() {
        syncing = true
        countText.text = "当前共 ${Prefs.getActions(this).size} 个点击器"
        when (Prefs.getPanelLayout(this)) {
            "h" -> tgLayout.check(R.id.btn_h)
            "min" -> tgLayout.check(R.id.btn_min)
            else -> tgLayout.check(R.id.btn_v)
        }
        val alpha = Prefs.getPanelAlpha(this)
        alphaSlider.value = alpha.toFloat()
        alphaLabel.text = "悬浮窗透明度 ${alpha}%"
        val scale = Prefs.getPanelScale(this)
        scaleSlider.value = scale.toFloat()
        scaleLabel.text = "悬浮窗大小 ${scale}%"
        val marker = Prefs.getMarkerSize(this)
        markerSlider.value = marker.toFloat()
        markerLabel.text = "点击器圆点大小 ${marker}dp"
        swTouchStop.isChecked = Prefs.getTouchStop(this)
        syncing = false
    }

    // ================= 点击器管理 =================

    private fun addClicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        if (!ensureOverlay()) return
        val list = Prefs.getActions(this).toMutableList()
        val i = list.size
        val dm = resources.displayMetrics
        val cx = (dm.widthPixels / 2 + (i % 5) * dp(30)).coerceAtMost(dm.widthPixels - dp(24))
        val cy = (dm.heightPixels / 2 + (i % 5) * dp(30)).coerceAtMost(dm.heightPixels - dp(24))
        list.add(ClickAction(label = "点击器${i + 1}", x = cx, y = cy,
            intervalMs = 1000, pressMs = 50, repeatCount = 0))
        Prefs.saveActions(this, list)
        showPanelService()
        sendReload()
        refreshManager()
        toast("已添加点击器 ${list.size}，拖动屏幕上的圆点调整位置")
    }

    /** 多选删除 */
    private fun showRemovePicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        val list = Prefs.getActions(this)
        if (list.isEmpty()) { toast("还没有点击器，请先添加"); return }
        val names = list.mapIndexed { i, a -> "点击器 ${i + 1}    (${a.x}, ${a.y})" }.toTypedArray()
        val checked = BooleanArray(list.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("勾选要删除的点击器（可多选）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("删除选中") { _, _ ->
                val toRemove = checked.indices.filter { checked[it] }.sortedDescending()
                if (toRemove.isEmpty()) { toast("未勾选任何点击器"); return@setPositiveButton }
                val l = Prefs.getActions(this).toMutableList()
                toRemove.forEach { if (it in l.indices) l.removeAt(it) }
                Prefs.saveActions(this, l)
                sendReload()
                refreshManager()
                toast("已删除 ${toRemove.size} 个点击器")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 多选启用/停用 */
    private fun showToggleDialog() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        val list = Prefs.getActions(this)
        if (list.isEmpty()) { toast("还没有点击器，请先添加"); return }
        val checked = BooleanArray(list.size) { list[it].enabled }
        val names = list.mapIndexed { i, _ -> "点击器 ${i + 1}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("启用/停用点击器（勾选=启用）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val l = Prefs.getActions(this).toMutableList()
                l.forEachIndexed { i, a -> a.enabled = checked.getOrElse(i) { true } }
                Prefs.saveActions(this, l)
                sendReload()
                toast("已更新启用状态")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 多选参数：先勾选，再批量编辑 */
    private fun showParamsPicker() {
        val list = Prefs.getActions(this)
        if (list.isEmpty()) { toast("还没有点击器，请先添加"); return }
        val names = list.mapIndexed { i, a ->
            "点击器 ${i + 1}    间隔${a.intervalMs}ms 时长${a.pressMs}ms 次数${if (a.repeatCount <= 0) "不限" else a.repeatCount.toString()}"
        }.toTypedArray()
        val checked = BooleanArray(list.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("勾选要设置参数的点击器（可多选）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("下一步") { _, _ ->
                val sel = checked.indices.filter { checked[it] }
                if (sel.isEmpty()) { toast("未勾选任何点击器"); return@setPositiveButton }
                showParamsEditor(sel)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showParamsEditor(indices: List<Int>) {
        val list = Prefs.getActions(this).toMutableList()
        val first = list.getOrNull(indices.first()) ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val etInterval = EditText(this).apply {
            hint = "间隔ms(≥20)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(first.intervalMs.toString())
        }
        val etPress = EditText(this).apply {
            hint = "时长ms"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(first.pressMs.toString())
        }
        val etRepeat = EditText(this).apply {
            hint = "次数(0=不限)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(first.repeatCount.toString())
        }
        root.addView(etInterval); root.addView(etPress); root.addView(etRepeat)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (indices.size == 1) "点击器 ${indices.first() + 1} 参数" else "批量设置 ${indices.size} 个点击器")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                val interval = etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 1000
                val press = etPress.text.toString().toIntOrNull() ?: 50
                val repeat = etRepeat.text.toString().toIntOrNull() ?: 0
                indices.forEach { idx ->
                    list.getOrNull(idx)?.let { a ->
                        a.intervalMs = interval
                        a.pressMs = press
                        a.repeatCount = repeat
                    }
                }
                Prefs.saveActions(this, list)
                sendReload()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 悬浮窗 =================

    private fun showPanel() {
        if (!ensureOverlay()) return
        showPanelService()
        if (!ClickAccessibilityService.ready) toast("提示：无障碍未开启，可点 Shizuku 一键授权")
    }

    private fun closePanel() {
        ClickEngine.stop()
        stopService(Intent(this, FloatingPanelService::class.java))
        toast("已关闭悬浮窗")
    }

    private fun ensureOverlay(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        toast("请先授予悬浮窗权限（可用 Shizuku 一键授权）")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        return false
    }

    private fun showPanelService() {
        startService(Intent(this, FloatingPanelService::class.java))
    }

    private fun sendReload() {
        sendBroadcast(Intent(Actions.RELOAD).setPackage(packageName))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(reloadRunnable)
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }
}
