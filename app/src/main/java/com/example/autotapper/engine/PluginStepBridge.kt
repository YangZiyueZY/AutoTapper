package com.example.autotapper.engine

import com.example.autotapper.model.PluginStep
import com.example.autotapper.plugin.StepResult

/**
 * 引擎与插件运行时之间的窄接口。引擎只认这个，不认识 DexClassLoader 那一层，
 * 于是插件运行时怎么实现（下载/校验/装卸）都不会反过来影响引擎。
 */
interface PluginStepBridge {

    /** 该 typeId 是否可用（插件已安装并启用）。 */
    fun isAvailable(step: PluginStep): Boolean

    /** 执行一步；插件缺失时返回 null，引擎据此记失败但不会崩。 */
    suspend fun execute(step: PluginStep): StepResult?

    /** 步骤列表里的一行摘要；插件缺失时返回 null。 */
    fun summarize(step: PluginStep): String?

    /** 插件显示名，用于"缺插件：xxx"提示。 */
    fun pluginName(pluginId: String): String
}
