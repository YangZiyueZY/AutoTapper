package com.example.autotapper.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.LogBus
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.PluginApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 插件运行时：仓库索引同步、安装 / 卸载 / 启停、冷启动装载的唯一入口。
 *
 * 线程约定（界面只要记住这三条）：
 * 1. 状态读取（[installed] / [index] / [available] / [updates] / [manifestOf] / [loadError] …）
 *    只读内存快照，主线程随时调用都不会读盘、不会发网络请求；
 * 2. 动作（[refreshIndex] / [install] / [installFromFile] / [uninstall] / [setEnabled]）立刻返回，
 *    真正的网络与文件操作跑在内部的 IO 协程里；
 * 3. 传进来的回调（onDone / onProgress / [Listener]）一律 post 到主线程执行，界面不用自己切线程。
 *
 * 用 private constructor + companion 而不是 object，是因为它要持有 application context
 * 和一个和进程同寿的 CoroutineScope，object 的初始化时机不好控制。
 */
class PluginManager private constructor(appContext: Context) {

    /** 仓库同步结果。 */
    sealed interface SyncOutcome {

        /** 同步成功。[fromCache] 为 true 表示用的是本地缓存（304 或网络失败退回）。 */
        data class Ok(val count: Int, val fromCache: Boolean) : SyncOutcome

        /** 同步失败，[reason] 是给用户看的一句中文原因。 */
        data class Failed(val reason: String) : SyncOutcome
    }

    /** 已装 / 启用 / 同步状态任一变化都回调它。回调在主线程。 */
    interface Listener {
        fun onPluginsChanged()
    }

    // -----------------------------------------------------------------------
    // 内部状态
    // -----------------------------------------------------------------------

