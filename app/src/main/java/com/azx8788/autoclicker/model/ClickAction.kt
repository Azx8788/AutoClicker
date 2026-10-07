package com.azx8788.autoclicker.model

import org.json.JSONObject

enum class ActionType(val code: Int) {
    CLICK(0), SWIPE(1), HOME(3), BACK(4), NOTIFICATION(5), GESTURE(10), OPEN_APP(20);

    companion object {
        fun from(code: Int): ActionType =
            entries.firstOrNull { it.code == code } ?: CLICK
    }
}

data class ClickAction(
    var id: Long = System.currentTimeMillis(),
    var enabled: Boolean = true,
    var type: ActionType = ActionType.CLICK,
    var x: Int = 200,
    var y: Int = 400,
    var x2: Int = 200,
    var y2: Int = 600,
    var intervalMs: Int = 1000,
    var pressMs: Int = 50,
    var repeatCount: Int = 0,
    var label: String = "点击"
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("enabled", enabled)
        .put("type", type.code)
        .put("x", x).put("y", y)
        .put("x2", x2).put("y2", y2)
        .put("interval", intervalMs)
        .put("press", pressMs)
        .put("repeat", repeatCount)
        .put("label", label)
        .toString()

    companion object {
        fun fromJson(s: String): ClickAction? = try {
            val o = JSONObject(s)
            ClickAction(
                id = o.optLong("id", System.currentTimeMillis()),
                enabled = o.optBoolean("enabled", true),
                type = ActionType.from(o.optInt("type", 0)),
                x = o.optInt("x", 200),
                y = o.optInt("y", 400),
                x2 = o.optInt("x2", 200),
                y2 = o.optInt("y2", 600),
                intervalMs = o.optInt("interval", 1000),
                pressMs = o.optInt("press", 50),
                repeatCount = o.optInt("repeat", 0),
                label = o.optString("label", "点击")
            )
        } catch (t: Throwable) { null }
    }
}

object Actions {
    const val STATE = "com.azx8788.autoclicker.STATE"
    const val EXTRA_RUNNING = "running"
    const val RELOAD = "com.azx8788.autoclicker.RELOAD"
}
