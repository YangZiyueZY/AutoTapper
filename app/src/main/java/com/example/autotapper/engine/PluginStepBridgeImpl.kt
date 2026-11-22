package com.example.autotapper.engine

import android.content.Context
import com.example.autotapper.LogBus
import com.example.autotapper.model.PluginStep
import com.example.autotapper.plugin.ExecutorApi
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import com.example.autotapper.plugin.host.CapabilityHolder
import com.example.autotapper.plugin.host.PluginExecutorAdapter
import com.example.autotapper.runtime.PluginHostContext
import com.example.autotapper.runtime.PluginRegistry
import kotlinx.coroutines.CancellationException

/**
 * [PluginStepBridge] 的实现：把引擎的"跑这一步"请求翻译成"找到插件 → 造上下文 → 调 execute"。
 *
 * 三件事在这里做，别处不做：
 * 1. **插件缺失降级**：typeId 找不到就返回 null，引擎记一次 Fail 并继续，绝不崩；
 * 2. **崩溃隔离**：插件抛的 Throwable 全吃掉，连续 3 次就通知宿主自动停用该插件；
 * 3. **上下文复用**：一个插件一个 [PluginHostContext]，不每次执行都重建（prefs 与日志前缀都挂在上面）。
 *
 * 线程：execute 在引擎的协程里跑（主线程调度器），插件内部自己做重活。
 * 因此这里不能有任何阻塞调用。
 */
class PluginStepBridgeImpl(
    context: Context,
    private val executorProvider: () -> AutomationExecutor?,
    private val onCrashSuspect: (pluginId: String, reason: String) -> Unit = { _, _ -> }
) : PluginStepBridge {

    private val appContext: Context = context.applicationContext

    private val lock = Any()

    /** pluginId -> 上下文。插件不多，直接全量缓存；卸载时由 clear 清掉。 */
    private val contexts = HashMap<String, PluginHostContext>()

    /** pluginId -> 连续异常次数。任何一次正常返回（成功或业务失败）都清零。 */
    private val crashCounts = HashMap<String, Int>()

    override fun isAvailable(step: PluginStep): Boolean = resolve(step) != null

    override suspend fun execute(step: PluginStep): StepResult? {
        val resolved = resolve(step) ?: return null
        val plugin = resolved.first
        val type = resolved.second
        val pluginId = safeId(plugin, step.pluginId)
        val pluginName = safeName(plugin, pluginId)
        val ctx = contextFor(pluginId, pluginName)

        return try {
            val result = type.execute(ctx, step.config)
            resetCrash(pluginId)
            result
        } catch (cancelled: CancellationException) {
            // 引擎停止 / 步骤超时走的是协程取消，必须原样抛出，否则超时会变成"插件异常"
            throw cancelled
        } catch (t: Throwable) {
            val count = bumpCrash(pluginId)
            val detail = describe(t)
            LogBus.error("插件 " + pluginName + " 执行「" + step.typeId + "」抛异常：" + detail)
            if (count >= CRASH_LIMIT) {
                LogBus.error("插件 " + pluginName + " 连续 " + count + " 次异常，已自动停用")
                crashCounts.remove(pluginId)
                runCatching { onCrashSuspect(pluginId, "连续 " + count + " 次执行异常：" + detail) }
            }
            StepResult.Fail("插件执行异常：" + detail)
        }
    }

    override fun summarize(step: PluginStep): String? {
        val resolved = resolve(step) ?: return null
        val plugin = resolved.first
        val type = resolved.second
        val pluginId = safeId(plugin, step.pluginId)
        val text = runCatching { type.summarize(step.config) }.getOrElse { t ->
            LogBus.warn("插件 " + pluginId + " 的 summarize() 抛异常：" + describe(t))
            ""
        }
        if (text.isNotBlank()) return text
        val displayName = runCatching { type.displayName }.getOrNull().orEmpty()
        return displayName.ifBlank { null }
    }

    override fun pluginName(pluginId: String): String {
        val plugin = PluginRegistry.plugin(pluginId) ?: return pluginId
        return safeName(plugin, pluginId)
    }

    /**
     * 卸载/停用一个插件后调用，丢掉它的上下文与崩溃计数，
     * 否则重新启用时还会复用旧 prefs 实例（内存里已经和磁盘不一致）。
     */
    fun forget(pluginId: String) {
        synchronized(lock) {
            contexts.remove(pluginId)
            crashCounts.remove(pluginId)
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun resolve(step: PluginStep): Pair<com.example.autotapper.plugin.AutoTapperPlugin, PluginStepType>? =
        PluginRegistry.resolveStepType(step.typeId)

    private fun contextFor(pluginId: String, pluginName: String): PluginHostContext = synchronized(lock) {
        contexts[pluginId] ?: PluginHostContext(
            appContext = appContext,
            pluginId = pluginId,
            pluginName = pluginName,
            executorProvider = ::currentExecutorApi,
            capture = CapabilityHolder.capture,
            ocr = CapabilityHolder.ocr
        ).also { contexts[pluginId] = it }
    }

    /** 注入器是随服务走的外部状态，所以每次取，不缓存 —— 但每次包一层便宜的适配器。 */
    private fun currentExecutorApi(): ExecutorApi? = executorProvider()?.let { PluginExecutorAdapter(it) }

    private fun resetCrash(pluginId: String) {
        synchronized(lock) { crashCounts.remove(pluginId) }
    }

    private fun bumpCrash(pluginId: String): Int = synchronized(lock) {
        val next = (crashCounts[pluginId] ?: 0) + 1
        crashCounts[pluginId] = next
        next
    }

    private fun safeId(plugin: com.example.autotapper.plugin.AutoTapperPlugin, fallback: String): String =
        runCatching { plugin.id }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun safeName(plugin: com.example.autotapper.plugin.AutoTapperPlugin, fallback: String): String =
        runCatching { plugin.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    /** 异常转一句人话，附带最内层原因（插件里的反射/类加载错误常常套好几层）。 */
    private fun describe(t: Throwable): String {
        var root: Throwable = t
        var guard = 0
        while (root.cause != null && root.cause !== root && guard < 8) {
            root = root.cause!!
            guard++
        }
        val head = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
        if (root === t) return head
        val tail = root.javaClass.simpleName + (root.message?.let { ": " + it } ?: "")
        return head + "（根本原因 " + tail + "）"
    }

    companion object {
        /** 连续异常到这个次数就自动停用该插件，避免一条规则被同一个插件反复拖垮。 */
        private const val CRASH_LIMIT = 3
    }
}