    private val app: Context = appContext.applicationContext
    private val prefs: SharedPreferences = AutoTapperConfig.prefs(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 自己的 IO 协程域：宿主界面/服务都不需要关心它的生命周期，随进程结束。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已装记录的内存快照。读盘只发生在 init 与安装/卸载这类后台流程里。 */
    @Volatile
    private var records: List<InstalledRecord> = emptyList()

    /** 最近一次成功解析的索引；非 null 时 [index] 不再碰磁盘。 */
    @Volatile
    private var memIndex: RepoIndex? = null

    /** 清单快照缓存：一份清单只有几百字节，一次读进内存，界面反复查也不读盘。 */
    private val snapshots = ConcurrentHashMap<String, PluginManifest>()

    /** 装载失败原因（只有内存态）。重启后会重新装载，脏原因不该跨进程留着。 */
    private val loadErrors = ConcurrentHashMap<String, String>()

    private val listeners = mutableListOf<Listener>()

    /** [repo] 的内存缓存（只含 owner/repo@branch，不含镜像），避免每次读属性都去翻 prefs。 */
    @Volatile
    private var repoCache: RepoSource? = null

    /** [mirror] 的内存缓存；null 表示还没从 prefs 读过。 */
    @Volatile
    private var mirrorCache: Mirror? = null

    @Volatile
    private var isReadyFlag: Boolean = false

    /** 用 AtomicBoolean 而不是 volatile Boolean：refreshIndex 需要 compareAndSet 防重入。 */
    private val syncing = AtomicBoolean(false)

    @Volatile
    private var lastSyncAtValue: Long = 0L

    @Volatile
    private var lastSyncErrorValue: String? = null

    private val initialLoadStarted = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // 状态（主线程随便读）
    // -----------------------------------------------------------------------

    /** 首次装载流程是否走完。成功、失败、一条插件都没有，都算走完。 */
    val isReady: Boolean get() = isReadyFlag

    /** 当前是否正在同步仓库索引。 */
    val isSyncing: Boolean get() = syncing.get()

    /** 最近一次成功同步的时间（毫秒）；0 表示从未同步过。 */
    val lastSyncAt: Long get() = lastSyncAtValue

    /** 最近一次同步的失败原因；null 表示上次同步成功（有时退回缓存但仍算失败）。 */
    val lastSyncError: String? get() = lastSyncErrorValue

    /**
     * 当前插件仓库。读出来的值已经叠加了当前镜像（[mirror]），
     * 所以 [RepoSource.indexUrl] / [RepoSource.resolve] 一定指向真正要请求的地址，
     * [PluginInstaller] 不用知道镜像的存在就自动走镜像。
     *
     * setter 只负责"仓库身份"（owner/repo@branch）：写 prefs、清同步错误、丢掉内存索引、
     * 后台读新缓存，再自动同步一次。镜像由 [mirror] 单独持久化，这里不接受 value 里的镜像。
     */
    var repo: RepoSource
        get() {
            val base = repoBase()
            return base.withMirror(mirror)
        }
        set(value) {
            val current = repoBase()
            if (current.label == value.label) {
                // 同一个仓库重复设置：不重复写盘，也不丢掉当前索引
                return
            }
            val keepKey = KEY_REPO_PREFIX + value.cacheKey
            runCatching {
                val editor = prefs.edit().putString(keepKey, value.label)
                // 只留一条"当前仓库"记录：冷启动靠它判断用户在哪个仓库，留多条就分不清了
                prefs.all.keys
                    .filter {
                        it.startsWith(KEY_REPO_PREFIX) && !it.startsWith(KEY_REPO_ETAG_PREFIX) && it != keepKey
                    }
                    .forEach { editor.remove(it) }
                editor.apply()
            }.onFailure { LogBus.warn("保存插件仓库地址失败：" + describe(it)) }
            // 只存基础仓库；镜像由 mirror 单独持久化，组合放在 getter 里做
            repoCache = value.copy(mirror = Mirror.Direct)
            lastSyncErrorValue = null
            memIndex = null
            LogBus.info("插件仓库已切换为 " + value.label)
            // 新仓库的缓存放到后台读：主线程绝不读盘
            scope.launch {
                val target = repo
                val cached = runCatching { readIndexCache(target) }.getOrNull()
                memIndex = cached
                lastSyncAtValue = if (cached != null) cacheModifiedAt(target) else 0L
                notifyChanged()
            }
            // 换仓库等于换一份内容：立刻同步一次，不用等用户再点"刷新"
            refreshIndex()
        }

    /**
     * 当前镜像策略：官方直连 / jsDelivr / 自定义模板。
     *
     * 默认走 [Mirror.JsDelivr]：国内直连 raw.githubusercontent.com 经常连不上，
     * 而"装不上插件"是用户第一眼就会看到的问题，所以默认值站在能连通那边，
     * 连不通时 [syncIndexBlocking] 还会自动退回官方直连。
     *
     * setter 写 prefs（key = plugin_mirror）、清掉上次同步错误、丢掉内存索引（缓存按镜像分键），
     * 然后自动重新同步一次 —— 用户换镜像的唯一目的就是"让它通"。
     * 自定义镜像的主机在设置页保存时登记进 [PluginHttp]；这里再登记一次，
     * 冷启动/进程重启后从 prefs 恢复设置时同样生效。
     */
    var mirror: Mirror
        get() {
            val cached = mirrorCache
            if (cached != null) return cached
            val loaded = readMirrorFromPrefs()
            mirrorCache = loaded
            // 恢复上次保存过的自定义镜像：主机名必须重新登记，否则同步会被安全策略挡下来
            if (loaded is Mirror.Custom) registerMirrorHost(loaded)
            return loaded
        }
        set(value) {
            val reason = value.invalidReason()
            if (reason != null) {
                // 坏配置宁可直接拒绝，也不要写进 prefs 让下次冷启动继续坏着
                LogBus.error("忽略这次插件镜像设置：" + reason)
                return
            }
            if (mirror.encode() == value.encode()) {
                // 同一个镜像重复设置：不重复写盘、不重复同步
                return
            }
            runCatching { prefs.edit().putString(KEY_MIRROR, value.encode()).apply() }
                .onFailure { LogBus.warn("保存插件镜像设置失败：" + describe(it)) }
            mirrorCache = value
            // 信任范围跟着镜像走：自定义才登记主机；切回官方/内置镜像时把登记过的自定义主机撤掉
            if (value is Mirror.Custom) {
                registerMirrorHost(value)
            } else {
                PluginHttp.resetAllowedHosts()
            }
            lastSyncErrorValue = null
            memIndex = null
            LogBus.info("插件镜像已切换为 " + value.label + "（" + value.encode() + "）")
            // 缓存按镜像分键，换镜像等于换一份缓存：先在后台把新镜像的缓存读进内存
            scope.launch {
                val target = repo
                val cached = runCatching { readIndexCache(target) }.getOrNull()
                memIndex = cached
                lastSyncAtValue = if (cached != null) cacheModifiedAt(target) else 0L
                notifyChanged()
            }
            // 换镜像后立刻重同步一次
            refreshIndex()
        }

    /** 只含 owner/repo@branch 的基础仓库；镜像在 [repo] getter 里叠加。 */
    private fun repoBase(): RepoSource {
        val cached = repoCache
        if (cached != null) return cached
        val loaded = readRepoFromPrefs()
        repoCache = loaded
        return loaded
    }

    /**
     * 是否允许安装未校验签名的本地 jar（默认 false）。
     * 打开后 [installFromFile] 才可用；从仓库安装仍要过 SHA-256。
     */
    var allowUnsigned: Boolean
        get() = runCatching { prefs.getBoolean(KEY_ALLOW_UNSIGNED, false) }.getOrDefault(false)
        set(value) {
            runCatching { prefs.edit().putBoolean(KEY_ALLOW_UNSIGNED, value).apply() }
                .onFailure { LogBus.warn("保存“允许未签名插件”开关失败：" + describe(it)) }
            LogBus.info(if (value) "已允许安装未签名插件" else "已关闭未签名插件安装")
        }

    // -----------------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------------

    /** 已安装插件列表（内存快照，按 installed.json 里的顺序）。 */
    fun installed(): List<InstalledRecord> = records

    /**
     * 最近一次成功的仓库索引。内存里有就用内存；没有时只在后台线程补读缓存文件，
     * 主线程调用一律返回 null 而不是去读盘（冷启动时 [init] 会在后台把缓存预热好）。
     */
    fun index(): RepoIndex? {
        val cached = memIndex
        if (cached != null) return cached
        if (isMainThread()) return null
        val loaded = readIndexCache(repo) ?: return null
        memIndex = loaded
        return loaded
    }

    /** 仓库里有、但本地没有装同版本的插件（可以"安装"的那些）。 */
    fun available(): List<PluginManifest> {
        val idx = index() ?: return emptyList()
        val installedById = installed().associateBy { it.id }
        return idx.plugins.filter { manifest -> installedById[manifest.id]?.version != manifest.version }
    }

    /** 本地已装、且仓库里有更高版本的插件（可以"更新"的那些）。 */
    fun updates(): List<PluginManifest> {
        val idx = index() ?: return emptyList()
        val installedById = installed().associateBy { it.id }
        return idx.plugins.filter { manifest ->
            val record = installedById[manifest.id]
            record != null && compareVersions(manifest.version, record.version) > 0
        }
    }

    /**
     * 插件清单：先看内存里的索引，再看安装时留下的清单快照。
     * 两者都没有时，只在后台线程补读一次快照文件；主线程返回 null 不读盘。
     */
    fun manifestOf(id: String): PluginManifest? {
        val key = id.trim()
        if (key.isEmpty()) return null
        index()?.find(key)?.let { return it }
        snapshots[key]?.let { return it }
        if (isMainThread()) return null
        val loaded = InstalledStore.readManifestSnapshot(app, key) ?: return null
        snapshots[key] = loaded
        return loaded
    }

    /** 插件上一次装载失败的原因；没失败过就返回 null。 */
    fun loadError(id: String): String? = loadErrors[id.trim()]

    // -----------------------------------------------------------------------
    // 监听
    // -----------------------------------------------------------------------

    /** 注册状态变化监听（主线程回调）。重复注册同一对象只算一次。 */
    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    /** 注销监听。界面 onDestroy 里记得调，否则会泄漏界面引用。 */
    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // -----------------------------------------------------------------------
    // 动作：同步索引
    // -----------------------------------------------------------------------

    /**
     * 同步仓库索引。立刻返回，网络在后台线程；结果通过主线程回调 [onDone]（可为 null）。
     * 已有同步在跑时直接回 [SyncOutcome.Failed]，不会排两次队。
     */
    fun refreshIndex(onDone: ((SyncOutcome) -> Unit)? = null) {
        if (!syncing.compareAndSet(false, true)) {
            LogBus.warn("插件仓库正在同步中，忽略这次重复请求")
            onDone?.let { cb ->
                mainHandler.post { runCatching { cb(SyncOutcome.Failed("正在同步中，请稍候再试")) } }
            }
            return
        }
        notifyChanged()
        val source = repo
        scope.launch {
            val outcome = try {
                syncIndexBlocking(source)
            } catch (t: Throwable) {
                val reason = "同步插件仓库失败：" + describe(t)
                LogBus.error(reason)
                lastSyncErrorValue = reason
                SyncOutcome.Failed(reason)
            }
            syncing.set(false)
            notifyChanged()
            onDone?.let { cb -> mainHandler.post { runCatching { cb(outcome) } } }
        }
    }

    // -----------------------------------------------------------------------
    // 动作：安装
    // -----------------------------------------------------------------------

    /**
     * 从仓库安装一个插件。立刻返回；进度与结果都回主线程。
     * 成功后立刻装载并注册，步骤类型马上可用。
     *
     * @param onProgress (已下载字节, 总字节；服务端没给长度时总字节为 -1)，主线程回调
     */
    fun install(
        manifest: PluginManifest,
        onProgress: ((Long, Long) -> Unit)? = null,
        onDone: (InstallResult) -> Unit
    ) {
        val progress = mainThreadProgress(onProgress)
        scope.launch {
            val result = try {
                installBlocking(manifest, progress)
            } catch (t: Throwable) {
                val reason = "安装插件失败：" + describe(t)
                LogBus.error(reason)
                InstallResult.IoError(reason)
            }
            notifyChanged()
            mainHandler.post { runCatching { onDone(result) } }
        }
    }

    /**
     * 从本地 jar 文件安装。立刻返回，结果回主线程。
     * 必须先在设置里打开[allowUnsigned]；清单来自包内 META-INF/autotapper/plugin.json。
     * 版本不兼容（apiVersion / minHostVersionCode）会在装载阶段被拒，原因查 [loadError]。
     */
    fun installFromFile(file: File, onDone: (InstallResult) -> Unit) {
        scope.launch {
            val result = try {
                installFromFileBlocking(file)
            } catch (t: Throwable) {
                val reason = "安装本地插件失败：" + describe(t)
                LogBus.error(reason)
                InstallResult.IoError(reason)
            }
            notifyChanged()
            mainHandler.post { runCatching { onDone(result) } }
        }
    }

    // -----------------------------------------------------------------------
    // 动作：卸载 / 启停
    // -----------------------------------------------------------------------

    /**
     * 卸载插件：移出注册表、删文件、删 installed.json 记录、清插件自己的 prefs。
     * "引擎正在跑时不允许卸载"由调用方判断，这里只做动作。结果回主线程。
     */
    fun uninstall(id: String, onDone: (Boolean) -> Unit) {
        val key = id.trim()
        scope.launch {
            val ok = if (key.isEmpty()) {
                LogBus.error("卸载插件失败：插件 id 为空")
                false
            } else {
                try {
                    uninstallBlocking(key)
                } catch (t: Throwable) {
                    LogBus.error("卸载插件 " + key + " 失败：" + describe(t))
                    false
                }
            }
            if (ok) notifyChanged()
            mainHandler.post { runCatching { onDone(ok) } }
        }
    }

    /**
     * 启用 / 停用插件。停用会立刻把它从注册表摘掉（步骤类型从编辑器里消失）；
     * 启用会重新装载，装载失败则保持停用状态并把原因写进 [loadError]。结果回主线程。
     */
    fun setEnabled(id: String, enabled: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        val key = id.trim()
        scope.launch {
            val ok = if (key.isEmpty()) {
                LogBus.error("切换插件开关失败：插件 id 为空")
                false
            } else {
                try {
                    if (enabled) enableBlocking(key) else disableBlocking(key)
                } catch (t: Throwable) {
                    LogBus.error("切换插件 " + key + " 开关失败：" + describe(t))
                    false
                }
            }
            if (ok) notifyChanged()
            onDone?.let { cb -> mainHandler.post { runCatching { cb(ok) } } }
        }
    }

    /**
     * 插件连续崩溃后自动停用（由步骤桥接层调用）。
     * 内部就是 [setEnabled] 传 false，外加一条黄色告警日志说明是崩溃导致的，
     * 免得用户以为是自己手滑关掉的。本方法不阻塞、不抛异常。
     */
    fun disableAfterCrashes(id: String, reason: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        LogBus.warn("插件 " + key + " 连续崩溃，已自动停用：" + reason.trim().ifBlank { "原因未知" })
        runCatching { setEnabled(key, false) }
            .onFailure { LogBus.error("自动停用插件 " + key + " 失败：" + describe(it)) }
    }

    // -----------------------------------------------------------------------
    // 索引同步实现（全部在 IO 协程里跑）
    // -----------------------------------------------------------------------

    /** 一次索引文本请求的结果；失败时 [error] 是给用户看的中文原因。 */
    private class IndexResponse(
        val body: String?,
        val etag: String?,
        val notModified: Boolean,
        val error: String?
    )

    private fun requestIndex(url: String, etag: String?): IndexResponse = try {
        val response = PluginHttp.getText(url, etag)
        IndexResponse(response.body, response.newEtag, response.notModified, null)
    } catch (e: PluginHttp.HttpException) {
        IndexResponse(null, null, false, "同步插件仓库失败：" + (e.message ?: "网络错误"))
    } catch (t: Throwable) {
        IndexResponse(null, null, false, "同步插件仓库失败：" + describe(t))
    }

    /** 一次同步尝试的结论：只有网络层失败才值得换镜像重试。 */
    private sealed class SyncAttempt {

        /** 流程走完了：成功（含 304 用缓存）、退缓存兜住了、或遇到不该重试的硬错误。 */
        data class Done(val outcome: SyncOutcome) : SyncAttempt()

        /** 网络层失败（含地址被安全策略拒绝）；还没退缓存，由调用方决定要不要换官方直连再来一次。 */
        data class NetworkFailure(val reason: String) : SyncAttempt()
    }

    /**
     * 同步索引。镜像不通时自动退回官方直连再试一次，两次都不行才退缓存/报错。
     * 回退只做一步、不递归（免得镜像与官方直连互相踢皮球，也免得用户看到一串重试日志）。
     */
    private fun syncIndexBlocking(source: RepoSource): SyncOutcome {
        if (!source.isValid()) {
            return failSync("插件仓库地址不合法，请检查设置里的 owner/repo@branch")
        }
        when (val first = syncOnce(source)) {
            is SyncAttempt.Done -> return first.outcome
            is SyncAttempt.NetworkFailure -> {
                if (source.mirror == Mirror.Direct) {
                    return fallbackToCache(source, first.reason)
                }
                // 回退与重试各记一条：用户要能看见"镜像不通，已改用官方直连"
                LogBus.warn("镜像「" + source.mirror.label + "」不通（" + first.reason + "），已改用官方直连")
                val direct = source.withMirror(Mirror.Direct)
                LogBus.info("正在改用官方直连重试同步：" + direct.indexUrl)
                return when (val second = syncOnce(direct)) {
                    is SyncAttempt.Done -> second.outcome
                    is SyncAttempt.NetworkFailure -> fallbackToCache(
                        source,
                        first.reason + "；官方直连也失败：" + second.reason
                    )
                }
            }
        }
    }

    /** 真正发请求并落盘的一次尝试；网络层问题一律返回 [SyncAttempt.NetworkFailure]。 */
    private fun syncOnce(source: RepoSource): SyncAttempt {
        val url = source.indexUrl
        // 未通过白名单就不发请求：宁可明确报错，也不要把请求打到任意主机上
        if (!PluginHttp.isAllowedUrl(url)) {
            // 自定义镜像的主机没登记时也会走到这里；交给上层决定要不要退回官方直连
            return SyncAttempt.NetworkFailure("仓库地址不被允许（只支持 https 的受信主机）：" + url)
        }

        // ETag 与镜像绑定：换镜像换了地址，沿用它上一家的 ETag 只会得到一句没用的 304
        val etagKey = KEY_REPO_ETAG_PREFIX + source.cacheKeyWithMirror
        val savedEtag = runCatching { prefs.getString(etagKey, null) }.getOrNull()

        var response = requestIndex(url, savedEtag)
        // 304 时只需要读一次缓存：既用来判断"缓存还在不在"，也直接拿去用
        var cachedIndex = if (response.error == null && response.notModified) readIndexCache(source) else null
        if (response.error == null && response.notModified && cachedIndex == null) {
            // 缓存被清过（用户清数据 / 重装应用）时 304 毫无用途，去掉 ETag 再要一次完整索引
            LogBus.warn("服务器说插件索引没变，但本地缓存缺失，改为强制重新下载")
            response = requestIndex(url, null)
            cachedIndex = null
        }
        response.error?.let { return SyncAttempt.NetworkFailure(it) }

        if (response.notModified) {
            val cached = cachedIndex
                ?: return SyncAttempt.Done(failSync("插件索引没有变化，但本地缓存缺失，请重新同步"))
            memIndex = cached
            markSynced()
            LogBus.info("插件仓库无新变化，继续使用本地索引（" + cached.plugins.size + " 个插件）")
            return SyncAttempt.Done(SyncOutcome.Ok(cached.plugins.size, true))
        }

        val body = response.body.orEmpty()
        val parsed = PluginRepo.parse(body)
        if (parsed == null) {
            // 解析失败多半是仓库内容坏了；缓存里的旧索引还能用，别让界面变成空白
            return SyncAttempt.Done(fallbackToCache(source, "插件索引格式不正确，无法解析"))
        }
        memIndex = parsed
        writeIndexCache(source, body)
        val newEtag = response.etag
        runCatching {
            val editor = prefs.edit()
            if (!newEtag.isNullOrBlank()) editor.putString(etagKey, newEtag)
            editor.apply()
        }.onFailure { LogBus.warn("保存插件索引 ETag 失败：" + describe(it)) }
        markSynced()
        LogBus.result("插件仓库同步完成，共 " + parsed.plugins.size + " 个插件")
        return SyncAttempt.Done(SyncOutcome.Ok(parsed.plugins.size, false))
    }

    /**
     * 同步失败但本地有缓存：退回缓存并如实记下网络失败原因，
     * 界面可以据此提示"当前离线，显示的是缓存内容"。
     */
    private fun fallbackToCache(source: RepoSource, reason: String): SyncOutcome {
        // 先找当前镜像的缓存；镜像的缓存没有时，官方直连的旧缓存同样能用（内容还是同一份仓库）
        val cached = readIndexCache(source)
            ?: if (source.mirror != Mirror.Direct) readIndexCache(source.withMirror(Mirror.Direct)) else null
        if (cached == null) {
            return failSync(reason + "，本地也没有缓存索引")
        }
        memIndex = cached
        lastSyncErrorValue = reason
        LogBus.warn(reason + "，已退回本地缓存（" + cached.plugins.size + " 个插件）")
        return SyncOutcome.Ok(cached.plugins.size, true)
    }

    private fun failSync(reason: String): SyncOutcome.Failed {
        lastSyncErrorValue = reason
        LogBus.error(reason)
        return SyncOutcome.Failed(reason)
    }

    private fun markSynced() {
        lastSyncAtValue = System.currentTimeMillis()
        lastSyncErrorValue = null
    }

    // -----------------------------------------------------------------------
    // 索引缓存文件
    // -----------------------------------------------------------------------

    /** 索引缓存文件名带镜像标识：否则换镜像会读到上一个镜像留下的旧索引。 */
    private fun indexCacheFile(source: RepoSource): File =
        File(PluginInstaller.pluginsDir(app), "index-" + source.cacheKeyWithMirror + ".json")

    private fun cacheModifiedAt(source: RepoSource): Long =
        runCatching { indexCacheFile(source).lastModified() }.getOrDefault(0L).coerceAtLeast(0L)

    private fun readIndexCache(source: RepoSource): RepoIndex? {
        val file = indexCacheFile(source)
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            LogBus.warn("读取插件索引缓存失败：" + describe(t))
            return null
        }
        if (text.isBlank()) return null
        return PluginRepo.parse(text)
    }

