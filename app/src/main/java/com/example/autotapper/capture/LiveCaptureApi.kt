package com.example.autotapper.capture

import android.graphics.Bitmap
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.CaptureApi
import com.example.autotapper.plugin.host.UnavailableCaptureApi

/**
 * 截图能力的「常驻门面」：进程里只装这一个对象到 CapabilityHolder，真正干活的实现随时换。
 *
 * 为什么需要它（踩过就会懂）：
 * [com.example.autotapper.runtime.PluginHostContext] 在**创建那一刻**就把
 * CapabilityHolder.capture 的当前值存下来了。也就是说，如果某个插件在用户授权截图之前跑过一次步骤，
 * 它的 ctx.capture 就永久停在「空实现」上 —— 用户后来点了授权，那个插件依旧用不了截图。
 * 而这块交接逻辑不在本模块的改动范围内，所以这里用一个身份不变、内部可换的委托层来绕开它：
 * 插件上下文拿到的一直是同一个 [LiveCaptureApi]，授权/撤销只影响它内部的 delegate。
 *
 * 用法：进程启动时装一次（[com.example.autotapper.AutoTapperApp]），
 * 截图服务拿到投影后 [attach]，服务收尾时 [detach]。插件永远不碰这两个方法。
 */
object LiveCaptureApi : CaptureApi {

    /** 当前真正可用的实现；没有授权时就是诚实的空实现。 */
    @Volatile
    private var delegate: CaptureApi = UnavailableCaptureApi()

    /** 截图服务就绪后挂上来。 */
    fun attach(api: CaptureApi) {
        delegate = api
    }

    /** 截图服务收尾时挂回空实现 —— 等价于契约里的「退回空实现」。 */
    fun detach() {
        delegate = UnavailableCaptureApi()
    }

    val current: CaptureApi get() = delegate

    override fun isReady(): Boolean = delegate.isReady()

    override fun grabRegion(region: Region): Bitmap? = delegate.grabRegion(region)

    override fun grabFullscreen(): Bitmap? = delegate.grabFullscreen()
}
