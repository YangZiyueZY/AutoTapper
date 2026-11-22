package com.example.autotapper

import android.app.Application
import com.example.autotapper.capture.LiveCaptureApi
import com.example.autotapper.ocr.OcrService
import com.example.autotapper.plugin.host.CapabilityHolder
import com.example.autotapper.plugin.host.PluginSoundAdapter
import com.example.autotapper.plugin.host.RecorderBridge
import com.example.autotapper.runtime.PluginManager

/**
 * 进程入口。日志落盘与插件运行时都是进程级单例，必须赶在任何界面与服务之前就位：
 * 无障碍服务可能由系统先于 MainActivity 拉起（用户开一次就常驻），
 * 晚一步初始化不仅会丢掉这段时间的落盘日志，插件步骤也解析不到类型。
 */
class AutoTapperApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Application.onCreate 抛异常会让整个进程起不来，这里只记日志、绝不外抛
        runCatching { LogBus.install(this) }
            .onFailure { LogBus.error("日志落盘初始化失败：" + it.javaClass.simpleName) }

        runCatching { PluginManager.init(this) }
            .onFailure {
                LogBus.error(
                    "插件运行时初始化失败：" + it.javaClass.simpleName + " " + (it.message ?: "")
                )
            }

        // 截图能力装的是常驻门面：插件上下文会把 CapabilityHolder.capture 按值缓存下来，
        // 所以这里先占领这个位置，授权/撤销只换门面内部的实现，插件不需要重建上下文。
        runCatching { CapabilityHolder.setCapture(LiveCaptureApi) }
            .onFailure { LogBus.warn("截图能力门面初始化失败：" + it.javaClass.simpleName) }

        // 响铃/震动：进程级能力，不需要授权，直接注册一个实现即可。
        runCatching { CapabilityHolder.setSound(PluginSoundAdapter(this)) }
            .onFailure {
                LogBus.error("响铃能力初始化失败：" + it.javaClass.simpleName)
            }

        // 录制能力：遮层与触摸采样都留在宿主，插件只拿"已经录好的手势序列"。
        // 它内部只用 app context 拉起 OverlayService，不依赖任何界面先启动。
        runCatching { CapabilityHolder.setRecorder(RecorderBridge(this)) }
            .onFailure {
                LogBus.error("录制能力初始化失败：" + it.javaClass.simpleName)
            }

        // 识字服务：令牌只留在主程序设置里，插件通过 CapabilityHolder 拿到的是 OcrApi 门面。
        runCatching { CapabilityHolder.setOcr(OcrService(this)) }
            .onFailure {
                LogBus.error(
                    "识字服务初始化失败：" + it.javaClass.simpleName + " " + (it.message ?: "")
                )
            }
    }
}