    /** 写缓存：先写 .tmp 再改名，应用被杀不会留下半截 JSON。 */
    private fun writeIndexCache(source: RepoSource, body: String): Boolean {
        val dir = PluginInstaller.pluginsDir(app)
        if (!dir.exists() && !dir.mkdirs()) {
            LogBus.warn("无法创建插件目录，索引缓存没写成功：" + dir.absolutePath)
            return false
        }
        val target = indexCacheFile(source)
        val tmp = File(dir, target.name + ".tmp")
        return try {
            tmp.writeText(body)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                runCatching { tmp.delete() }
                LogBus.warn("写入插件索引缓存失败（改名未成功）：" + target.absolutePath)
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            LogBus.warn("写入插件索引缓存失败：" + describe(t))
            false
        }
    }

    // -----------------------------------------------------------------------
    // 安装实现
    // -----------------------------------------------------------------------

    /** PluginInstaller.install 是 suspend 的，这里必须也是 suspend（只从 IO 协程里调）。 */
    private suspend fun installBlocking(
        manifest: PluginManifest,
        onProgress: ((Long, Long) -> Unit)?
    ): InstallResult {
        val id = manifest.id.trim()
        if (id.isEmpty()) {
            LogBus.error("安装插件失败：清单里的 id 为空")
            return InstallResult.IoError("插件清单缺少 id，拒绝安装")
        }
        val source = repo
        if (!source.isValid()) {
            LogBus.error("安装插件 " + id + " 失败：当前仓库地址不合法")
            return InstallResult.IoError("当前插件仓库地址不合法，请先在设置里重新填写")
        }

        val result = PluginInstaller(app).install(manifest, source, onProgress)
        if (result !is InstallResult.Success) {
            LogBus.error("插件 " + id + " 安装失败：" + describeInstallFailure(result))
            return result
        }

        // 宿主要求签名时，没通过校验的包不能留在本机（PluginInstaller 只在缺 sigUrl 时才会是 false）
        if (!allowUnsigned && !result.verified && PluginSignature.isConfigured()) {
            val reason = "宿主要求签名，这个插件没有通过校验，已撤销安装"
            LogBus.error("插件 " + id + "：" + reason)
            revokeInstall(id)
            return InstallResult.SignatureInvalid(reason)
        }

        InstalledStore.readManifestSnapshot(app, id)?.let { snapshots[id] = it }
        records = runCatching { InstalledStore.read(app) }.getOrDefault(records)
        // 装完立刻装载，让步骤类型马上出现在编辑器里
        val record = records.firstOrNull { it.id == id }
        if (record == null) {
            LogBus.warn("插件 " + id + " 安装完成，但没在 installed.json 里找到记录，本次不装载")
        } else if (record.enabled) {
            loadRecord(record)
        }
        return result
    }

