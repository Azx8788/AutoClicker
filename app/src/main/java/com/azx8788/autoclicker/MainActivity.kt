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
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var alphaSeek: SeekBar
    private var receiver: BroadcastReceiver? = null
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        countText = findViewById(R.id.clicker_count)
        alphaLabel = findViewById(R.id.alpha_label)
        alphaSeek = findViewById(R.id.seek_alpha)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { addClicker() }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { showRemovePicker() }
        findViewById<Button>(R.id.btn_params).setOnClickListener { showParamsPicker() }
        findViewById<Button>(R.id.btn_show_panel).setOnClickListener { showPanel() }
        findViewById<Button>(R.id.btn_close_panel).setOnClickListener { closePanel() }

        findViewById<RadioGroup>(R.id.rg_layout).setOnCheckedChangeListener { _, checkedId ->
            if (syncing) return@setOnCheckedChangeListener
            val v = when (checkedId) {
                R.id.rb_h -> "h"
                R.id.rb_min -> "min"
                else -> "v"
            }
            Prefs.setPanelLayout(this, v)
            sendReload()
        }

        alphaSeek.max = 90
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                alphaLabel.text = "悬浮窗透明度 ${p + 10}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Prefs.setPanelAlpha(this@MainActivity, alphaSeek.progress + 10)
                sendReload()
            }
        })

        findViewById<CheckBox>(R.id.cb_touch_stop).setOnCheckedChangeListener { _, checked ->
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

    // ================= 状态 / 刷新 =================

    private fun updateStatus() {
        val acc = ClickAccessibilityService.ready
        statusText.text = if (acc) "无障碍服务：已连接 ✓" else "无障碍服务：未开启"
        statusText.setTextColor(if (acc) Color.GREEN else Color.RED)
    }

    private fun refreshManager() {
        syncing = true
        countText.text = "当前共 ${Prefs.getActions(this).size} 个点击器"
        val v = Prefs.getPanelLayout(this)
        findViewById<RadioGroup>(R.id.rg_layout).check(
            when (v) {
                "h" -> R.id.rb_h
                "min" -> R.id.rb_min
                else -> R.id.rb_v
            })
        val alpha = Prefs.getPanelAlpha(this)
        alphaSeek.progress = alpha - 10
        alphaLabel.text = "悬浮窗透明度 ${alpha}%"
        findViewById<CheckBox>(R.id.cb_touch_stop).isChecked = Prefs.getTouchStop(this)
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

    private fun showRemovePicker() {
        if (ClickEngine.running) { toast("请先停止连点"); return }
        val list = Prefs.getActions(this)
        if (list.isEmpty()) { toast("还没有点击器，请先添加"); return }
        val names = list.mapIndexed { i, a -> "点击器 ${i + 1}    (${a.x}, ${a.y})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("要删除哪个点击器？")
            .setItems(names) { _, which ->
                val l = Prefs.getActions(this).toMutableList()
                if (which in l.indices) {
                    l.removeAt(which)
                    Prefs.saveActions(this, l)
                    sendReload()
                    refreshManager()
                    toast("已删除点击器 ${which + 1}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showParamsPicker() {
        val list = Prefs.getActions(this)
        if (list.isEmpty()) { toast("还没有点击器，请先添加"); return }
        val names = list.mapIndexed { i, a ->
            "点击器 ${i + 1}    间隔${a.intervalMs}ms 时长${a.pressMs}ms 次数${if (a.repeatCount <= 0) "不限" else a.repeatCount.toString()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("设置哪个点击器？")
            .setItems(names) { _, which -> showParamsEditor(which) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showParamsEditor(index: Int) {
        val list = Prefs.getActions(this).toMutableList()
        val a = list.getOrNull(index) ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val etInterval = EditText(this).apply {
            hint = "间隔ms(≥20)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(a.intervalMs.toString())
        }
        val etPress = EditText(this).apply {
            hint = "时长ms"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(a.pressMs.toString())
        }
        val etRepeat = EditText(this).apply {
            hint = "次数(0=不限)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(a.repeatCount.toString())
        }
        root.addView(etInterval); root.addView(etPress); root.addView(etRepeat)
        MaterialAlertDialogBuilder(this)
            .setTitle("点击器 ${index + 1} 参数")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                a.intervalMs = etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 1000
                a.pressMs = etPress.text.toString().toIntOrNull() ?: 50
                a.repeatCount = etRepeat.text.toString().toIntOrNull() ?: 0
                list[index] = a
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
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }
}
