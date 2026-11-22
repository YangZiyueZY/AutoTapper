package com.example.autotapper.runtime

import com.example.autotapper.LogBus
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.ConsoleCellExtension
import com.example.autotapper.plugin.HostExtension
import com.example.autotapper.plugin.PluginStepType
import java.io.File

/**
 * 进程内已装载插件的注册表。规则引擎查步骤类型、控制台查格子都走这里。
 *
 * 线程安全：全部用 synchronized，读操作也加锁 —— 注册表只在安装/启用/停用时变动，
 * 频率极低，简单加锁比读写锁更不容易写错。
 *
 * 一个 typeId 只归属一个插件：重复注册时第一条胜出并 warn，避免规则里的
 * 步骤类型悄悄指向另一个插件。
 */
object PluginRegistry {

    /** 注册表里的一个插件，附带它来自哪个 jar（调试与卸载时有用）。 */
    data class Entry(val plugin: AutoTapperPlugin, val jarFile: File?)

    private val lock = Any()

    private val entries = LinkedHashMap<String, Entry>()
    private val stepTypeIndex = LinkedHashMap<String, Pair<AutoTapperPlugin, PluginStepType>>()
    private val displayNames = LinkedHashMap<String, String>()

    // -----------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------

    /** 注册（或替换同 id 的旧插件）。返回实际生效的步骤类型数量。 */
    fun register(plugin: AutoTapperPlugin, jarFile: File? = null): Int {
        synchronized(lock) {
            val id = runCatching { plugin.id }.getOrNull()?.trim().orEmpty()
            if (id.isEmpty()) {
                LogBus.error("插件 id 为空，拒绝注册")
                return 0
            }
            if (entries.containsKey(id)) {
                LogBus.warn("插件 " + id + " 已注册，先移除旧实例")
                unregister(id)
            }
            entries[id] = Entry(plugin, jarFile)
            displayNames[id] = runCatching { plugin.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
            reindex()
            val count = stepTypeIndex.values.count { it.first === plugin }
            LogBus.info("插件 " + id + " 注册完成，提供 " + count + " 种步骤类型")
            return count
        }
    }

    /** 移除插件及其所有步骤类型 / 扩展。 */
    fun unregister(id: String): Boolean = synchronized(lock) {
        val removed = entries.remove(id) != null
        if (removed) {
            displayNames.remove(id)
            reindex()
            LogBus.info("插件 " + id + " 已从注册表移除")
        }
        removed
    }

    /**
     * 启用/停用。停用等价于 unregister（步骤类型立刻从规则编辑器的列表里消失），
     * 启用由 PluginManager 负责重新装载后 register。
     */
    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        if (enabled) entries.containsKey(id) else unregister(id)
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        stepTypeIndex.clear()
        displayNames.clear()
    }

    // -----------------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------------

    fun plugin(id: String): AutoTapperPlugin? = synchronized(lock) { entries[id]?.plugin }

    fun isRegistered(id: String): Boolean = synchronized(lock) { entries.containsKey(id) }

    fun pluginIds(): List<String> = synchronized(lock) { entries.keys.toList() }

    /** 已注册插件与 jar 的对应关系（调试/卸载列表用）。 */
    fun entry(id: String): Entry? = synchronized(lock) { entries[id] }

    /** 按 typeId 找步骤类型；找不到返回 null。 */
    fun resolveStepType(typeId: String): Pair<AutoTapperPlugin, PluginStepType>? =
        synchronized(lock) { stepTypeIndex[typeId] }

    /** 所有插件提供的步骤类型：(pluginId, pluginName, stepType)。 */
    fun allStepTypes(): List<Triple<String, String, PluginStepType>> = synchronized(lock) {
        val result = ArrayList<Triple<String, String, PluginStepType>>(stepTypeIndex.size)
        entries.forEach { (id, entry) ->
            val name = displayNames[id] ?: id
            safeStepTypes(entry.plugin).forEach { type ->
                // 只列真正归属本插件的（冲突时那条可能被别的插件拿走）
                if (stepTypeIndex[type.typeId]?.first === entry.plugin) {
                    result.add(Triple(id, name, type))
                }
            }
        }
        result
    }

    /** 某个插件的步骤类型。 */
    fun stepTypesOf(pluginId: String): List<PluginStepType> = synchronized(lock) {
        entries[pluginId]?.let { safeStepTypes(it.plugin) } ?: emptyList()
    }

    /** 所有插件提供的控制台格子扩展：(pluginId, extension)。 */
    fun consoleCells(): List<Pair<String, ConsoleCellExtension>> = synchronized(lock) {
        val result = ArrayList<Pair<String, ConsoleCellExtension>>()
        entries.forEach { (id, entry) ->
            safeExtensions(entry.plugin).forEach { extension ->
                if (extension is ConsoleCellExtension) {
                    result.add(id to extension)
                }
            }
        }
        result
    }

    /** 所有扩展（不限于控制台格子）。 */
    fun extensions(): List<Pair<String, HostExtension>> = synchronized(lock) {
        val result = ArrayList<Pair<String, HostExtension>>()
        entries.forEach { (id, entry) -> safeExtensions(entry.plugin).forEach { result.add(id to it) } }
        result
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /** 重建 typeId 索引。调用方必须已持锁。 */
    private fun reindex() {
        stepTypeIndex.clear()
        entries.forEach { (id, entry) ->
            safeStepTypes(entry.plugin).forEach { type ->
                val typeId = type.typeId.trim()
                if (typeId.isEmpty()) {
                    LogBus.warn("插件 " + id + " 提供了空的 typeId，已忽略")
                    return@forEach
                }
                val existing = stepTypeIndex[typeId]
                if (existing != null) {
                    LogBus.warn(
                        "步骤类型 " + typeId + " 已被插件 " + (runCatching { existing.first.id }.getOrNull() ?: "?") +
                            " 占用，忽略插件 " + id + " 的重复声明"
                    )
                    return@forEach
                }
                stepTypeIndex[typeId] = entry.plugin to type
            }
        }
    }

    private fun safeStepTypes(plugin: AutoTapperPlugin): List<PluginStepType> =
        runCatching { plugin.stepTypes() }.getOrElse { t ->
            LogBus.error("插件 " + runCatching { plugin.id }.getOrElse { "?" } + " 的 stepTypes() 抛异常：" + (t.message ?: t.javaClass.simpleName))
            emptyList()
        }

    private fun safeExtensions(plugin: AutoTapperPlugin): List<HostExtension> =
        runCatching { plugin.extensions() }.getOrElse { t ->
            LogBus.error("插件 " + runCatching { plugin.id }.getOrElse { "?" } + " 的 extensions() 抛异常：" + (t.message ?: t.javaClass.simpleName))
            emptyList()
        }
}
