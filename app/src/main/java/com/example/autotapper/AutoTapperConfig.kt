package com.example.autotapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AutoTapperConfig {
    const val PREFS_NAME = "autotapper_prefs"

    const val KEY_TAP_POINTS = "tap_points"
    const val KEY_INTERVAL_MS = "interval_ms"
    const val KEY_RANDOM_EXTRA_MS = "random_extra_ms"
    const val KEY_REPEAT_COUNT = "repeat_count"
    const val KEY_TAP_DURATION_MS = "tap_duration_ms"
    const val KEY_CONTROLLER_ALPHA = "controller_alpha"
    const val KEY_TAP_MODE = "tap_mode"
    const val KEY_CONSOLE_DOCK_RIGHT = "console_dock_right"

    // 点击配置的默认值与取值范围
    const val DEFAULT_INTERVAL_MS = 100L
    const val DEFAULT_RANDOM_EXTRA_MS = 80L
    const val DEFAULT_REPEAT_COUNT = 0
    const val DEFAULT_TAP_DURATION_MS = 50L

    const val MIN_INTERVAL_MS = 100L
    const val MIN_TAP_DURATION_MS = 10L
    const val MAX_TAP_DURATION_MS = 2_000L

    // 更新检查：用户主动忽略的版本、最近一次已提醒过的版本
    const val KEY_UPDATE_IGNORED_VERSION = "update_ignored_version"
    const val KEY_UPDATE_NOTIFIED_VERSION = "update_notified_version"

    // 旧版单点字段，仅用于一次性迁移
    const val KEY_TAP_X = "tap_x"
    const val KEY_TAP_Y = "tap_y"

    const val ACTION_POINT_SELECTED = "com.example.autotapper.action.POINT_SELECTED"
    const val ACTION_START_CLICKING = "com.example.autotapper.action.START_CLICKING"
    const val ACTION_STOP_CLICKING = "com.example.autotapper.action.STOP_CLICKING"

    /** 暂停 / 继续：两个服务都接收，和 START / STOP 走同一条广播通道。 */
    const val ACTION_PAUSE_CLICKING = "com.example.autotapper.action.PAUSE_CLICKING"
    const val ACTION_RESUME_CLICKING = "com.example.autotapper.action.RESUME_CLICKING"
    const val ACTION_SHOW_CONTROLLER = "com.example.autotapper.action.SHOW_CONTROLLER"
    const val ACTION_START_PICKING = "com.example.autotapper.action.START_PICKING"
    const val ACTION_STOP_CONTROLLER = "com.example.autotapper.action.STOP_CONTROLLER"
    const val ACTION_CLICK_STATE_CHANGED = "com.example.autotapper.action.CLICK_STATE_CHANGED"
    const val ACTION_CONTROLLER_ALPHA_CHANGED = "com.example.autotapper.action.CONTROLLER_ALPHA_CHANGED"
    const val ACTION_SHIZUKU_TAP = "com.example.autotapper.action.SHIZUKU_TAP"
    const val ACTION_START_REGION_PICK = "com.example.autotapper.action.START_REGION_PICK"
    const val ACTION_CANCEL_REGION_PICK = "com.example.autotapper.action.CANCEL_REGION_PICK"
    const val ACTION_REGION_PICKED = "com.example.autotapper.action.REGION_PICKED"

    /** 录制模式：拉起 / 停掉遮层，结果用 ACTION_RECORDED 回传（插件 RecorderBridge 在等）。 */
    const val ACTION_START_RECORDING = "com.example.autotapper.action.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.example.autotapper.action.STOP_RECORDING"
    const val ACTION_RECORDED = "com.example.autotapper.action.RECORDED"

    const val EXTRA_TAP_X = "extra_tap_x"
    const val EXTRA_TAP_Y = "extra_tap_y"
    const val EXTRA_IS_CLICKING = "extra_is_clicking"
    const val EXTRA_START_PICKING = "extra_start_picking"
    const val EXTRA_CURRENT_POINT_INDEX = "extra_current_point_index"
    const val EXTRA_REGION_X = "extra_region_x"
    const val EXTRA_REGION_Y = "extra_region_y"
    const val EXTRA_REGION_W = "extra_region_w"
    const val EXTRA_REGION_H = "extra_region_h"
    const val EXTRA_REGION_LABEL = "extra_region_label"
    const val EXTRA_PICK_REQUEST_ID = "extra_pick_request_id"
    const val EXTRA_RECORD_REQUEST_ID = "extra_record_request_id"
    const val EXTRA_RECORDED_JSON = "extra_recorded_json"
    const val EXTRA_RECORD_MAX_MS = "extra_record_max_ms"

    // 点击方式的两种实现：无障碍手势 / Shizuku 提权
    const val TAP_MODE_ACCESSIBILITY = "accessibility"
    const val TAP_MODE_SHIZUKU = "shizuku"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConsoleDockedRight(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSOLE_DOCK_RIGHT, true)

    fun saveConsoleDock(context: Context, dockedRight: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSOLE_DOCK_RIGHT, dockedRight).apply()
    }

    fun getTapMode(context: Context): String =
        prefs(context).getString(KEY_TAP_MODE, TAP_MODE_ACCESSIBILITY)
            ?.takeIf { it == TAP_MODE_SHIZUKU || it == TAP_MODE_ACCESSIBILITY }
            ?: TAP_MODE_ACCESSIBILITY

    fun saveTapMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_TAP_MODE, mode).apply()
    }

    /**
     * 点位相关方法仍然保留：主界面与悬浮控制器的取点/徽标还在用它们，
     * 而引擎已经改吃「规则」；两边通过 RuleStore.ensureMigrated 的迁移对齐。
     */
    fun loadPoints(context: Context): List<TapPoint> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_TAP_POINTS, null)
        if (raw != null) {
            return parsePoints(raw)
        }

        // 一次性迁移：把旧版单点坐标转成点位列表
        val legacyX = prefs.getInt(KEY_TAP_X, -1)
        val legacyY = prefs.getInt(KEY_TAP_Y, -1)
        if (legacyX >= 0 && legacyY >= 0) {
            val points = listOf(TapPoint(legacyX, legacyY))
            savePoints(context, points)
            return points
        }
        return emptyList()
    }

    fun savePoints(context: Context, points: List<TapPoint>) {
        prefs(context).edit().putString(KEY_TAP_POINTS, toJson(points)).apply()
    }

    fun addPoint(context: Context, x: Int, y: Int): List<TapPoint> {
        val points = loadPoints(context) + TapPoint(x, y)
        savePoints(context, points)
        return points
    }

    fun removeLastPoint(context: Context): List<TapPoint> {
        val points = loadPoints(context)
        val updated = if (points.isEmpty()) points else points.dropLast(1)
        savePoints(context, updated)
        return updated
    }

    fun removePointAt(context: Context, index: Int): List<TapPoint> {
        val points = loadPoints(context)
        val updated = if (index in points.indices) {
            points.toMutableList().apply { removeAt(index) }
        } else {
            points
        }
        savePoints(context, updated)
        return updated
    }

    fun hasPoints(context: Context): Boolean = loadPoints(context).isNotEmpty()

    fun getIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceAtLeast(MIN_INTERVAL_MS)

    fun saveIntervalMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL_MS, value.coerceAtLeast(MIN_INTERVAL_MS)).apply()
    }

    fun getRandomExtraMs(context: Context): Long =
        prefs(context).getLong(KEY_RANDOM_EXTRA_MS, DEFAULT_RANDOM_EXTRA_MS).coerceAtLeast(0L)

    fun saveRandomExtraMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_RANDOM_EXTRA_MS, value.coerceAtLeast(0L)).apply()
    }

    fun getRepeatCount(context: Context): Int =
        prefs(context).getInt(KEY_REPEAT_COUNT, DEFAULT_REPEAT_COUNT).coerceAtLeast(0)

    fun saveRepeatCount(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_REPEAT_COUNT, value.coerceAtLeast(0)).apply()
    }

    fun getTapDurationMs(context: Context): Long =
        prefs(context).getLong(KEY_TAP_DURATION_MS, DEFAULT_TAP_DURATION_MS)
            .coerceIn(MIN_TAP_DURATION_MS, MAX_TAP_DURATION_MS)

    fun saveTapDurationMs(context: Context, value: Long) {
        prefs(context).edit()
            .putLong(KEY_TAP_DURATION_MS, value.coerceIn(MIN_TAP_DURATION_MS, MAX_TAP_DURATION_MS))
            .apply()
    }

    fun saveControllerAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_CONTROLLER_ALPHA, alpha.coerceIn(0.3f, 1f)).apply()
    }

    /** 默认完全不透明：半透明时下层应用的高对比内容会透出来，面板上的读数不好认。 */
    fun getControllerAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_CONTROLLER_ALPHA, 1f).coerceIn(0.3f, 1f)
    }

    private fun toJson(points: List<TapPoint>): String =
        JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().put("x", point.x).put("y", point.y))
            }
        }.toString()

    private fun parsePoints(raw: String): List<TapPoint> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x >= 0 && y >= 0) {
                    add(TapPoint(x, y))
                }
            }
        }
    }.getOrDefault(emptyList())
}
