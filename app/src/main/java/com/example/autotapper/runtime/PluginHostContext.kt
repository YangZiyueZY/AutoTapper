package com.example.autotapper.runtime

import android.content.Context
import android.content.SharedPreferences
import com.example.autotapper.LogBus
import com.example.autotapper.plugin.CaptureApi
import com.example.autotapper.plugin.ClockApi
import com.example.autotapper.plugin.ExecutorApi
import com.example.autotapper.plugin.GlobalAction
import com.example.autotapper.plugin.KeyCode
import com.example.autotapper.plugin.LogApi
import com.example.autotapper.plugin.NodeApi
import com.example.autotapper.plugin.OcrApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.HostSoundApi
import com.example.autotapper.plugin.RecordApi
import com.example.autotapper.plugin.RegionApi
import com.example.autotapper.plugin.host.CapabilityHolder
import com.example.autotapper.plugin.host.PluginClockAdapter
import com.example.autotapper.plugin.host.PluginLogAdapter
import com.example.autotapper.plugin.host.PluginRegionAdapter
import com.example.autotapper.plugin.host.UnconfiguredOcrApi
import com.example.autotapper.plugin.host.UnavailableCaptureApi
import com.example.autotapper.model.TapCoord

/**
 * 一个插件专属的 PluginContext。pluginId 不同 → prefs 不同、日志前缀不同。
 *
 * 一个插件一个实例，由 PluginStepBridgeImpl 按 pluginId 缓存起来复用，
 * 别每次执行步骤都 new —— executor 只在第一次访问时解析一次，
 * 这样即使执行途中注入通道切换，该插件的步骤行为也保持稳定。
 */
class PluginHostContext(
    appContext: Context,
    override val pluginId: String,
    private val pluginName: String,
    private val executorProvider: () -> ExecutorApi?,
    override val capture: CaptureApi = UnavailableCaptureApi(),
    override val ocr: OcrApi = UnconfiguredOcrApi()
) : PluginContext {

    override val executor: ExecutorApi by lazy {
        // provider 是宿主跨边界给的回调，失败时降级到 Noop 而不是把异常抛给插件/界面
        val resolved = runCatching { executorProvider() }.getOrElse { throwable ->
            LogBus.warn("插件 " + pluginId + " 获取注入器失败，使用空实现：" + (throwable.message ?: "未知原因"))
            null
        }
        resolved ?: NoopExecutorApi()
    }

    override val regions: RegionApi = PluginRegionAdapter(appContext)
    override val log: LogApi = PluginLogAdapter(pluginName)
    override val clock: ClockApi = PluginClockAdapter(appContext)

    // 响铃是进程级能力（不需要授权），所以直接取交接点里的实现
    override val sound: HostSoundApi get() = CapabilityHolder.sound

    // 节点树随无障碍服务连上/断开而变，所以每次取交接点里的当前实现，
    // 不在这里缓存 —— 缓存了服务一断插件就会摸到一棵死树
    override val nodes: NodeApi get() = CapabilityHolder.nodes

    // 录制能力同理：悬浮窗权限可能中途被撤销，每次取交接点里的当前实现，
    // 这样插件在录制前后的 isSupported() 拿到的是同一时刻的真相
    override val recorder: RecordApi get() = CapabilityHolder.recorder

    // prefs 名按 pluginId 隔离：非法字符换成下划线，避免落到系统保留的文件名上
    override val prefs: SharedPreferences = appContext.getSharedPreferences(
        "plugin_" + sanitizeId(pluginId),
        Context.MODE_PRIVATE
    )

    private companion object {
        fun sanitizeId(id: String): String {
            if (id.isEmpty()) return "unknown"
            val cleaned = buildString(id.length) {
                for (ch in id) {
                    append(
                        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                            ch == '.' || ch == '_' || ch == '-'
                        ) ch else '_'
                    )
                }
            }
            return cleaned.ifEmpty { "unknown" }
        }
    }

    /**
     * 注入器没连上时的空实现：必须立刻回调 false，
     * 否则引擎会等满 5 秒回调超时，把规则执行白白拖慢。
     */
    private class NoopExecutorApi : ExecutorApi {
        override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) = onResult(false)

        override fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) =
            onResult(false)

        override fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit) =
            onResult(false)

        override fun globalAction(action: GlobalAction, onResult: (Boolean) -> Unit) = onResult(false)

        override fun keyEvent(key: KeyCode, onResult: (Boolean) -> Unit) = onResult(false)

        override fun inputText(text: String, onResult: (Boolean) -> Unit) = onResult(false)

        override fun maxTapsPerSecond(): Int = 0
    }
}