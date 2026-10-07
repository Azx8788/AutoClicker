package com.azx8788.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.azx8788.autoclicker.engine.ClickEngine
import com.azx8788.autoclicker.model.ActionType
import com.azx8788.autoclicker.model.ClickAction
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickAccessibilityService? = null
            private set
        @Volatile var ready: Boolean = false
            private set
        const val STATE_ACTION = "com.azx8788.autoclicker.ACC_STATE"
        const val EXTRA_CONNECTED = "connected"
        private const val QUEUE_CAPACITY = 128
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ClickAction>(QUEUE_CAPACITY)
    @Volatile private var busy = false
    @Volatile private var stopped = false
    private var volumeDownConsumed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ready = true
        stopped = false
        broadcast(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (volumeDownConsumed || ClickEngine.running) {
                    if (!volumeDownConsumed) {
                        volumeDownConsumed = true
                        ClickEngine.stop()
                        Toast.makeText(this, "已通过音量键停止连点", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                if (volumeDownConsumed) {
                    volumeDownConsumed = false
                    return true
                }
            }
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null; ready = false; busy = false; queue.clear()
        broadcast(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null; ready = false; busy = false; queue.clear()
        broadcast(false)
        super.onDestroy()
    }

    private fun broadcast(connected: Boolean) {
        handler.post {
            sendBroadcast(Intent(STATE_ACTION)
                .putExtra(EXTRA_CONNECTED, connected)
                .setPackage(packageName))
        }
    }

    fun perform(action: ClickAction): Boolean {
        if (!ready || stopped || !ClickEngine.running) return false
        when (action.type) {
            ActionType.HOME -> return performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.BACK -> return performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.NOTIFICATION -> return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> {}
        }
        // 有界队列 + 超时轮询：提供背压防积压漂移；停止/未运行时立即退出不残留
        while (ClickEngine.running && !stopped) {
            try {
                if (queue.offer(action, 50, TimeUnit.MILLISECONDS)) {
                    dispatchNext()
                    return true
                }
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun clearPending() {
        stopped = true
        queue.clear()
        handler.removeCallbacksAndMessages(null)
        busy = false
    }

    fun resetForRun() {
        stopped = false
        queue.clear()
    }

    private fun dispatchNext() {
        if (busy) return
        val a = queue.poll() ?: return
        val gesture = when (a.type) {
            ActionType.CLICK -> clickGesture(a.x, a.y, a.pressMs)
            ActionType.SWIPE -> swipeGesture(a.x, a.y, a.x2, a.y2, a.pressMs)
            else -> { dispatchNext(); return }
        }
        busy = true
        val timeoutRunnable = Runnable { if (busy) { busy = false; dispatchNext() } }
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                busy = false
                handler.removeCallbacks(timeoutRunnable)
                dispatchNext()
            }
            override fun onCancelled(g: GestureDescription?) {
                busy = false
                handler.removeCallbacks(timeoutRunnable)
                if (ClickEngine.running) {
                    // 真实触摸会取消进行中的注入手势（AOSP: 任何真实 MotionEvent 到达即取消注入）
                    // → 视为"用户触摸屏幕"，立即停止连点
                    ClickEngine.stop()
                    Toast.makeText(this@ClickAccessibilityService, "检测到触摸，已停止连点", Toast.LENGTH_SHORT).show()
                }
            }
        }, handler)
        val timeout = a.pressMs.toLong().coerceAtLeast(50L) + 2000L
        handler.postDelayed(timeoutRunnable, timeout)
    }

    private fun clickGesture(x: Int, y: Int, pressMs: Int): GestureDescription {
        val duration = if (pressMs > 0) pressMs.toLong() else 50L
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
        val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun swipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): GestureDescription {
        val duration = if (ms > 0) ms.toLong() else 200L
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val sx = x1.coerceIn(0, w - 1); val sy = y1.coerceIn(0, h - 1)
        val ex = x2.coerceIn(0, w - 1); val ey = y2.coerceIn(0, h - 1)
        val path = Path().apply { moveTo(sx.toFloat(), sy.toFloat()); lineTo(ex.toFloat(), ey.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}
