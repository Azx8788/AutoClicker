package com.azx8788.autoclicker.util

import android.content.Context
import com.azx8788.autoclicker.model.ClickAction
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "autoclicker"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_VER = "schema_ver"
    private const val KEY_ALPHA = "panel_alpha"
    private const val KEY_TOUCH_STOP = "touch_stop"
    private const val KEY_LAYOUT = "panel_layout"
    private const val KEY_PANEL_X = "panel_x"
    private const val KEY_PANEL_Y = "panel_y"
    private const val KEY_SCALE = "panel_scale"
    private const val KEY_MARKER_SIZE = "marker_size"
    private const val SCHEMA_VER = 1

    fun getActions(c: Context): List<ClickAction> {
        val s = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ACTIONS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { ClickAction.fromJson(arr.getString(it)) }
        } catch (t: Throwable) { emptyList() }
    }

    fun saveActions(c: Context, list: List<ClickAction>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject(it.toJson())) }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VER, SCHEMA_VER)
            .putString(KEY_ACTIONS, arr.toString())
            .apply()
    }

    /** 悬浮窗不透明度百分比（10~100） */
    fun getPanelAlpha(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_ALPHA, 70).coerceIn(10, 100)

    fun setPanelAlpha(c: Context, percent: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ALPHA, percent.coerceIn(10, 100))
            .apply()
    }

    /** 点击屏幕是否立即停止连点（默认开启） */
    fun getTouchStop(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUCH_STOP, true)

    fun setTouchStop(c: Context, enabled: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TOUCH_STOP, enabled)
            .apply()
    }

    /** 悬浮窗按钮排列：v=竖排 h=横排 min=极简 */
    fun getPanelLayout(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT, "v") ?: "v"

    fun setPanelLayout(c: Context, layout: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAYOUT, layout).apply()
    }

    fun getPanelX(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_PANEL_X, Int.MIN_VALUE)

    fun getPanelY(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_PANEL_Y, Int.MIN_VALUE)

    fun setPanelPos(c: Context, x: Int, y: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PANEL_X, x).putInt(KEY_PANEL_Y, y).apply()
    }

    /** 悬浮窗大小百分比（60~150） */
    fun getPanelScale(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, 100).coerceIn(60, 150)

    fun setPanelScale(c: Context, percent: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SCALE, percent.coerceIn(60, 150))
            .apply()
    }

    /** 点击器圆点大小 dp（28~80） */
    fun getMarkerSize(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_MARKER_SIZE, 46).coerceIn(28, 80)

    fun setMarkerSize(c: Context, dp: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MARKER_SIZE, dp.coerceIn(28, 80))
            .apply()
    }
}
