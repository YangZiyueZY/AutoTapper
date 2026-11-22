package com.example.autotapper.engine

/**
 * 当前进程里"活着"的注入器。
 *
 * 为什么需要它：注入器是跟着服务生命周期的——无障碍服务的 dispatchGesture 只有
 * [com.example.autotapper.AutoClickService] 持有，Shizuku 通道只有 ShizukuTapService 持有。
 * 而插件运行时是进程级的（规则可能从悬浮控制器启动、插件上下文随时可能被创建），
 * 二者生命周期不一致，中间就需要一个极窄的交接点。
 *
 * 只有服务在 onServiceConnected / onCreate 时 [set]、在 onDestroy / onUnbind 时 [clear]。
 * 读的一方永远要能接受 null（服务没起来时插件应该拿到一个"什么都做不成"的执行器，
 * 而不是一个假的成功回调）。
 */
object ActiveExecutor {

    @Volatile
    private var executor: AutomationExecutor? = null

    /** 当前注入器；服务没起来时为 null。 */
    val current: AutomationExecutor?
        get() = executor

    fun set(executor: AutomationExecutor) {
        this.executor = executor
    }

    /** 只有当前登记的就是这一个时才清空，避免旧服务的 onDestroy 把新服务的注入器抹掉。 */
    fun clear(executor: AutomationExecutor) {
        if (this.executor === executor) {
            this.executor = null
        }
    }

    /** 通道是否真的就绪（无障碍已连上 / Shizuku 已授权）。 */
    fun isReady(): Boolean = executor?.isReady() ?: false
}
