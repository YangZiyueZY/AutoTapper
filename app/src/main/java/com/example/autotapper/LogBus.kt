package com.example.autotapper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志总线：内存环形缓冲（面板和日志页读它）+ 按天落盘。
 * 插件通过 PluginContext.log 写进来，主程序自己的引擎也写这里，保证同一个时间轴。
 */
object LogBus {

    enum class Level { INFO, WARN, ERROR, RESULT }

    data class Entry(
        val at: Long,
        val level: Level,
        val stepIndex: Int,
        val stepLabel: String,
        val message: String
    )

    private const val CAPACITY = 2000
    private const val KEEP_DAYS = 7

    private val buffer = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(Entry) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun add(level: Level, message: String, stepIndex: Int = -1, stepLabel: String = "") {
        val entry = Entry(System.currentTimeMillis(), level, stepIndex, stepLabel, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it(entry) } }
        runCatching { appendToFile(entry) }
    }

    fun info(message: String, stepIndex: Int = -1, stepLabel: String = "") =
        add(Level.INFO, message, stepIndex, stepLabel)

    fun warn(message: String, stepIndex: Int = -1, stepLabel: String = "") =
        add(Level.WARN, message, stepIndex, stepLabel)

    fun error(message: String, stepIndex: Int = -1, stepLabel: String = "") =
        add(Level.ERROR, message, stepIndex, stepLabel)

    fun result(message: String, stepIndex: Int = -1, stepLabel: String = "") =
        add(Level.RESULT, message, stepIndex, stepLabel)

    fun snapshot(level: Level? = null, limit: Int = 300): List<Entry> {
        val all = synchronized(buffer) { buffer.toList() }
        val filtered = if (level == null) all else all.filter { it.level == level }
        return filtered.takeLast(limit).asReversed()
    }

    fun formatTime(at: Long): String = timeFormat.format(Date(at))

    fun addListener(listener: (Entry) -> Unit) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }

    fun removeListener(listener: (Entry) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    /** 日志文件目录；插件与主程序共用。 */
    fun logDir(context: Context): File = File(context.filesDir, "log").apply { mkdirs() }

    private var lastPrunedDay: String = ""

    private fun appendToFile(entry: Entry) {
        val context = logContext ?: return
        runCatching {
            val day = fileFormat.format(Date(entry.at))
            if (day != lastPrunedDay) {
                lastPrunedDay = day
                pruneOldLogs(context)
            }
            val file = File(logDir(context), "run-$day.log")
            val line = "${formatTime(entry.at)} [${entry.level}] #${entry.stepIndex} ${entry.stepLabel} ${entry.message}\n"
            file.appendText(line)
        }
    }

    private fun pruneOldLogs(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
            logDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }

    /** 主程序启动时注入 application context，之后日志才会落盘。 */
    @Volatile
    private var logContext: Context? = null

    fun install(context: Context) {
        logContext = context.applicationContext
    }
}
