package com.example.autotapper.model

import org.json.JSONObject

/**
 * 规则里的一步。只有两种：
 * - [CoreStep]：主程序自带的基础点击（今天就有的能力）；
 * - [PluginStep]：由插件提供的步骤类型，主程序不认识它的 config 内容。
 *
 * 这样主程序的 JSON 结构永远不会因为多了一个插件而改变。
 */
sealed interface Step {
    val id: String
    val enabled: Boolean
    val note: String?

    /** 步骤列表里显示的一行摘要。插件步骤由插件自己提供，缺失时显示类型号。 */
    fun summary(): String
}

/** 主程序内置的点击：在固定坐标按住 [durationMs]。 */
data class CoreStep(
    override val id: String,
    override val enabled: Boolean = true,
    override val note: String? = null,
    val x: Int,
    val y: Int,
    val durationMs: Long = DEFAULT_DURATION_MS
) : Step {

    override fun summary(): String = "点击 $x,$y 按住 $durationMs ms"

    companion object {
        const val TYPE = "core.tap"
        const val DEFAULT_DURATION_MS = 50L
    }
}

/** 插件提供的步骤。主程序只保存 pluginId / typeId / config 三样东西。 */
data class PluginStep(
    override val id: String,
    override val enabled: Boolean = true,
    override val note: String? = null,
    val pluginId: String,
    val typeId: String,
    val config: JSONObject = JSONObject()
) : Step {

    override fun summary(): String {
        val custom = config.optString("__summary", "")
        return custom.ifBlank { "$pluginId / $typeId" }
    }

    companion object {
        const val TYPE = "plugin"
    }
}
