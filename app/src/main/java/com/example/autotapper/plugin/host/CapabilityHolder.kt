package com.example.autotapper.plugin.host

import com.example.autotapper.plugin.CaptureApi
import com.example.autotapper.plugin.HostSoundApi
import com.example.autotapper.plugin.NodeApi
import com.example.autotapper.plugin.OcrApi
import com.example.autotapper.plugin.RecordApi

/**
 * 插件能力的进程级交接点。
 *
 * 为什么要有它：截图要等 MediaProjection 授权、识字要等设置里填了 token，
 * 二者都不是"服务启动时就有"的东西；而插件上下文是按 pluginId 缓存的对象，
 * 不适合把能力当成构造参数一次性钉死。
 *
 * 约定：
 * - 默认值是"诚实的空实现"——isReady()/isConfigured() 为 false，插件据此走失败分支，
 *   而不是拿到一张黑图或空字符串还当成功。
 * - 只有宿主自己的服务 / 设置页能改它，插件永远只读。
 */
object CapabilityHolder {

    @Volatile
    private var captureImpl: CaptureApi = UnavailableCaptureApi()

    @Volatile
    private var ocrImpl: OcrApi = UnconfiguredOcrApi()

    /** 节点树只有无障碍服务连上才可读，所以默认是"什么都找不到"的空实现。 */
    @Volatile
    private var nodesImpl: NodeApi = UnavailableNodeApi()

    /** 响铃没有"未授权"状态，未注册时用一个静默实现，插件调了也不会崩。 */
    @Volatile
    private var soundImpl: HostSoundApi = object : HostSoundApi {
        override fun ring(durationMs: Int) = Unit
        override fun vibrate(durationMs: Long) = Unit
        override fun stop() = Unit
    }

    /** 录制要一块悬浮遮层：没接上（或权限被撤销）时是"不支持"的空实现。 */
    @Volatile
    private var recorderImpl: RecordApi = UnavailableRecordApi()

    val capture: CaptureApi get() = captureImpl

    val ocr: OcrApi get() = ocrImpl

    fun setCapture(capture: CaptureApi) {
        captureImpl = capture
    }

    fun setOcr(ocr: OcrApi) {
        ocrImpl = ocr
    }

    val sound: HostSoundApi get() = soundImpl

    fun setSound(sound: HostSoundApi) {
        soundImpl = sound
    }

    /** 截图服务断开时退回空实现，避免插件拿到已经死掉的 ImageReader。 */
    fun resetCapture() {
        captureImpl = UnavailableCaptureApi()
    }

    fun resetOcr() {
        ocrImpl = UnconfiguredOcrApi()
    }

    val recorder: RecordApi get() = recorderImpl

    fun setRecorder(recorder: RecordApi) {
        recorderImpl = recorder
    }

    /** 悬浮窗权限被撤销、或服务不再可用时退回"不支持"，插件据此提示用户重新授权。 */
    fun resetRecorder() {
        recorderImpl = UnavailableRecordApi()
    }

    val nodes: NodeApi get() = nodesImpl

    fun setNodes(nodes: NodeApi) {
        nodesImpl = nodes
    }

    /** 无障碍服务断开时退回空实现，否则插件会去摸一棵已经死掉的节点树。 */
    fun resetNodes() {
        nodesImpl = UnavailableNodeApi()
    }
}