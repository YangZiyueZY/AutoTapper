package com.example.autotapper.engine

import android.content.Context
import com.example.autotapper.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 时间服务：系统时间 + 一个可校准的偏移量。
 *
 * "北京时间" 不依赖设备的时区设置 —— 直接用 UTC 毫秒 + 8 小时，
 * 这样用户把手机时区改成别的地方，定时与时间对比仍然按北京时间走。
 */
object TimeService {

    private const val PREFS = "time_service_prefs"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_CALIBRATED_AT = "calibrated_at"

    private val CALIBRATION_URLS = listOf(
        "https://www.baidu.com",
        "https://api.github.com"
    )

    private val BEIJING = TimeZone.getTimeZone("Asia/Shanghai")

    fun offsetMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_OFFSET, 0L)

    fun calibratedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CALIBRATED_AT, 0L)

    /** 校准后的当前时间（UTC 毫秒）。 */
    fun nowMs(context: Context): Long = System.currentTimeMillis() + offsetMs(context)

    /** 北京时间封装，[nowMs] 已经含偏移，这里只做时区换算。 */
    fun beijingFormat(context: Context, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = BEIJING }
        return format.format(Date(nowMs(context)))
    }

    fun beijingHour(context: Context): Int = hourOf(nowMs(context), BEIJING)

    fun beijingWeekday(context: Context): Int {
        // 1 = 周一 … 7 = 周日
        val format = SimpleDateFormat("u", Locale.US).apply { timeZone = BEIJING }
        return format.format(Date(nowMs(context))).toIntOrNull() ?: 1
    }

    private fun hourOf(ms: Long, zone: TimeZone): Int {
        val format = SimpleDateFormat("H", Locale.US).apply { timeZone = zone }
        return format.format(Date(ms)).toIntOrNull() ?: 0
    }

    /**
     * 用 HTTP 响应的 Date 头校准。取最小的 RTT 作为估计误差，
     * 一次只信任一个来源，失败就换下一个。
     */
    suspend fun calibrate(context: Context): Result<Long> = withContext(Dispatchers.IO) {
        for (url in CALIBRATION_URLS) {
            val result = runCatching { fetchServerOffset(url) }
            val offset = result.getOrNull()
            if (offset != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_OFFSET, offset)
                    .putLong(KEY_CALIBRATED_AT, System.currentTimeMillis())
                    .apply()
                LogBus.info("时间已校准，偏移 ${offset} ms（来源 $url）")
                return@withContext Result.success(offset)
            }
        }
        LogBus.warn("时间校准失败：所有来源都不可用")
        Result.failure(IllegalStateException("校准失败"))
    }

    private fun fetchServerOffset(url: String): Long? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = true
            val sentAt = System.currentTimeMillis()
            connection.connect()
            val dateHeader = connection.getHeaderField("Date") ?: return null
            val receivedAt = System.currentTimeMillis()
            val serverMs = parseHttpDate(dateHeader) ?: return null
            // Date 头只精确到秒，取往返中点作为参照
            val localMid = (sentAt + receivedAt) / 2
            serverMs - localMid
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
    }.getOrNull()
}
