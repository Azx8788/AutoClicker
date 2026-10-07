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
            .getInt(KEY_ALPHA, 90).coerceIn(10, 100)

    fun setPanelAlpha(c: Context, percent: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ALPHA, percent.coerceIn(10, 100))
            .apply()
    }
}
