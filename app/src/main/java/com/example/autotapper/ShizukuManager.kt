package com.example.autotapper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 授权模式：状态检测、授权申请、UserService 绑定与点击执行。
 *
 * Shizuku API 13.1.1 起 Shizuku.newProcess 变成 private，官方要求改用 UserService：
 * Shizuku 以 shell 身份拉起 [TapUserService]，由它注入点击事件，应用本身不需要无障碍权限。
 * 所有 Shizuku.* 调用都可能抛异常（binder 未就绪等），一律包起来。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_PERMISSION = 4001

    /** UserService 的身份标识：tag 固定，不依赖会被混淆的类名。 */
    private const val SERVICE_TAG = "autotapper-tap-v1"

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        READY
    }

    @Volatile
    private var tapService: ITapService? = null

    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var connectLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tapService = if (binder != null && binder.pingBinder()) {
                ITapService.Stub.asInterface(binder)
            } else {
                null
            }
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "user service disconnected: $name")
            tapService = null
            connectLatch?.countDown()
        }
    }

    // ------------------------------------------------------------ 状态

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shouldShowRationale(): Boolean = runCatching {
        pingBinder() && Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun serverVersion(): Int = runCatching {
        if (pingBinder()) Shizuku.getVersion() else -1
    }.getOrDefault(-1)

    /** UserService 需要 Shizuku 11 以上。 */
    fun isUserServiceSupported(): Boolean = runCatching {
        !Shizuku.isPreV11() && serverVersion() >= 11
    }.getOrDefault(false)

    fun state(context: Context): State = when {
        !isInstalled(context) -> State.NOT_INSTALLED
        !pingBinder() -> State.NOT_RUNNING
        !hasPermission() -> State.NO_PERMISSION
        else -> State.READY
    }

    // ------------------------------------------------------------ 授权

    fun requestPermission(requestCode: Int = REQUEST_CODE_PERMISSION) {
        if (!pingBinder()) {
            return
        }
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun addBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
    }

    fun removeBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.removeBinderReceivedListener(listener) }
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runCatching { Shizuku.addBinderDeadListener(listener) }
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runCatching { Shizuku.removeBinderDeadListener(listener) }
    }

    // ------------------------------------------------------------ UserService

    private fun buildArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, TapUserService::class.java.name))
            // 必须设置，否则 forAdd() 里 Objects.requireNonNull 直接抛
            .processNameSuffix("tap")
            .tag(SERVICE_TAG)
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    fun ensureBound(context: Context): Boolean {
        if (tapService != null) {
            return true
        }
        if (!hasPermission() || !isUserServiceSupported()) {
            return false
        }
        if (connectLatch == null) {
            connectLatch = CountDownLatch(1)
        }
        return runCatching {
            val args = buildArgs(context)
            boundArgs = args
            Shizuku.bindUserService(args, connection)
            true
        }.onFailure { Log.w(TAG, "bindUserService failed", it) }.getOrDefault(false)
    }

    /**
     * 等待服务连上。只能在后台线程调用：Shizuku 把连接回调 post 到主线程，
     * 主线程上等待会死锁。
     */
    fun awaitReady(context: Context, timeoutMs: Long = 4_000L): Boolean {
        if (tapService != null) {
            return true
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "awaitReady must not be called on the main thread")
            return false
        }
        if (!ensureBound(context)) {
            return false
        }
        runCatching { connectLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return tapService != null
    }

    fun isServiceConnected(): Boolean = tapService != null

    /** 结束会话：同时结束 shell 身份的 UserService 进程。 */
    fun unbind() {
        val args = boundArgs ?: return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        tapService = null
        boundArgs = null
        connectLatch = null
    }

    // ------------------------------------------------------------ 点击

    /** 同步 binder 调用，必须在后台线程执行；按住时长由 UserService 精确表达。 */
    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val service = tapService ?: return false
        return runCatching { service.tap(x, y, durationMs.toInt()) }
            .onFailure { Log.w(TAG, "tap failed", it) }
            .getOrDefault(false)
    }
}
