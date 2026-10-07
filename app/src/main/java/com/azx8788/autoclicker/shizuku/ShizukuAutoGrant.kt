package com.azx8788.autoclicker.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.azx8788.autoclicker.service.ClickAccessibilityService
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

object ShizukuAutoGrant {

    private const val REQUEST_CODE = 10086
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private var booted = false
    private var contextRef: Context? = null

    fun boot(context: Context) {
        if (booted) return
        booted = true
        contextRef = context.applicationContext
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                grantAndEnable(contextRef)
            }
        }
        Shizuku.addBinderReceivedListenerSticky {
            checkAndRequest(showDialog = false)
        }
    }

    fun requestPermissionIfNeeded(context: Context, showDialog: Boolean) {
        contextRef = context.applicationContext
        checkAndRequest(showDialog)
    }

    private fun checkAndRequest(showDialog: Boolean) {
        val context = contextRef ?: return
        if (!Shizuku.pingBinder()) {
            if (showDialog) toast(context, "未检测到 Shizuku，请先启动 Shizuku（ADB/无线调试）")
            return
        }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                if (!isServiceEnabled(context)) grantAndEnable(context)
            }
            showDialog && !Shizuku.shouldShowRequestPermissionRationale() -> {
                Shizuku.requestPermission(REQUEST_CODE)
            }
            Shizuku.shouldShowRequestPermissionRationale() -> {
                toast(context, "Shizuku 权限未授予，请到 Shizuku App 手动授权后重试")
            }
        }
    }

    private fun grantAndEnable(context: Context?) {
        val ctx = context ?: return
        thread(name = "shizuku-grant") {
            var ok = true
            // 1) 授予 WRITE_SECURE_SETTINGS（manifest 已声明）
            ok = runShell(ctx, "pm", "grant", ctx.packageName, PERM).first && ok
            // 2) Android 13+ 解除受限设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = runShell(ctx, "appops", "set", ctx.packageName,
                    "ACCESS_RESTRICTED_SETTINGS", "allow").first && ok
            }
            // 3) 主路：shell 直写 enabled_accessibility_services（追加，unflatten 判重）
            ok = runShell(ctx, "settings", "put", "secure",
                "enabled_accessibility_services", mergeServices(ctx)).first && ok
            ok = runShell(ctx, "settings", "put", "secure",
                "accessibility_enabled", "1").first && ok
            // 4) 辅路：pm grant 生效后应用自身也可直写
            try { enableAccessibilitySelf(ctx) } catch (t: Throwable) {}
            toast(ctx, if (ok) "无障碍已自动开启" else "部分命令失败，请查看日志或手动开启")
        }
    }

    private fun mergeServices(context: Context): String {
        val svc = serviceName(context)
        val current = try {
            runShell(context, "settings", "get", "secure", "enabled_accessibility_services").second
        } catch (t: Throwable) { "" }
        val cur = current.trim().takeIf { it.isNotBlank() && it != "null" } ?: ""
        val set = cur.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .toMutableSet()
        set.add(ComponentName.unflattenFromString(svc) ?: return svc)
        return set.joinToString(":") { it.flattenToShortString() }
    }

    private fun isServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val target = ComponentName(context, ClickAccessibilityService::class.java).flattenToShortString()
        return enabled.any {
            ComponentName.unflattenFromString(it.id)?.flattenToShortString() == target
        }
    }

    private fun enableAccessibilitySelf(context: Context) {
        val svc = ComponentName(context, ClickAccessibilityService::class.java)
        val cur = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val set = cur.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.toMutableSet()
        if (set.contains(svc)) return
        set.add(svc)
        Settings.Secure.putString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            set.joinToString(":") { it.flattenToShortString() })
        Settings.Secure.putString(context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }

    private fun serviceName(context: Context): String =
        ComponentName(context, ClickAccessibilityService::class.java).flattenToShortString()

    /** 反射 Shizuku.newProcess（13.1.5 为 private）；返回 (是否成功, 输出) */
    private fun runShell(context: Context, vararg cmd: String): Pair<Boolean, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java)
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null)
            val ins = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val text = ins.bufferedReader().readText()
            val code = process.javaClass.getMethod("waitFor").invoke(process) as Int
            Pair(code == 0, text)
        } catch (t: Throwable) {
            Pair(false, t.message ?: "")
        }
    }

    private fun toast(context: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}
