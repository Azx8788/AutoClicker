package com.azx8788.autoclicker.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import com.azx8788.autoclicker.model.Actions
import com.azx8788.autoclicker.model.ClickAction
import androidx.core.content.ContextCompat
import com.azx8788.autoclicker.service.ClickAccessibilityService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object ClickEngine {

    @Volatile var running: Boolean = false
        private set

    private val lock = Any()
    @Volatile private var session = 0L
    private var hasInfinite = false
    private val remainingLimited = AtomicInteger(0)
    private val chains = CopyOnWriteArrayList<Thread>()
    private var contextRef: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    fun start(context: Context, actions: List<ClickAction>) = synchronized(lock) {
        if (running) return
        val active = actions.filter { it.enabled }
        if (active.isEmpty()) return
        contextRef = context.applicationContext
        running = true
        session++
        ClickAccessibilityService.instance?.resetForRun()
        hasInfinite = active.any { it.repeatCount <= 0 }
        remainingLimited.set(active.count { it.repeatCount > 0 })
        chains.clear()
        acquireWakeLock(context.applicationContext)
        registerScreenOff(context.applicationContext)
        active.forEach { a ->
            chains.add(thread(isDaemon = true, name = "chain-${a.label}") { chainLoop(a) })
        }
        broadcast(true)
    }

    fun stop() = synchronized(lock) {
        if (!running) return
        running = false
        session++
        chains.forEach { it.interrupt() }
        chains.clear()
        ClickAccessibilityService.instance?.clearPending()
        releaseWakeLock()
        unregisterScreenOff()
        broadcast(false)
    }

    private fun chainLoop(action: ClickAction) {
        val mySession = session
        var done = 0
        var fails = 0
        while (running && mySession == session) {
            val svc = ClickAccessibilityService.instance
            if (svc == null) { sleepQuiet(200); continue }
            val t0 = SystemClock.elapsedRealtime()
            val ok = try { svc.perform(action) } catch (t: Throwable) { false }
            if (!ok) {
                if (!running) return
                if (++fails >= 10) { stop(); return }
                sleepQuiet(50)
                continue
            }
            fails = 0
            done++
            if (action.repeatCount > 0 && done >= action.repeatCount) {
                onLimitedDone()
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            val wait = action.intervalMs.toLong() - elapsed
            if (wait > 0) sleepQuiet(wait)
        }
    }

    private fun onLimitedDone() = synchronized(lock) {
        if (!running) return
        if (remainingLimited.decrementAndGet() == 0 && !hasInfinite) {
            running = false
            session++
            chains.forEach { it.interrupt() }
            chains.clear()
            ClickAccessibilityService.instance?.clearPending()
            releaseWakeLock()
            unregisterScreenOff()
            broadcast(false)
        }
    }

    private fun registerScreenOff(ctx: Context) {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Intent.ACTION_SCREEN_OFF) stop()
            }
        }
        try {
            ContextCompat.registerReceiver(ctx, r,
                IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = r
        } catch (t: Throwable) {}
    }

    private fun unregisterScreenOff() {
        val r = screenReceiver ?: return
        screenReceiver = null
        try { contextRef?.unregisterReceiver(r) } catch (t: Throwable) {}
    }

    private fun acquireWakeLock(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoClicker:engine").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (t: Throwable) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (t: Throwable) {}
        wakeLock = null
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (t: Throwable) {}
    }

    private fun broadcast(state: Boolean) {
        val ctx = contextRef ?: return
        ctx.sendBroadcast(Intent(Actions.STATE)
            .putExtra(Actions.EXTRA_RUNNING, state)
            .setPackage(ctx.packageName))
    }
}
