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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.ActionType
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.model.ClickAction
import com.azx8788.autoclicker.service.ClickAccessibilityService
import com.azx8788.autoclicker.service.FloatingPanelService
import com.azx8788.autoclicker.shizuku.ShizukuAutoGrant
import com.azx8788.autoclicker.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var adapter: ActionAdapter
    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private var receiver: android.content.BroadcastReceiver? = null

    private val actions = mutableListOf<ClickAction>()
    private val editableTypes = listOf(
        ActionType.CLICK, ActionType.SWIPE, ActionType.HOME, ActionType.BACK, ActionType.NOTIFICATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        actions.addAll(Prefs.getActions(this))
        listView = findViewById(R.id.list)
        adapter = ActionAdapter(actions,
            onEdit = { index, action -> showEditDialog(index, action) },
            onToggle = { index -> toggleEnabled(index) })
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        statusText = findViewById(R.id.status)
        runButton = findViewById(R.id.btn_run)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(-1, null) }
        findViewById<Button>(R.id.btn_run).setOnClickListener {
            if (ClickEngine.running) {
                ClickEngine.stop()
            } else {
                val active = actions.filter { it.enabled }
                if (active.isEmpty()) {
                    Toast.makeText(this, "请先添加并启用至少一个点", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.saveActions(this, actions)
                    ClickEngine.start(this, active)
                }
            }
        }
        findViewById<Button>(R.id.btn_float).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startService(Intent(this, FloatingPanelService::class.java))
            }
        }
        findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
            ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = true)
        }
        findViewById<Button>(R.id.btn_open_acc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Actions.STATE -> updateRun(i.getBooleanExtra(Actions.EXTRA_RUNNING, false))
                    ClickAccessibilityService.STATE_ACTION -> updateStatus()
                }
            }
        }
        val f = android.content.IntentFilter()
        f.addAction(Actions.STATE)
        f.addAction(ClickAccessibilityService.STATE_ACTION)
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        ShizukuAutoGrant.boot(this)
        updateStatus()
        updateRun(ClickEngine.running)
        ShizukuAutoGrant.requestPermissionIfNeeded(this, showDialog = false)
    }

    private fun updateStatus() {
        val acc = ClickAccessibilityService.ready
        statusText.text = if (acc) "无障碍服务：已连接 ✓" else "无障碍服务：未开启"
        statusText.setTextColor(if (acc) Color.GREEN else Color.RED)
        runButton.isEnabled = acc
    }

    private fun updateRun(running: Boolean) {
        runButton.text = if (running) "■ 停止" else "▶ 开始"
    }

    private fun toggleEnabled(index: Int) {
        if (index < 0 || index >= actions.size) return
        actions[index].enabled = !actions[index].enabled
        Prefs.saveActions(this, actions)
        adapter.notifyItemChanged(index)
        Toast.makeText(this,
            if (actions[index].enabled) "「${actions[index].label}」已启用" else "「${actions[index].label}」已停用",
            Toast.LENGTH_SHORT).show()
    }

    private fun showEditDialog(index: Int, edit: ClickAction?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_action, null)
        val typeSpinner = v.findViewById<Spinner>(R.id.sp_type)
        val typeNames = editableTypes.map { "${it.name} (${it.code})" }
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeNames)

        val etLabel = v.findViewById<EditText>(R.id.et_label)
        val etX = v.findViewById<EditText>(R.id.et_x)
        val etY = v.findViewById<EditText>(R.id.et_y)
        val etX2 = v.findViewById<EditText>(R.id.et_x2)
        val etY2 = v.findViewById<EditText>(R.id.et_y2)
        val etInterval = v.findViewById<EditText>(R.id.et_interval)
        val etPress = v.findViewById<EditText>(R.id.et_press)
        val etRepeat = v.findViewById<EditText>(R.id.et_repeat)

        val a = edit ?: ClickAction()
        etLabel.setText(a.label)
        etX.setText(a.x.toString()); etY.setText(a.y.toString())
        etX2.setText(a.x2.toString()); etY2.setText(a.y2.toString())
        etInterval.setText(a.intervalMs.toString())
        etPress.setText(a.pressMs.toString())
        etRepeat.setText(a.repeatCount.toString())
        typeSpinner.setSelection(editableTypes.indexOf(a.type).coerceAtLeast(0))

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (edit == null) "添加点" else "编辑点")
            .setView(v)
            .setPositiveButton("确定") { _, _ ->
                val t = editableTypes[typeSpinner.selectedItemPosition.coerceAtLeast(0)]
                val na = a.copy(
                    type = t,
                    label = etLabel.text.toString().ifBlank { "点" },
                    x = etX.text.toString().toIntOrNull() ?: 0,
                    y = etY.text.toString().toIntOrNull() ?: 0,
                    x2 = etX2.text.toString().toIntOrNull() ?: 0,
                    y2 = etY2.text.toString().toIntOrNull() ?: 0,
                    intervalMs = etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 1000,
                    pressMs = etPress.text.toString().toIntOrNull() ?: 50,
                    repeatCount = etRepeat.text.toString().toIntOrNull() ?: 0
                )
                if (index >= 0 && index < actions.size) actions[index] = na else actions.add(na)
                Prefs.saveActions(this, actions)
                adapter.notifyDataSetChanged()
            }
        if (index >= 0 && index < actions.size) {
            builder.setNegativeButton("删除") { _, _ ->
                actions.removeAt(index)
                Prefs.saveActions(this, actions)
                adapter.notifyDataSetChanged()
            }
        }
        builder.setNeutralButton("取消", null).show()
    }

    class ActionAdapter(
        private val actions: List<ClickAction>,
        private val onEdit: (Int, ClickAction) -> Unit,
        private val onToggle: (Int) -> Unit
    ) : RecyclerView.Adapter<ActionAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.act_title)
            val detail: TextView = v.findViewById(R.id.act_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_action, parent, false))

        override fun getItemCount(): Int = actions.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val a = actions[pos]
            val prefix = if (a.enabled) "" else "[已停用] "
            h.title.text = "$prefix${a.type.name} · ${a.label} · (${a.x},${a.y})"
            h.detail.text = buildString {
                append("间隔 ${a.intervalMs}ms")
                append(" · 时长 ${a.pressMs}ms")
                append(" · 次数 ${if (a.repeatCount <= 0) "不限" else a.repeatCount}")
                if (a.type == ActionType.SWIPE) append("\n  → (${a.x2},${a.y2})")
            }
            h.itemView.setOnClickListener { onEdit(pos, a) }
            h.itemView.setOnLongClickListener { onToggle(pos); true }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        // 不断引擎：转屏/关界面不应停止连点
    }
}
