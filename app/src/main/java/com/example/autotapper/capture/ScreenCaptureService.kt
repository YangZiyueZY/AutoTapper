package com.example.autotapper.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.autotapper.LogBus
import com.example.autotapper.R
import com.example.autotapper.plugin.host.CapabilityHolder

/**
 * 截图前台服务：拿到 MediaProjection 授权结果 → 建 ImageReader + VirtualDisplay → 持续供帧。
 *
 * 两个必须遵守的系统约束（踩了就是黑屏或崩溃）：
 * 1. **Android 14 起，getMediaProjection 必须在「已进入前台的 mediaProjection 类型前台服务」里调用**，
 *    所以 [startAsForeground] 一定排在 [startCapture] 里的 getMediaProjection 之前。
 * 2. **Android 14 起，必须先 registerCallback 再 createVirtualDisplay**，否则抛 IllegalStateException。
 *
 * 帧的取走方式：每来一帧都 acquireLatestImage 然后立刻 close（哪怕这一帧并不转换），
 * 缓冲区只要有一次没排空，画面就会永远停在旧帧上 —— 这也是「5 帧之后卡死」的经典原因。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frameThread: HandlerThread? = null
    private var captureApi: ScreenCaptureApi? = null
    private var lastWidth = 0
    private var lastHeight = 0

    /** 收尾重入锁：releaseCapture 里会调 projection.stop()，它会再回调一次 onStop。 */
    @Volatile
    private var releasing = false

    /** MediaProjection 还活着吗。插件侧 isReady() 靠它挡住「服务已经没了但插件还在问」。 */
    private val projectionAlive: Boolean
        get() = projection != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (releasing) {
                return
            }
            // 用户可能在系统 UI 里点了「停止共享」，或者系统自己收回授权：
            // 这里必须自己收尾，否则 ImageReader 会一直活着
            LogBus.info("系统收回了屏幕录制授权，截图服务收尾。")
            runCatching { releaseCapture() }
                .onFailure { LogBus.warn("回收截图资源失败：" + it.javaClass.simpleName) }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 先建 api（此时 projection 还是 null，isReady() 自然是 false），
        // 这样任何时候插件读到的都是一个稳定对象，而不是 null
        captureApi = ScreenCaptureApi { projectionAlive }
        // 前台通知必须在 getMediaProjection 之前起来
        runCatching { startAsForeground() }
            .onFailure { LogBus.error("截图服务进入前台失败：" + it.javaClass.simpleName) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogBus.info("截图服务收到停止指令。")
            runCatching { releaseCapture() }
                .onFailure { LogBus.warn("回收截图资源失败：" + it.javaClass.simpleName) }
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { startCapture(intent) }
            .onFailure {
                LogBus.error("截图服务启动失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
                Toast.makeText(this, R.string.capture_start_failed, Toast.LENGTH_SHORT).show()
                runCatching { releaseCapture() }
                stopSelf()
            }
        return START_NOT_STICKY
    }

    /** 屏幕转了或尺寸变了：重建整条管线，否则截出来的是被拉伸的旧画面。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) {
            return
        }
        val size = screenSize()
        if (size.x == lastWidth && size.y == lastHeight) {
            return
        }
        LogBus.info("屏幕尺寸变化，重建截图管线：" + size.x + "×" + size.y)
        runCatching { buildPipeline() }
            .onFailure { LogBus.error("重建截图管线失败：" + it.javaClass.simpleName) }
    }

    override fun onDestroy() {
        runCatching { releaseCapture() }
            .onFailure { LogBus.warn("回收截图资源失败：" + it.javaClass.simpleName) }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 启动与管线

    private fun startCapture(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = readResultData(intent)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            LogBus.warn("没有拿到屏幕录制授权结果，截图服务不启动。")
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        // 重复授权（用户点了两次「截图」）时，旧投影和旧帧源一起作废，但前台状态要保住
        detachProjection()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            LogBus.error("系统没有 MediaProjectionManager，此设备不支持截图。")
            Toast.makeText(this, R.string.capture_unavailable, Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        val created = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("系统没有返回屏幕录制会话")
        projection = created
        // Android 14 起：createVirtualDisplay 之前必须先注册回调
        created.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        buildPipeline()

        val api = captureApi ?: ScreenCaptureApi { projectionAlive }
        captureApi = api
        // 到这里才把能力交出去：插件看到 isReady() == true 就说明帧源真的活着。
        // 装的是常驻门面 LiveCaptureApi（插件上下文会把它按值存下来），
        // 真正可用的实现挂在门面里，这样「先跑过插件、后授权截图」的场景也能立刻生效。
        LiveCaptureApi.attach(api)
        CapabilityHolder.setCapture(LiveCaptureApi)
        running = true
        LogBus.info("截图已就绪：" + lastWidth + "×" + lastHeight + "，插件现在可以取帧。")
    }

    @Suppress("DEPRECATION")
    private fun readResultData(intent: Intent?): Intent? {
        if (intent == null) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            // API 33 以下只有这个重载
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    /** 建（或重建）ImageReader + VirtualDisplay。进来时旧的一律先释放，所以旋转时直接调它就够。 */
    private fun buildPipeline() {
        val currentProjection = projection ?: return
        releasePipeline()

        val size = screenSize()
        val densityDpi = resources.displayMetrics.densityDpi

        val thread = HandlerThread("autotapper-capture")
        thread.start()
        val handler = Handler(thread.looper)
        frameThread = thread

        val created = ImageReader.newInstance(
            size.x,
            size.y,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )
        reader = created
        created.setOnImageAvailableListener({ source -> onFrameAvailable(source) }, handler)

        virtualDisplay = currentProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            size.x,
            size.y,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            created.surface,
            // Android 14 把 6 参重载标记为废弃并在 Kotlin 里解析不到，必须显式给回调与 Handler
            null,
            handler
        )
        lastWidth = size.x
        lastHeight = size.y
    }

    /**
     * 每帧都取走并 close。
     *
     * 关键点：**哪怕这一帧不打算用，也必须 acquireLatestImage + close**。
     * ImageReader 的队列只有 [MAX_IMAGES] 个槽，槽不还回去，生产者就不再入队新帧，
     * 之后截到的永远是几秒前那一张 —— 这就是「连续取 5 次之后卡死」的真正原因。
     */
    private fun onFrameAvailable(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            captureApi?.updateFrame(image)
        } catch (t: Throwable) {
            LogBus.warn("解析截图帧失败：" + t.javaClass.simpleName)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun releasePipeline() {
        val closingDisplay = virtualDisplay
        virtualDisplay = null
        runCatching { closingDisplay?.release() }

        val closingReader = reader
        reader = null
        runCatching { closingReader?.setOnImageAvailableListener(null, null) }
        runCatching { closingReader?.close() }

        val thread = frameThread
        frameThread = null
        runCatching { thread?.quitSafely() }
    }

    /**
     * 只摘掉「这一次授权」的全部东西：能力、帧源、投影。
     * 用户重新点一次「截图」时走这里 —— **不能**顺手把前台通知也撤了，
     * 否则紧接着的 getMediaProjection 会因为「不在前台」被系统拒绝（Android 14）。
     */
    private fun detachProjection() {
        // 自己也要挡重入：重新授权时里面会调 projection.stop()，那会同步回调 onStop，
        // 而 onStop 里的收尾会顺手 stopSelf()，把刚要重新授权的服务停掉
        val previous = releasing
        releasing = true
        try {
            running = false
            // 顺序有讲究：先把能力摘掉（门面内部退回空实现），插件就不会再拿到即将失效的 ImageReader
            runCatching { LiveCaptureApi.detach() }
            runCatching { CapabilityHolder.setCapture(LiveCaptureApi) }
            runCatching { captureApi?.clear() }
            releasePipeline()

            val closing = projection
            projection = null
            if (closing != null) {
                runCatching { closing.unregisterCallback(projectionCallback) }
                runCatching { closing.stop() }
            }
        } finally {
            releasing = previous
        }
    }

    /** 彻底收尾：摘投影 + 退出前台。重复调用安全。 */
    private fun releaseCapture() {
        if (releasing) {
            return
        }
        releasing = true
        try {
            detachProjection()
        } finally {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            releasing = false
        }
    }

    // ------------------------------------------------------------ 屏幕尺寸

    /** 真实的整屏像素尺寸（包含状态栏 / 导航栏），和 VirtualDisplay 要的尺寸必须是同一个。 */
    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        // API 30 以下：defaultDisplay.getRealSize 是唯一拿「整屏」虚拟尺寸的办法
        val point = Point()
        manager.defaultDisplay.getRealSize(point)
        return point
    }

    // ------------------------------------------------------------ 前台服务与通知

    private fun startAsForeground() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        /** 带着授权结果启动（由 CapturePermissionActivity 发出）。 */
        const val ACTION_START = "com.example.autotapper.action.CAPTURE_START"

        /** 停止截图服务。 */
        const val ACTION_STOP = "com.example.autotapper.action.CAPTURE_STOP"

        const val EXTRA_RESULT_CODE = "extra_capture_result_code"
        const val EXTRA_RESULT_DATA = "extra_capture_result_data"

        private const val CHANNEL_ID = "autotapper_screen_capture"
        private const val NOTIFICATION_ID = 1003
        private const val VIRTUAL_DISPLAY_NAME = "autotapper-capture"

        /** 双缓冲就够：多一个槽只是多一份 8 MB 的延迟，排空速度才是关键。 */
        private const val MAX_IMAGES = 2

        @Volatile
        private var running = false

        /** 截图管线是否活着（设置页 / 插件面板用，不依赖插件是否有权限）。 */
        fun isRunning(): Boolean = running
    }
}
