package com.azx8788.autoclicker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.service.ClickAccessibilityService
import com.azx8788.autoclicker.service.FloatingPanelService
import com.azx8788.autoclicker.shizuku.ShizukuAutoGrant
import com.azx8788.autoclicker.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var receiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val btnRun = findViewById<Button>(R.id.btn_run)
        btnRun.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && ClickEngine.running) {
                ClickEngine.stop()
                Toast.makeText(this, "已停止连点", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
        btnRun.setOnClickListener {
            if (ClickEngine.running) {
                ClickEngine.stop()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限或点击 Shizuku 一键授权", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startService(Intent(this, FloatingPanelService::class.java))
            }
        }

        val cbTouchStop = findViewById<CheckBox>(R.id.cb_touch_stop)
        cbTouchStop.isChecked = Prefs.getTouchStop(this)
        cbTouchStop.setOnCheckedChangeListener { _, checked ->
            Prefs.setTouchStop(this, checked)
        }

        findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
            ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = true)
        }
        findViewById<Button>(R.id.btn_open_acc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ClickAccessibilityService.STATE_ACTION) {
                    updateStatus()
                }
            }
        }
        val f = android.content.IntentFilter()
        f.addAction(ClickAccessibilityService.STATE_ACTION)
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        ShizukuAutoGrant.boot(this)
        updateStatus()
        findViewById<CheckBox>(R.id.cb_touch_stop).isChecked = Prefs.getTouchStop(this)
        ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = false)
    }

    private fun updateStatus() {
        val acc = ClickAccessibilityService.ready
        statusText.text = if (acc) "无障碍服务：已连接 ✓" else "无障碍服务：未开启"
        statusText.setTextColor(if (acc) Color.GREEN else Color.RED)
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }
}
