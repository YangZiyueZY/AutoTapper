package com.example.autotapper.runtime

import android.content.Context
import android.system.Os
import com.example.autotapper.LogBus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次安装的结果。每一种失败都对应界面上的一句确定提示，不做模糊的"安装失败"。
 */
sealed interface InstallResult {

    /**
     * 安装成功。[path] 是落在 filesDir/plugins/&lt;id&gt;/&lt;ver&gt;.jar 的只读文件。
     * [verified] 表示分离签名是否真的校验通过；宿主没配置公钥时为 false
     * （界面必须显示成"未校验签名"，不能显示成"已校验"）。
     */
    data class Success(val path: File, val verified: Boolean) : InstallResult

    /** SHA-256 与 index.json 声明不一致：文件被篡改或下载损坏。 */
    data class DigestMismatch(val expected: String, val actual: String) : InstallResult

    /** 分离签名无效（公钥已配置但验签失败）。 */
    data class SignatureInvalid(val reason: String) : InstallResult

    /** 网络层失败：离线、超时、HTTP 错误码、地址被安全策略拒绝。 */
    data class NetworkError(val message: String) : InstallResult

    /** 本地文件操作失败：目录不可写、磁盘满、路径非法。 */
    data class IoError(val message: String) : InstallResult
}

/**
 * 已安装插件的一条记录，与 installed.json 里的对象一一对应：
 * {"installed":[{"id","version","sha256","installedAt","enabled","verified"}]}
 */
data class InstalledRecord(
    val id: String,
    val version: String,
    val sha256: String,
    val installedAt: Long,
    val enabled: Boolean,
    val verified: Boolean
) {

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("version", version)
        .put("sha256", sha256)
        .put("installedAt", installedAt)
        .put("enabled", enabled)
        .put("verified", verified)

    companion object {

        fun fromJson(obj: JSONObject): InstalledRecord? {
            val id = obj.optString("id").trim()
            val version = obj.optString("version").trim()
            if (id.isEmpty() || version.isEmpty()) return null
            return InstalledRecord(
                id = id,
                version = version,
                sha256 = obj.optString("sha256", "").trim(),
                installedAt = obj.optLong("installedAt", 0L),
                enabled = obj.optBoolean("enabled", true),
                verified = obj.optBoolean("verified", false)
            )
        }
    }
}

/**
 * installed.json 的读写。整个文件小，直接整体重写；写入用"临时文件 + 改名"保证原子性，
 * 应用被杀也不会留下半截 JSON。
 *
 * 另外每个插件目录下还有一份 manifest.json（安装时的 index 条目快照），
 * 这样冷启动装载插件时不需要联网也能拿到 entryClass。
 */
object InstalledStore {

    const val FILE_NAME = "installed.json"
    const val MANIFEST_FILE_NAME = "manifest.json"

    private const val ROOT_KEY = "installed"

    private val lock = Any()

    fun installedFile(context: Context): File = File(PluginInstaller.pluginsDir(context), FILE_NAME)