    /** 回滚一次刚装上的插件（宿主要求签名但包未通过校验时用）。 */
    private fun revokeInstall(id: String) {
        runCatching { PluginRegistry.unregister(id) }
        runCatching { PluginInstaller.deletePluginFiles(app, id) }
        runCatching { InstalledStore.remove(app, id) }
        snapshots.remove(id)
        loadErrors.remove(id)
        records = runCatching { InstalledStore.read(app) }.getOrDefault(emptyList())
    }

    private fun installFromFileBlocking(file: File): InstallResult {
        if (!allowUnsigned) {
            LogBus.warn("本地安装被拒：设置里没有打开“允许未签名插件”")
            return InstallResult.IoError("请先在设置里打开“允许未签名插件”")
        }
        if (!file.isFile) {
            return InstallResult.IoError("插件文件不存在：" + file.absolutePath)
        }

        val entryText: String? = try {
            readEntryJson(file)
        } catch (t: Throwable) {
            val reason = "读取插件包内清单失败：" + describe(t)
            LogBus.error(reason)
            return InstallResult.IoError(reason)
        }
        if (entryText == null) {
            LogBus.error("插件包缺少 " + ENTRY_JSON_PATH + "：" + file.name)
            return InstallResult.IoError("插件包缺少 " + ENTRY_JSON_PATH)
        }

        val json = try {
            JSONObject(entryText)
        } catch (t: Throwable) {
            val reason = "插件包内的清单不是合法 JSON：" + describe(t)
            LogBus.error(reason)
            return InstallResult.IoError(reason)
        }

        val id = json.optString("id").trim()
        val version = json.optString("version").trim()
        val entryClass = json.optString("entryClass").trim()
        if (id.isEmpty() || version.isEmpty()) {
            LogBus.error("插件包内的清单缺少 id 或版本号：" + file.name)
            return InstallResult.IoError("插件包内的清单缺少 id 或版本号")
        }
        if (entryClass.isEmpty()) {
            LogBus.error("插件包内的清单缺少 entryClass：" + id)
            return InstallResult.IoError("插件包内的清单缺少 entryClass，装上也无法装载")
        }
        // id 与版本号会被拼进文件路径，必须挡住 ../ 这类穿越
        if (!isSafeSegment(id) || !isSafeSegment(version)) {
            LogBus.error("插件包内的 id/版本号含非法字符：" + id + " / " + version)
            return InstallResult.IoError("插件 id/版本号含非法字符：" + id + " / " + version)
        }

        val sha256 = PluginSignature.sha256Hex(file)
        if (sha256 == null) {
            LogBus.error("无法计算插件包的 SHA-256：" + file.name)
            return InstallResult.IoError("无法计算插件包的 SHA-256")
        }

        val manifest = PluginManifest(
            id = id,
            name = json.optString("name").trim().ifBlank { id },
            version = version,
            apiVersion = json.optInt("apiVersion", PluginApi.VERSION),
            minHostVersionCode = json.optInt("minHostVersionCode", 0),
            entryClass = entryClass,
            requires = parseCapabilities(json),
            sizeBytes = json.optLong("sizeBytes", file.length()),
            sha256 = sha256,
            // 本地安装没有下载地址，字段留空即可（InstallResult.Success 才是成功依据）
            downloadUrl = "",
            sigUrl = null,
            description = json.optString("description", "").trim(),
            changelog = emptyList()
        )

        val target = PluginInstaller.jarPath(app, id, version)
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LogBus.error("无法创建插件目录：" + parent.absolutePath)
            return InstallResult.IoError("无法创建插件目录：" + parent.absolutePath)
        }
        val copied = try {
            file.copyTo(target, overwrite = true)
            true
        } catch (t: Throwable) {
            LogBus.error("复制插件包到 " + target.absolutePath + " 失败：" + describe(t))
            false
        }
        if (!copied || !target.isFile) {
            runCatching { target.delete() }
            return InstallResult.IoError("无法把插件包复制到 " + target.absolutePath)
        }
        // 装完就置只读：DexClassLoader 加载的包不该再被改
        runCatching { target.setReadOnly() }

