package com.example.autotapper.engine

import android.content.Context
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.LogBus
import com.example.autotapper.model.Rule
import com.example.autotapper.model.StepJson
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则的落盘与迁移。
 *
 * - 一条规则一个 JSON：filesDir/rules/<id>.json
 * - 删除进回收站：filesDir/rules/.trash/<时间戳>-<id>.json，最多留 30 个
 * - 旧版本（只有点位 + 间隔 + 轮数）在第一次启动时自动迁移成一条"基础点击"规则
 */
object RuleStore {

    private const val PREFS = "rule_store_prefs"
    private const val KEY_CURRENT = "current_rule_id"
    const val TRASH_LIMIT = 30

    data class Summary(
        val id: String,
        val name: String,
        val stepCount: Int,
        val updatedAt: Long
    )

    data class TrashItem(
        val file: File,
        val ruleId: String,
        val name: String,
        val stepCount: Int,
        val deletedAt: Long
    )

    fun rulesDir(context: Context): File = File(context.filesDir, "rules").apply { mkdirs() }

    fun trashDir(context: Context): File = File(rulesDir(context), ".trash").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<Summary> {
        val files = rulesDir(context).listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val obj = JSONObject(file.readText())
                val rule = Rule.fromJson(obj)
                Summary(rule.id, rule.name, rule.steps.size, rule.updatedAt)
            }.onFailure { LogBus.warn("规则文件解析失败：${file.name}") }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(context: Context, id: String): Rule? {
        val file = File(rulesDir(context), "$id.json")
        if (!file.exists()) return null
        return runCatching { Rule.fromJson(JSONObject(file.readText())) }
            .onFailure { LogBus.error("规则加载失败：$id") }
            .getOrNull()
    }

    fun save(context: Context, rule: Rule): Boolean {
        val dir = rulesDir(context)
        val file = File(dir, "${rule.id}.json")
        return runCatching {
            // 先写临时文件再改名，避免写一半断电留下坏规则
            val tmp = File(dir, "${rule.id}.json.tmp")
            tmp.writeText(rule.toJson().toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { LogBus.error("规则保存失败：${rule.name}") }.isSuccess
    }

    fun delete(context: Context, id: String): Boolean {
        val file = File(rulesDir(context), "$id.json")
        if (!file.exists()) return false
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val target = File(trashDir(context), "$stamp-$id.json")
        val moved = runCatching { file.copyTo(target, overwrite = true).also { file.delete() } }.isSuccess
        if (moved) {
            pruneTrash(context)
            if (currentRuleId(context) == id) setCurrent(context, null)
        }
        return moved
    }

    fun trash(context: Context): List<TrashItem> {
        val files = trashDir(context).listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val rule = Rule.fromJson(JSONObject(file.readText()))
                val stamp = file.name.substringBefore('-').toLongOrNull() ?: file.lastModified()
                TrashItem(file, rule.id, rule.name, rule.steps.size, stamp)
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    fun restore(context: Context, item: TrashItem): Boolean {
        val target = File(rulesDir(context), "${item.ruleId}.json")
        return runCatching {
            item.file.copyTo(target, overwrite = true)
            item.file.delete()
        }.isSuccess
    }

    fun purgeTrash(context: Context) {
        trashDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun pruneTrash(context: Context) {
        val files = trashDir(context).listFiles { f -> f.isFile } ?: return
        if (files.size <= TRASH_LIMIT) return
        files.sortedBy { it.lastModified() }
            .take(files.size - TRASH_LIMIT)
            .forEach { it.delete() }
    }

    fun currentRuleId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT, null)?.takeIf { it.isNotBlank() }

    fun setCurrent(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
    }

    /** 当前规则；没有就取最近更新的一条；一条都没有返回 null。 */
    fun currentRule(context: Context): Rule? {
        currentRuleId(context)?.let { load(context, it) }?.let { return it }
        val newest = list(context).firstOrNull() ?: return null
        setCurrent(context, newest.id)
        return load(context, newest.id)
    }

    /**
     * 旧版本配置迁移。只做一次：迁移完把旧 key 清掉，避免下次又生成第二条规则。
     * 返回迁移出来的规则（没有旧配置时返回 null）。
     */
    fun ensureMigrated(context: Context): Rule? {
        if (list(context).isNotEmpty()) return null

        val prefs = AutoTapperConfig.prefs(context)
        val pointsRaw = prefs.getString(AutoTapperConfig.KEY_TAP_POINTS, null)
        val legacyX = prefs.getInt(AutoTapperConfig.KEY_TAP_X, -1)
        val legacyY = prefs.getInt(AutoTapperConfig.KEY_TAP_Y, -1)

        val migrated = StepJson.migrateLegacyPoints(
            pointsJson = pointsRaw,
            intervalMs = prefs.getLong(AutoTapperConfig.KEY_INTERVAL_MS, 100L),
            randomExtraMs = prefs.getLong(AutoTapperConfig.KEY_RANDOM_EXTRA_MS, 0L),
            repeatCount = prefs.getInt(AutoTapperConfig.KEY_REPEAT_COUNT, 0)
        ) ?: run {
            // 旧版单点字段
            if (legacyX >= 0 && legacyY >= 0) {
                StepJson.migrateLegacyPoints(
                    pointsJson = "[]",
                    intervalMs = 100L,
                    randomExtraMs = 0L,
                    repeatCount = 0
                )
            } else {
                null
            }
        }

        if (migrated == null) return null
        save(context, migrated)
        setCurrent(context, migrated.id)
        prefs.edit()
            .remove(AutoTapperConfig.KEY_TAP_POINTS)
            .remove(AutoTapperConfig.KEY_TAP_X)
            .remove(AutoTapperConfig.KEY_TAP_Y)
            .apply()
        LogBus.info("已把旧版点位迁移成规则「${migrated.name}」，共 ${migrated.steps.size} 步")
        return migrated
    }
}