    /** 读全部记录；文件缺失或损坏都返回空表，坏数据写一条 warn。 */
    /** 读全部记录；文件缺失或损坏都返回空表，坏数据写一条 warn。 */
    fun read(context: Context): List<InstalledRecord> {
        synchronized(lock) {
            val file = installedFile(context)
            if (!file.isFile) return emptyList()
            val text = runCatching { file.readText() }.getOrElse {
                LogBus.warn("读取 installed.json 失败：" + (it.message ?: it.javaClass.simpleName))
                return emptyList()
            }
            if (text.isBlank()) return emptyList()
            return try {
                val root = JSONObject(text)
                val array = root.optJSONArray(ROOT_KEY) ?: return emptyList()
                val records = ArrayList<InstalledRecord>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val record = InstalledRecord.fromJson(obj)
                    if (record == null) {
                        LogBus.warn("installed.json 第 " + (i + 1) + " 条记录损坏，已忽略")
                    } else {
                        records.add(record)
                    }
                }
                records
            } catch (t: Throwable) {
                LogBus.warn("installed.json 解析失败，按空处理：" + (t.message ?: t.javaClass.simpleName))
                emptyList()
            }
        }
    }

    /** 整体写回。返回是否写成功。 */
    fun write(context: Context, records: List<InstalledRecord>): Boolean {
        synchronized(lock) {
            val dir = PluginInstaller.pluginsDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                LogBus.error("无法创建插件目录：" + dir.absolutePath)
                return false
            }
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            val text = JSONObject().put(ROOT_KEY, array).toString(2)
            val target = installedFile(context)
            val tmp = File(dir, FILE_NAME + ".tmp")
            return try {
                tmp.writeText(text)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    LogBus.error("写入 installed.json 失败（改名失败）")
                    false
                } else {
                    true
                }
            } catch (t: Throwable) {
                runCatching { tmp.delete() }
                LogBus.error("写入 installed.json 失败：" + (t.message ?: t.javaClass.simpleName))
                false
            }
        }
    }

    /** 新增或覆盖某个插件的记录，返回写入后的全表。 */
    fun upsert(context: Context, record: InstalledRecord): List<InstalledRecord> {
        synchronized(lock) {
            val records = read(context).toMutableList()
            val index = records.indexOfFirst { it.id == record.id }
            if (index >= 0) records[index] = record else records.add(record)
            write(context, records)
            return records
        }
    }

    fun remove(context: Context, id: String): List<InstalledRecord> {
        synchronized(lock) {
            val records = read(context).filterNot { it.id == id }
            write(context, records)
            return records
        }
    }

    /** 只改 enabled 字段；找不到记录返回 null。 */
    fun setEnabled(context: Context, id: String, enabled: Boolean): List<InstalledRecord>? {
        synchronized(lock) {
            val records = read(context).toMutableList()
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return null
            records[index] = records[index].copy(enabled = enabled)
            write(context, records)
            return records
        }
    }

    fun find(context: Context, id: String): InstalledRecord? = read(context).firstOrNull { it.id == id }

    /** 写安装时的清单快照；失败只警告，不影响安装结果。 */
    fun writeManifestSnapshot(context: Context, manifest: PluginManifest): Boolean {
        return try {
            val dir = File(PluginInstaller.pluginsDir(context), manifest.id)
            if (!dir.exists() && !dir.mkdirs()) return false
            File(dir, MANIFEST_FILE_NAME).writeText(manifest.toJson().toString(2))
            true
        } catch (t: Throwable) {
            LogBus.warn("写入插件清单快照失败：" + manifest.id + "：" + (t.message ?: t.javaClass.simpleName))
            false
        }
    }

    /** 读清单快照；缺失或损坏返回 null。 */
    fun readManifestSnapshot(context: Context, id: String): PluginManifest? {
        val file = File(File(PluginInstaller.pluginsDir(context), id), MANIFEST_FILE_NAME)
        if (!file.isFile) return null
        return try {
            PluginRepo.parseManifest(JSONObject(file.readText()))
        } catch (t: Throwable) {
            LogBus.warn("读取插件清单快照失败：" + id + "：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    /** 删掉清单快照（卸载时用）。 */
    fun deleteManifestSnapshot(context: Context, id: String) {
        runCatching { File(File(PluginInstaller.pluginsDir(context), id), MANIFEST_FILE_NAME).delete() }
    }
}

/**
 * 负责把插件从仓库搬到本机：下载 → SHA-256 → （可选）分离签名 → 置只读 → 原子落到 filesDir。
 * 所有方法都在 Dispatchers.IO 上执行，可放心从主线程用协程调用。
 */
class PluginInstaller(private val context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 安装一个插件。
     *
     * @param onProgress 下载进度回调，在 IO 线程上触发；界面自己 post 回主线程
     */
    suspend fun install(
        manifest: PluginManifest,
        repo: RepoSource = RepoSource.DEFAULT,
        onProgress: ((Long, Long) -> Unit)? = null
    ): InstallResult = withContext(Dispatchers.IO) {
        installBlocking(manifest, repo, onProgress)
    }

    private fun installBlocking(
        manifest: PluginManifest,
        repo: RepoSource,
        onProgress: ((Long, Long) -> Unit)?
    ): InstallResult {
        val id = manifest.id.trim()
        val version = manifest.version.trim()
        if (id.isEmpty() || version.isEmpty()) {
            return InstallResult.IoError("插件 id 或版本号为空，拒绝安装")
        }
        // 防止 ../ 之类的路径穿越：id 与版本都会被拼进文件路径
        if (!isSafeSegment(id) || !isSafeSegment(version)) {
            return InstallResult.IoError("插件 id/版本含非法字符：" + id + " / " + version)
        }

        val downloadDir = File(appContext.codeCacheDir, "plugin-dl")
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            return InstallResult.IoError("无法创建下载目录：" + downloadDir.absolutePath)
        }
        val downloaded = File(downloadDir, id + "-" + version + ".jar")
        if (downloaded.exists()) downloaded.delete()

        // 1. 下载：repo 里带着当前镜像，相对路径（如 dist/xxx.jar）会自动拼成镜像地址；
        //    镜像不通时退回官方直连再试一次（只回退一次，不递归）
        val downloadError = downloadWithMirrorFallback(repo, manifest.downloadUrl, downloaded, onProgress)
        if (downloadError != null) {
            runCatching { downloaded.delete() }
            return InstallResult.NetworkError(downloadError)
        }
        LogBus.info("插件 " + id + " " + version + " 下载完成：" + downloaded.length() + " 字节")

        // 2. SHA-256 必须匹配
        val actual = PluginSignature.sha256Hex(downloaded)
        if (actual == null) {
            runCatching { downloaded.delete() }
            return InstallResult.IoError("无法计算下载文件的 SHA-256")
        }
        if (!actual.equals(manifest.sha256.trim(), ignoreCase = true)) {
            runCatching { downloaded.delete() }
            LogBus.error("插件 " + id + " SHA-256 不匹配，已丢弃下载文件")
            return InstallResult.DigestMismatch(manifest.sha256.trim().lowercase(), actual)
        }

        // 3. 分离签名（有 sigUrl 才做）
        var verified = false
        val sigUrl = manifest.sigUrl
        if (!sigUrl.isNullOrBlank()) {
            // 签名文件同样走镜像；镜像取不到时退回官方直连一次
            val fetched = fetchTextWithMirrorFallback(repo, sigUrl)
            val sigText = fetched.text
            if (sigText == null) {
                runCatching { downloaded.delete() }
                return InstallResult.NetworkError("签名文件下载失败：" + (fetched.error ?: "网络错误"))
            }
            when (val status = PluginSignature.verifyDetachedSignature(downloaded, sigText)) {
                PluginSignature.Status.VALID -> {
                    verified = true
                    LogBus.info("插件 " + id + " 签名校验通过")
                }

                PluginSignature.Status.INVALID -> {
                    runCatching { downloaded.delete() }
                    LogBus.error("插件 " + id + " 签名无效，已丢弃下载文件")
                    return InstallResult.SignatureInvalid("签名与安装包不匹配")
                }

                PluginSignature.Status.NOT_CONFIGURED -> {
                    LogBus.warn("宿主未配置签名公钥，插件 " + id + " 仅做了 SHA-256 校验")
                }
            }
        } else {
            LogBus.warn("插件 " + id + " 未提供签名文件，仅做了 SHA-256 校验")
        }

        // 4. 置只读：DexClassLoader 只读，装完就不该再被改
        setReadOnly(downloaded)

        // 5. 原子移动到 filesDir/plugins/<id>/<ver>.jar
        val pluginDir = File(pluginsDir(appContext), id)
        if (!pluginDir.exists() && !pluginDir.mkdirs()) {
            runCatching { downloaded.delete() }
            return InstallResult.IoError("无法创建插件目录：" + pluginDir.absolutePath)
        }
        val target = File(pluginDir, version + ".jar")
        if (target.exists()) target.delete()
        var moved = runCatching { downloaded.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            // 理论上不会有，但 codeCacheDir 与 filesDir 在不同分区时必须兜底
            moved = runCatching {
                downloaded.copyTo(target, overwrite = true)
                downloaded.delete()
                true
            }.getOrDefault(false)
            if (moved) setReadOnly(target)
        }
        if (!moved || !target.isFile) {
            runCatching { downloaded.delete() }
            return InstallResult.IoError("无法把插件移动到 " + target.absolutePath)
        }
        if (!PluginSignature.verifyJarSha256(target, manifest.sha256)) {
            LogBus.error("插件 " + id + " 落盘后摘要不一致，安装中止")
            runCatching { target.delete() }
            return InstallResult.IoError("安装落盘后摘要不一致")
        }

        // 6. 清单快照 + installed.json
        InstalledStore.writeManifestSnapshot(appContext, manifest)
        InstalledStore.upsert(
            appContext,
            InstalledRecord(
                id = id,
                version = version,
                sha256 = manifest.sha256.trim().lowercase(),
                installedAt = System.currentTimeMillis(),
                enabled = true,
                verified = verified
            )
        )

        // 7. 清掉旧版本 jar 与旧 dex 缓存，避免磁盘越攒越大、也避免加载到旧类
        removeOtherVersions(pluginDir, keep = target)
        deleteDexCache(appContext, id)

        LogBus.result("插件 " + manifest.name + " " + version + " 安装完成（签名" + (if (verified) "已校验" else "未校验") + "）")
        return InstallResult.Success(target, verified)
    }

    /** 一次文本请求的结果；[error] 为 null 表示成功。 */
    private class TextFetch(val text: String?, val error: String?)

    /**
     * 按当前镜像下载文件；失败且当前不是官方直连时，退回官方直连再试一次。
     * 只回退一次：退回后仍失败就直接返回失败原因，不做"镜像 ↔ 官方"的递归互踢。
     *
     * @return null 表示成功，否则是一句给用户看的中文失败原因
     */
    private fun downloadWithMirrorFallback(
        repo: RepoSource,
        path: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)?
    ): String? {
        // jsDelivr 这类只服务文本的镜像对 .jar 会直接 403（servesBinary=false），
        // 这种镜像下二进制产物一律走官方直连，别让每个插件都先白撞一次。
        if (!repo.mirror.servesBinary && repo.mirror != Mirror.Direct) {
            LogBus.info("镜像「" + repo.mirror.label + "」不转发二进制产物，插件包直接走官方直连")
            return downloadOnce(repo.withMirror(Mirror.Direct).resolve(path), dest, onProgress)
        }
        val primaryUrl = repo.resolve(path)
        val firstError = downloadOnce(primaryUrl, dest, onProgress)
        if (firstError == null) return null
        if (repo.mirror == Mirror.Direct) return firstError
        LogBus.warn("镜像「" + repo.mirror.label + "」下载不通（" + firstError + "），已改用官方直连")
        val directUrl = repo.withMirror(Mirror.Direct).resolve(path)
        LogBus.info("正在改用官方直连重试下载：" + directUrl)
        val secondError = downloadOnce(directUrl, dest, onProgress)
        if (secondError == null) {
            LogBus.info("官方直连下载成功，本次安装继续")
        }
        return secondError
    }

    /** 单次下载。地址不在白名单时直接给原因，绝不发请求。 */
    private fun downloadOnce(url: String, dest: File, onProgress: ((Long, Long) -> Unit)?): String? {
        if (!PluginHttp.isAllowedUrl(url)) {
            return "下载地址不在允许范围内：" + url
        }
        return try {
            PluginHttp.download(url, dest, onProgress)
            null
        } catch (e: PluginHttp.HttpException) {
            runCatching { dest.delete() }
            e.message ?: "下载失败"
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            "下载失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 签名文件这类小文本：同样走镜像 + 一次官方直连回退。 */
    private fun fetchTextWithMirrorFallback(repo: RepoSource, path: String): TextFetch {
        if (!repo.mirror.servesBinary && repo.mirror != Mirror.Direct) {
            // 签名文件与 jar 同源同目录，镜像不转发二进制时也没必要先去撞
            return fetchTextOnce(repo.withMirror(Mirror.Direct).resolve(path))
        }
        val first = fetchTextOnce(repo.resolve(path))
        if (first.error == null) return first
        if (repo.mirror == Mirror.Direct) return first
        LogBus.warn("镜像「" + repo.mirror.label + "」取签名文件失败（" + first.error + "），已改用官方直连")
        val directUrl = repo.withMirror(Mirror.Direct).resolve(path)
        LogBus.info("正在改用官方直连重试下载签名文件：" + directUrl)
        return fetchTextOnce(directUrl)
    }

    private fun fetchTextOnce(url: String): TextFetch {
        if (!PluginHttp.isAllowedUrl(url)) {
            return TextFetch(null, "地址不在允许范围内：" + url)
        }
        return try {
            TextFetch(PluginHttp.getText(url).body, null)
        } catch (e: PluginHttp.HttpException) {
            TextFetch(null, e.message ?: "网络错误")
        } catch (t: Throwable) {
            TextFetch(null, t.message ?: t.javaClass.simpleName)
        }
    }

    /** 删掉同 id 的旧版本 jar。版本回退时也走这里，保证目录里只有一份。 */
    private fun removeOtherVersions(pluginDir: File, keep: File) {
        runCatching {
            pluginDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".jar") && file.absolutePath != keep.absolutePath) {
                    if (file.delete()) {
                        LogBus.info("已清理旧版本插件文件：" + file.name)
                    }
                }
            }
        }
    }

    private fun setReadOnly(file: File) {
        runCatching { file.setReadOnly() }
        runCatching { Os.chmod(file.absolutePath, 0b100100100) } // 0444：只读
    }

    companion object {

        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._-]+$")

        private fun isSafeSegment(value: String): Boolean =
            SAFE_SEGMENT.matches(value) && !value.contains("..")

        /** filesDir/plugins —— 所有插件文件的根。 */
        fun pluginsDir(context: Context): File = File(context.filesDir, "plugins")

        /** 某个插件某个版本的 jar 落点。 */
        fun jarPath(context: Context, id: String, version: String): File =
            File(File(pluginsDir(context), id), version + ".jar")

        /** DexClassLoader 的优化输出目录，卸载/升级时一并清掉。 */
        fun dexCacheDir(context: Context, id: String): File =
            File(File(context.codeCacheDir, "plugin-dex"), id)

        fun deleteDexCache(context: Context, id: String) {
            deleteRecursively(dexCacheDir(context, id))
        }

        /** 卸载用：删掉插件目录（含清单快照）、dex 缓存，以及下载目录里残留的同 id 文件。 */
        fun deletePluginFiles(context: Context, id: String) {
            deleteRecursively(File(pluginsDir(context), id))
            deleteDexCache(context, id)
            runCatching {
                File(context.codeCacheDir, "plugin-dl").listFiles()?.forEach { file ->
                    if (file.name.startsWith(id + "-")) file.delete()
                }
            }
        }

        private fun deleteRecursively(file: File) {
            if (!file.exists()) return
            runCatching {
                if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
                file.delete()
            }
        }
    }
}