        val copiedSha = PluginSignature.sha256Hex(target)
        if (copiedSha == null || !copiedSha.equals(sha256, ignoreCase = true)) {
            runCatching { target.delete() }
            LogBus.error("插件 " + id + " 落盘后摘要不一致，已删除")
            return InstallResult.DigestMismatch(sha256.lowercase(Locale.ROOT), copiedSha ?: "")
        }
        removeOtherJars(parent, target)
        PluginInstaller.deleteDexCache(app, id)

        // 清单快照 + installed.json：冷启动靠快照拿 entryClass，本地安装一律记未校验
        InstalledStore.writeManifestSnapshot(app, manifest)
        val record = InstalledRecord(
            id = id,
            version = version,
            sha256 = copiedSha,
            installedAt = System.currentTimeMillis(),
            enabled = true,
            verified = false
        )
        InstalledStore.upsert(app, record)
        records = runCatching { InstalledStore.read(app) }.getOrDefault(records)
        snapshots[id] = manifest
        loadRecord(record)
        LogBus.result("插件 " + manifest.name + " " + version + " 已从本地文件安装（未校验签名）")
        return InstallResult.Success(target, false)
    }

    /**
     * 读 jar 内的 META-INF/autotapper/plugin.json；没有这个条目返回 null。
     * 其它 IO 异常向上抛，由调用方统一转成 IoError。
     */
    private fun readEntryJson(jar: File): String? {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(ENTRY_JSON_PATH) ?: return null
            // 清单只该有几百字节，这里挡一下畸形的超大条目，免得把内存读爆
            if (entry.size > MAX_ENTRY_JSON_BYTES) {
                throw IllegalStateException("插件包内的清单异常大（" + entry.size + " 字节）")
            }
            return zip.getInputStream(entry).use { input ->
                input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
    }

    /** 只认 [A-Za-z0-9._-]，且不许出现 ..（id 与版本号都会被拼进文件路径）。 */
    private fun isSafeSegment(value: String): Boolean =
        SAFE_SEGMENT.matches(value) && !value.contains("..")

    /**
     * 解析插件包里声明的能力。认不出来的直接忽略并 warn —— 旧版宿主遇到新能力时
     * 应该还能装，而不是整条拒掉。
     */
    private fun parseCapabilities(json: JSONObject): Set<Capability> {
        val array = json.optJSONArray("requires") ?: return emptySet()
        val result = LinkedHashSet<Capability>()
        for (i in 0 until array.length()) {
            val raw = array.optString(i).trim()
            if (raw.isEmpty()) continue
            val capability = runCatching { Capability.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()
            if (capability == null) {
                LogBus.warn("插件包声明了宿主不认识的能力 " + raw + "，已忽略")
            } else {
                result.add(capability)
            }
        }
        return result
    }

    /** 删掉同目录下别的版本 jar：留着只会让 dex 缓存膨胀，版本回退时还可能装错。 */
    private fun removeOtherJars(dir: File?, keep: File) {
        if (dir == null) return
        runCatching {
            dir.listFiles()?.forEach { other ->
                if (other.isFile && other.name.endsWith(".jar") && other.absolutePath != keep.absolutePath) {
                    other.delete()
                }
            }
        }
    }

    private fun describeInstallFailure(result: InstallResult): String = when (result) {
        is InstallResult.DigestMismatch ->
            "文件摘要不一致（声明 " + result.expected.take(12) + "…，实际 " + result.actual.take(12) + "…）"

        is InstallResult.SignatureInvalid -> result.reason
        is InstallResult.NetworkError -> result.message
        is InstallResult.IoError -> result.message
        // Success 不会走到这里，但 sealed 分支必须写全
        is InstallResult.Success -> "已安装完成"
    }

    // -----------------------------------------------------------------------
    // 卸载 / 启停实现
    // -----------------------------------------------------------------------

    private fun uninstallBlocking(id: String): Boolean {
        val hadRecord = InstalledStore.find(app, id) != null
        PluginRegistry.unregister(id)
        PluginInstaller.deletePluginFiles(app, id)
        InstalledStore.remove(app, id)
        // 插件配置跟着插件走：不清掉的话，同名插件重装会读到上一次的脏配置
        runCatching {
            app.getSharedPreferences(pluginPrefsName(id), Context.MODE_PRIVATE).edit().clear().apply()
        }.onFailure { LogBus.warn("清理插件 " + id + " 的配置失败：" + describe(it)) }
        snapshots.remove(id)
        loadErrors.remove(id)
        records = runCatching { InstalledStore.read(app) }.getOrDefault(emptyList())
        if (hadRecord) {
            LogBus.result("插件 " + id + " 已卸载")
        } else {
            LogBus.warn("插件 " + id + " 没有安装记录，已清理残留文件")
        }
        return hadRecord
    }

    private fun enableBlocking(id: String): Boolean {
        val record = InstalledStore.find(app, id)
        if (record == null) {
            LogBus.error("启用插件失败：找不到 " + id + " 的安装记录")
            return false
        }
        // 先装载再落盘：装载失败的插件不应该被记成启用状态
        if (!loadRecord(record)) {
            records = runCatching { InstalledStore.read(app) }.getOrDefault(records)
            return false
        }
        val updated = InstalledStore.setEnabled(app, id, true)
        if (updated == null) {
            LogBus.error("启用插件失败：写 installed.json 时找不到 " + id)
            return false
        }
        records = updated
        LogBus.info("插件 " + id + " 已启用")
        return true
    }

    private fun disableBlocking(id: String): Boolean {
        PluginRegistry.unregister(id)
        val updated = InstalledStore.setEnabled(app, id, false)
        if (updated == null) {
            LogBus.error("停用插件失败：找不到 " + id + " 的安装记录")
            return false
        }
        records = updated
        LogBus.info("插件 " + id + " 已停用")
        return true
    }

    // -----------------------------------------------------------------------
    // 装载（init / 安装 / 启用共用）
    // -----------------------------------------------------------------------

    /**
     * 装载一条已安装记录并注册。任何异常都吃掉，失败原因写进 [loadErrors]。
     * 必须在后台线程调用（会读盘 + 走 DexClassLoader）。
     *
     * @return 是否装载并注册成功
     */
    private fun loadRecord(record: InstalledRecord): Boolean {
        val id = record.id.trim()
        if (id.isEmpty()) return false
        return try {
            val snapshot = InstalledStore.readManifestSnapshot(app, id)
            if (snapshot == null) {
                failLoad(id, "缺少清单快照，无法确定入口类，请重新安装该插件")
                return false
            }
            val entryClass = snapshot.entryClass.trim()
            if (entryClass.isEmpty()) {
                failLoad(id, "清单快照没有声明入口类，请重新安装该插件")
                return false
            }
            val jar = PluginInstaller.jarPath(app, id, record.version)
            when (val outcome = PluginLoader.loadDetailed(app, jar, entryClass, id, record.version)) {
                is PluginLoader.LoadOutcome.Ok -> {
                    // 插件自己声明的 id 必须与安装记录一致，否则注册表与 installed.json 会各说各话
                    val loadedId = runCatching { outcome.plugin.id }.getOrNull()?.trim().orEmpty()
                    if (loadedId.isNotEmpty() && loadedId != id) {
                        failLoad(id, "插件声明的 id 是 " + loadedId + "，与安装记录不一致，已拒绝装载")
                        return false
                    }
                    PluginRegistry.register(outcome.plugin, jar)
                    loadErrors.remove(id)
                    true
                }

                is PluginLoader.LoadOutcome.Failed -> {
                    failLoad(id, outcome.message)
                    false
                }
            }
        } catch (t: Throwable) {
            failLoad(id, "装载过程抛异常：" + describe(t))
            false
        }
    }

    private fun failLoad(id: String, reason: String) {
        loadErrors[id] = reason
        LogBus.error("插件 " + id + " 装载失败，暂不可用：" + reason)
    }

    // -----------------------------------------------------------------------
    // 冷启动装载
    // -----------------------------------------------------------------------

    /** init 只调一次：把缓存索引、已装记录读进内存，再逐个装载启用的插件。 */
    private fun startInitialLoad() {
        if (!initialLoadStarted.compareAndSet(false, true)) return
        scope.launch {
            try {
                // 1. 先把索引缓存预热：isReady 之后主线程调 index() 立刻就有数据
                val source = repo
                val cachedIndex = runCatching { readIndexCache(source) }.getOrNull()
                if (cachedIndex != null) {
                    memIndex = cachedIndex
                    // 用缓存文件的修改时间近似"上次成功同步时间"，比一直显示"从未同步"更贴近事实
                    lastSyncAtValue = cacheModifiedAt(source)
                }
                // 2. 已装记录 + 清单快照：只在这里读一次盘，之后查询全走内存
                val list = runCatching { InstalledStore.read(app) }.getOrDefault(emptyList())
                records = list
                list.forEach { record ->
                    runCatching { InstalledStore.readManifestSnapshot(app, record.id) }
                        .getOrNull()
                        ?.let { snapshots[record.id] = it }
                }
                // 3. 逐个装载已启用的插件；单个失败不影响其它插件
                list.filter { it.enabled }.forEach { record ->
                    runCatching { loadRecord(record) }
                        .onFailure { LogBus.error("装载插件 " + record.id + " 时抛异常：" + describe(it)) }
                }
                LogBus.info(
                    "插件冷启动装载完成：共 " + list.size + " 个已安装，" +
                        PluginRegistry.pluginIds().size + " 个已装载"
                )
            } catch (t: Throwable) {
                LogBus.error("插件冷启动装载失败：" + describe(t))
            } finally {
                isReadyFlag = true
                notifyChanged()
            }
        }
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /** 通知所有监听者。统一 post 到主线程，避免各调用点各切各的线程。 */
    private fun notifyChanged() {
        val targets = synchronized(listeners) { listeners.toList() }
        if (targets.isEmpty()) return
        mainHandler.post {
            targets.forEach { listener ->
                // 单个监听者抛异常不能影响其它界面
                runCatching { listener.onPluginsChanged() }
                    .onFailure { LogBus.error("插件状态监听回调异常：" + describe(it)) }
            }
        }
    }

    /** 把可能来自任意线程的进度回调包成主线程回调。 */
    private fun mainThreadProgress(callback: ((Long, Long) -> Unit)?): ((Long, Long) -> Unit)? {
        if (callback == null) return null
        return { done, total ->
            mainHandler.post { runCatching { callback(done, total) } }
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 从 prefs 里恢复当前仓库。任何时候都只应有一条 plugin_repo_<cacheKey>，见 [repo] setter。 */
    private fun readRepoFromPrefs(): RepoSource {
        val stored = runCatching {
            prefs.all.entries.firstOrNull { (key, value) ->
                key.startsWith(KEY_REPO_PREFIX) && !key.startsWith(KEY_REPO_ETAG_PREFIX) && value is String
            }?.value as? String
        }.getOrNull()
        if (stored.isNullOrBlank()) return RepoSource.DEFAULT
        val parsed = RepoSource.parse(stored)
        if (parsed == null) {
            LogBus.warn("prefs 里的插件仓库地址无法解析（" + stored + "），已回退到默认仓库")
            return RepoSource.DEFAULT
        }
        return parsed
    }

    /** 从 prefs 里恢复镜像设置。没存过就用 [Mirror.JsDelivr]，坏数据一律回退官方直连，绝不抛。 */
    private fun readMirrorFromPrefs(): Mirror {
        val stored = runCatching { prefs.getString(KEY_MIRROR, null) }.getOrNull()
        if (stored.isNullOrBlank()) return Mirror.JsDelivr
        val decoded = Mirror.decode(stored)
        if (decoded == null) {
            LogBus.warn("prefs 里的插件镜像设置无法识别（" + stored + "），已回退到官方直连")
            return Mirror.Direct
        }
        val reason = decoded.invalidReason()
        if (reason != null) {
            LogBus.warn("prefs 里保存的插件镜像不可用（" + reason + "），已回退到官方直连")
            return Mirror.Direct
        }
        return decoded
    }

    /**
     * 把自定义镜像的主机登记进 [PluginHttp] 白名单。
     * 只有用户显式保存过镜像才会走到这里（保存时登记一次，冷启动从 prefs 恢复时再登记一次）。
     */
    private fun registerMirrorHost(mirror: Mirror.Custom) {
        val host = mirror.host
        if (host.isNullOrBlank()) {
            LogBus.warn("自定义镜像里解析不出主机名，同步会被安全策略拒绝：" + mirror.template)
            return
        }
        if (PluginHttp.allowHost(host)) {
            LogBus.info("已登记自定义镜像主机：" + host)
        } else {
            LogBus.warn("自定义镜像主机不合法，已忽略：" + host)
        }
    }

    /**
     * 插件配置的 prefs 名，必须与 PluginHostContext 的规则完全一致（契约第 3 节），
     * 否则卸载时清不掉插件自己的配置。
     */
    private fun pluginPrefsName(id: String): String {
        val safe = id.map { ch ->
            val keep = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                ch == '.' || ch == '_' || ch == '-'
            if (keep) ch else '_'
        }.joinToString("")
        return if (safe.isEmpty()) "plugin_unknown" else "plugin_" + safe
    }

    /** 异常转一句人话：避免界面看到 "java.lang.RuntimeException" 这种无信息量的文案。 */
    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName
        else t.javaClass.simpleName + ": " + message
    }

    companion object {

        // ---- prefs key（全部以 plugin_ 开头，避免与主程序其它设置撞名）----
        private const val KEY_REPO_PREFIX = "plugin_repo_"
        private const val KEY_REPO_ETAG_PREFIX = "plugin_repo_etag_"
        private const val KEY_MIRROR = "plugin_mirror"
        private const val KEY_ALLOW_UNSIGNED = "plugin_allow_unsigned"

        /** 插件 jar 内自描述清单的路径（构建时由 plugin.json 打进 jar）。 */
        private const val ENTRY_JSON_PATH = "META-INF/autotapper/plugin.json"

        /** 清单只该有几百字节，超过这个就当畸形包拒掉，免得读爆内存。 */
        private const val MAX_ENTRY_JSON_BYTES = 64L * 1024L

        /** id 与版本号会被拼进文件路径，只认这些字符且不许出现 ..。 */
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._-]+$")

        private val lock = Any()

        @Volatile
        private var instance: PluginManager? = null

        /**
         * 建单例并立刻在后台开始装载已装插件。可重复调用（幂等），主线程安全。
         * 建议在 Application.onCreate 里调一次。
         */
        fun init(context: Context): PluginManager {
            instance?.let { return it }
            synchronized(lock) {
                instance?.let { return it }
                val created = PluginManager(context.applicationContext)
                instance = created
                created.startInitialLoad()
                return created
            }
        }

        /** 取单例；还没 [init] 过就等价于 [init]。主线程安全，绝不抛异常。 */
        fun get(context: Context): PluginManager = init(context)

        /** 取单例；还没 [init] 过返回 null（不抛异常，也不隐式初始化）。 */
        fun peek(): PluginManager? = instance

        /**
         * 版本号比较：按点分段，段内能转数字就按数字比，转不了按字符串比；
         * 长度不同时短的补 "0"（所以 2 == 2.0.0）。
         *
         * internal 是为了让单元测试直接打它——这是一个纯函数，没有理由藏在 private 里。
         */
        internal fun compareVersions(left: String, right: String): Int {
            val a = left.trim().split('.')
            val b = right.trim().split('.')
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val x = a.getOrElse(i) { "0" }.trim()
                val y = b.getOrElse(i) { "0" }.trim()
                val nx = x.toLongOrNull()
                val ny = y.toLongOrNull()
                val diff = if (nx != null && ny != null) {
                    nx.compareTo(ny)
                } else {
                    x.compareTo(y, ignoreCase = true)
                }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
