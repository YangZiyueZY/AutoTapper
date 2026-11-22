package com.example.autotapper.runtime

import android.content.Context
import com.example.autotapper.BuildConfig
import com.example.autotapper.LogBus
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.PluginApi
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 用 DexClassLoader 把插件 jar 装进进程，并做两道硬性兼容性检查：
 * apiVersion 必须等于宿主 [PluginApi.VERSION]，minHostVersionCode 不能高于
 * 宿主 [BuildConfig.VERSION_CODE]。
 *
 * 任何 Throwable（含类初始化崩溃、静态块里直接 System.exit 之外的任何异常）都在这里吃掉，
 * 只写 LogBus 并返回失败原因，绝不让宿主进程崩掉。
 */
object PluginLoader {

    /** 装载失败的具体原因，界面可以直接拿去显示。 */
    enum class LoadFailure(val label: String) {
        FILE_MISSING("插件文件不存在"),
        API_MISMATCH("插件 API 版本不匹配"),
        HOST_TOO_OLD("主程序版本过低"),
        ENTRY_NOT_FOUND("找不到入口类"),
        NOT_A_PLUGIN("入口类没有实现 AutoTapperPlugin"),
        CRASH("插件初始化时抛异常")
    }

    /** 装载结果。[Ok.classLoader] 保留下来，便于之后按需 continueUse 或调试。 */
    sealed interface LoadOutcome {
        data class Ok(val plugin: AutoTapperPlugin, val classLoader: ClassLoader) : LoadOutcome

        data class Failed(val reason: LoadFailure, val detail: String) : LoadOutcome {
            /** 一句完整的失败说明，写入日志 / 显示在插件详情里。 */
            val message: String get() = reason.label + "：" + detail
        }
    }

    /**
     * 便捷版：只要插件对象，失败返回 null（日志里已经有原因）。
     * 需要知道失败原因时用 [loadDetailed]。
     */
    fun load(context: Context, jarFile: File, entryClass: String): AutoTapperPlugin? =
        (loadDetailed(context, jarFile, entryClass) as? LoadOutcome.Ok)?.plugin

    /**
     * 完整版装载。
     *
     * @param pluginId 插件 id，只用于 dex 缓存目录与日志
     * @param version  插件版本，只用于 dex 缓存目录与日志
     */
    fun loadDetailed(
        context: Context,
        jarFile: File,
        entryClass: String,
        pluginId: String = jarFile.parentFile?.name ?: jarFile.nameWithoutExtension,
        version: String = jarFile.nameWithoutExtension
    ): LoadOutcome {
        if (!jarFile.isFile) {
            return fail(pluginId, LoadFailure.FILE_MISSING, jarFile.absolutePath)
        }
        if (entryClass.isBlank()) {
            return fail(pluginId, LoadFailure.ENTRY_NOT_FOUND, "index.json 没有声明 entryClass")
        }

        return try {
            val dexDir = File(PluginInstaller.dexCacheDir(context, pluginId), version)
            if (!dexDir.exists() && !dexDir.mkdirs()) {
                return fail(pluginId, LoadFailure.CRASH, "无法创建 dex 缓存目录 " + dexDir.absolutePath)
            }
            val loader = DexClassLoader(
                jarFile.absolutePath,
                dexDir.absolutePath,
                null,
                PluginLoader::class.java.classLoader
            )
            // 插件自己也会打包一份 plugin/Api.kt，这里靠双亲委派拿到宿主这份，
            // 保证插件拿到的 Capacity / PluginContext 就是宿主的同一批类型。
            val clazz = Class.forName(entryClass, true, loader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance !is AutoTapperPlugin) {
                return fail(pluginId, LoadFailure.NOT_A_PLUGIN, entryClass + " 的实际类型是 " + clazz.name)
            }

            val apiVersion = runCatching { instance.apiVersion }.getOrDefault(-1)
            if (apiVersion != PluginApi.VERSION) {
                return fail(
                    pluginId,
                    LoadFailure.API_MISMATCH,
                    "插件声明 API " + apiVersion + "，宿主支持 " + PluginApi.VERSION
                )
            }
            val minHost = runCatching { instance.minHostVersionCode }.getOrDefault(0)
            if (minHost > BuildConfig.VERSION_CODE) {
                return fail(
                    pluginId,
                    LoadFailure.HOST_TOO_OLD,
                    "插件要求主程序 versionCode ≥ " + minHost + "，当前 " + BuildConfig.VERSION_CODE
                )
            }

            LogBus.info("已装载插件 " + instance.id + " " + instance.version + "（" + instance.name + "）")
            LoadOutcome.Ok(instance, loader)
        } catch (t: Throwable) {
            fail(pluginId, LoadFailure.CRASH, describe(t))
        }
    }

    private fun fail(pluginId: String, reason: LoadFailure, detail: String): LoadOutcome.Failed {
        val text = reason.label + "：" + detail
        LogBus.error("插件 " + pluginId + " 装载失败 —— " + text)
        return LoadOutcome.Failed(reason, detail)
    }

    /** 异常转一句人话，附带最内层 cause 的信息。 */
    private fun describe(t: Throwable): String {
        var root: Throwable = t
        var guard = 0
        while (root.cause != null && root.cause !== root && guard < 8) {
            root = root.cause!!
            guard++
        }
        val head = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
        return if (root === t) head else head + "（根本原因 " + root.javaClass.simpleName + (root.message?.let { ": " + it } ?: "") + "）"
    }
}
