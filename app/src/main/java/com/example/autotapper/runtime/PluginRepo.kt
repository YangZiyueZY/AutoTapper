package com.example.autotapper.runtime

import com.example.autotapper.BuildConfig
import com.example.autotapper.LogBus
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.PluginApi
import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件仓库（GitHub 仓库 + index.json）的数据模型与解析。
 *
 * 仓库根目录必须放一个 index.json，结构：
 * {
 *   "schema": 1,
 *   "hostApiVersion": 1,
 *   "generatedAt": "2025-01-01T00:00:00Z",
 *   "plugins": [
 *     {
 *       "id": "tap-styles",
 *       "name": "点击样式",
 *       "version": "1.0.0",
 *       "apiVersion": 1,
 *       "minHostVersionCode": 6,
 *       "entryClass": "com.autotapper.plugin.tapstyles.TapStylesPlugin",
 *       "requires": ["SCREENSHOT"],
 *       "sizeBytes": 18432,
 *       "sha256": "…64 位小写十六进制…",
 *       "downloadUrl": "plugins/tap-styles-1.0.0.jar",   // 也允许写完整 https 地址
 *       "sigUrl": "plugins/tap-styles-1.0.0.jar.sig",    // 可选，BASE64 分离签名
 *       "description": "长按、连击、滑动、多点",
 *       "changelog": ["首版"]
 *     }
 *   ]
 * }
 *
 * 解析全程容错：坏条目只跳过并写 LogBus，绝不把异常抛给界面。
 * downloadUrl / sigUrl 允许写仓库内相对路径，安装时按 [RepoSource] 拼成 raw 地址。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val minHostVersionCode: Int,
    val entryClass: String,
    val requires: Set<Capability>,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val sigUrl: String?,
    val description: String,
    val changelog: List<String>
) {

    /**
     * 与当前宿主不兼容时返回一句人话原因，兼容返回 null。
     * 界面可以据此把安装按钮置灰，而不是装到一半才失败。
     */
    fun incompatibleReason(hostVersionCode: Int = BuildConfig.VERSION_CODE): String? = when {
        apiVersion != PluginApi.VERSION ->
            "插件 API 版本 " + apiVersion + "，宿主只支持 " + PluginApi.VERSION
        minHostVersionCode > hostVersionCode ->
            "需要主程序 versionCode ≥ " + minHostVersionCode + "（当前 " + hostVersionCode + "）"
        else -> null
    }

    /** 写回 sidecar 清单用；与 [PluginRepo.parseManifest] 互逆。 */
    fun toJson(): JSONObject {
        val requiresArray = JSONArray()
        requires.forEach { requiresArray.put(it.name) }
        val changelogArray = JSONArray()
        changelog.forEach { changelogArray.put(it) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("apiVersion", apiVersion)
            .put("minHostVersionCode", minHostVersionCode)
            .put("entryClass", entryClass)
            .put("requires", requiresArray)
            .put("sizeBytes", sizeBytes)
            .put("sha256", sha256)
            .put("downloadUrl", downloadUrl)
            .put("sigUrl", sigUrl ?: JSONObject.NULL)
            .put("description", description)
            .put("changelog", changelogArray)
    }
}

/** 解析后的仓库索引。 */
data class RepoIndex(
    val schema: Int,
    val hostApiVersion: Int,
    val generatedAt: String,
    val plugins: List<PluginManifest>
) {

    fun find(id: String): PluginManifest? = plugins.firstOrNull { it.id == id }

    /** 只保留能在当前宿主上装载的插件。 */
    fun compatible(hostVersionCode: Int = BuildConfig.VERSION_CODE): List<PluginManifest> =
        plugins.filter { it.incompatibleReason(hostVersionCode) == null }
}

/** 自定义镜像的缓存标识里模板最多保留多少字符，剩下的用哈希补齐，避免文件名超长。 */
private const val CUSTOM_TAG_MAX_CHARS = 24

/** 自定义镜像模板的校验规则；[Mirror] 与 [RepoSource.previewMirror] 共用同一套。 */
private fun customMirrorReason(template: String): String? {
    val text = template.trim()
    if (text.isEmpty()) return "自定义镜像地址不能为空"
    if (!text.startsWith("https://", ignoreCase = true)) return "自定义镜像地址必须以 https:// 开头"
    if (text.any { it.isWhitespace() }) return "自定义镜像地址里不能有空格"
    if (!text.contains(Mirror.PLACEHOLDER_OWNER)) return "自定义镜像地址必须包含 " + Mirror.PLACEHOLDER_OWNER + " 占位符"
    if (!text.contains(Mirror.PLACEHOLDER_REPO)) return "自定义镜像地址必须包含 " + Mirror.PLACEHOLDER_REPO + " 占位符"
    if (!text.contains(Mirror.PLACEHOLDER_BRANCH)) return "自定义镜像地址必须包含 " + Mirror.PLACEHOLDER_BRANCH + " 占位符"
    // 除三个占位符外不许再出现花括号：否则用户会以为能写别的变量，最后拼出一条奇怪的地址
    val stripped = text
        .replace(Mirror.PLACEHOLDER_OWNER, "")
        .replace(Mirror.PLACEHOLDER_REPO, "")
        .replace(Mirror.PLACEHOLDER_BRANCH, "")
    if (stripped.contains('{') || stripped.contains('}')) {
        return "自定义镜像地址只认 {owner} {repo} {branch} 三个占位符"
    }
    return null
}

/**
 * 插件仓库的镜像策略。
 *
 * 为什么要有镜像：raw.githubusercontent.com 在国内经常连不上，换一个镜像就能继续装插件。
 * 三种形态的信任范围不一样，所以做成密封类型而不是一个字符串：
 * - [Direct]：官方直连，只碰 GitHub 自己的域；
 * - [JsDelivr]：公开 CDN 镜像（cdn.jsdelivr.net），国内通常能通；
 * - [Custom]：用户自己填的模板，必须同时含 {owner} {repo} {branch} 三个占位符、且是 https，
 *   主机名要由设置页显式保存时登记进 [PluginHttp] 白名单（见 PluginHttp.allowHost）。
 *
 * 这里只做"拼地址 + 校验"，不碰网络、不碰 SharedPreferences，纯 JVM 单元测试可以直接跑。
 */
sealed interface Mirror {

    /** 官方直连：https://raw.githubusercontent.com/{owner}/{repo}/{branch} */
    data object Direct : Mirror

    /** jsDelivr 镜像：https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch} */
    data object JsDelivr : Mirror

    /** 自定义模板，例如 https://mirror.example.com/gh/{owner}/{repo}/{branch} */
    data class Custom(val template: String) : Mirror

    /**
     * 这个镜像能不能下发**二进制**产物（插件 jar）。
     *
     * 实测：jsDelivr 的三个节点对 `.jar` 一律返回 **403 Forbidden**（同一仓库的 index.json、
     * README.md 都正常 200），所以 jar 不能走 jsDelivr。与其每个插件都先撞一次 403 再回退，
     * 不如在这里如实声明，让 PluginInstaller 直接走官方直连。
     *
     * 自定义模板（自建代理 / ghproxy 这类 raw 代理）按可用来处理——它们转发的是原始字节。
     */
    val servesBinary: Boolean
        get() = when (this) {
            Direct -> true
            JsDelivr -> false
            is Custom -> true
        }

    /** 界面显示名（设置页的"当前镜像"行与提示吐司都用它）。 */
    val label: String
        get() = when (this) {
            Direct -> "官方直连"
            JsDelivr -> "jsDelivr 镜像"
            is Custom -> "自定义"
        }

    /**
     * 这个镜像真正会去请求的主机名（小写）。
     * 设置页据此登记白名单；解析不出来返回 null（此时这个镜像不可用）。
     */
    val host: String?
        get() = when (this) {
            Direct -> "raw.githubusercontent.com"
            JsDelivr -> "cdn.jsdelivr.net"
            is Custom -> runCatching {
                // 先把占位符替换成假值，才能交给 URI 解析出主机名
                val probe = template.trim()
                    .replace(PLACEHOLDER_OWNER, "owner")
                    .replace(PLACEHOLDER_REPO, "repo")
                    .replace(PLACEHOLDER_BRANCH, "main")
                URI(probe).host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /**
     * 可用性校验：null = 可用，否则是一句给用户看的中文原因。
     * 自定义模板要求：非空、以 https:// 开头、没有空白、三个占位符齐全、除占位符外没有别的花括号。
     */
    fun invalidReason(): String? = when (this) {
        Direct -> null
        JsDelivr -> null
        is Custom -> customMirrorReason(template)
    }

    /** 持久化用的紧凑写法：direct / jsdelivr / custom:模板。 */
    fun encode(): String = when (this) {
        Direct -> "direct"
        JsDelivr -> "jsdelivr"
        is Custom -> "custom:" + template
    }

    /**
     * 缓存分键用的短标识。索引缓存文件与 ETag 都带上它，
     * 否则换镜像会读到上一个镜像留下的旧索引（甚至命中一份过期的 304 缓存）。
     */
    val cacheTag: String
        get() = when (this) {
            Direct -> "direct"
            JsDelivr -> "jsdelivr"
            is Custom -> {
                val sanitized = template.map { ch ->
                    val keep = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                        ch == '-' || ch == '_' || ch == '.'
                    if (keep) ch else '_'
                }.joinToString("")
                "custom_" + sanitized.take(CUSTOM_TAG_MAX_CHARS) + "_" + Integer.toHexString(template.hashCode())
            }
        }

    /** 拼出仓库根地址（不带结尾斜杠）。owner/repo/branch 的合法性由 [RepoSource] 负责。 */
    fun baseFor(owner: String, repo: String, branch: String): String = when (this) {
        Direct -> "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch
        JsDelivr -> "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch
        is Custom -> template.trim()
            .replace(PLACEHOLDER_OWNER, owner)
            .replace(PLACEHOLDER_REPO, repo)
            .replace(PLACEHOLDER_BRANCH, branch)
            .trimEnd('/')
    }

    companion object {

        const val PLACEHOLDER_OWNER = "{owner}"
        const val PLACEHOLDER_REPO = "{repo}"
        const val PLACEHOLDER_BRANCH = "{branch}"

        /** 解析 [encode] 写出来的字符串；认不出来返回 null，由调用方决定回退到哪一个。 */
        fun decode(text: String): Mirror? {
            val raw = text.trim()
            if (raw.isEmpty()) return null
            if (raw.equals("direct", ignoreCase = true)) return Direct
            if (raw.equals("jsdelivr", ignoreCase = true)) return JsDelivr
            if (raw.startsWith("custom:", ignoreCase = true)) return Custom(raw.substring("custom:".length))
            return null
        }
    }
}

/**
 * 插件仓库来源。默认官方仓库 YangZiyueZY/AutoTapperPlugin@main，也支持用户自定义。
 *
 * [mirror] 只决定"从哪个主机取文件"，不影响仓库身份（[label] / [cacheKey] 仍是 owner/repo@branch）：
 * 已装记录、清单快照都挂在仓库上，换镜像不会让它们失效。
 */
data class RepoSource(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val mirror: Mirror = Mirror.Direct
) {

    /** index.json 的完整地址（随镜像变化）。 */
    val indexUrl: String get() = rawUrl("index.json")

    /** 仓库在镜像上的根地址（不带结尾斜杠）。 */
    val rawBase: String get() = mirror.baseFor(owner, repo, branch)

    /** 当前镜像的中文显示名：官方直连 / jsDelivr 镜像 / 自定义。 */
    val mirrorLabel: String get() = mirror.label

    /** 镜像配置本身是否可用（自定义模板可能是坏的）。 */
    fun isMirrorUsable(): Boolean = mirror.invalidReason() == null

    /** 仓库内相对路径 → raw 地址。 */
    fun rawUrl(path: String): String {
        val clean = path.trim().removePrefix("./").trimStart('/')
        return rawBase + "/" + clean
    }

    /** 相对路径补全成绝对地址；已经是 http(s) 的原样返回。 */
    fun resolve(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> rawUrl(trimmed)
        }
    }

    /** 用于 prefs / 缓存文件名，避免不同仓库互相覆盖。刻意不含镜像：仓库身份与镜像无关。 */
    val cacheKey: String
        get() = (owner + "_" + repo + "_" + branch)
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")

    /**
     * 带镜像标识的缓存键。索引缓存文件名与 ETag key 都用它 ——
     * 不带镜像标识时，换镜像会读到上一个镜像留下的旧索引，甚至被一句 304 糊住。
     */
    val cacheKeyWithMirror: String get() = cacheKey + "_" + mirror.cacheTag

    /** 只换镜像，其余不变。 */
    fun withMirror(mirror: Mirror): RepoSource = copy(mirror = mirror)

    val label: String get() = owner + "/" + repo + "@" + branch

    /**
     * 校验三段是否合法。只接受 GitHub 上可能出现的字符，
     * 这样拼进 URL 时不会被注入路径或协议。
     */
    fun isValid(): Boolean = isValidPart(owner) && isValidPart(repo) && isValidPart(branch, allowSlash = true)

    private fun isValidPart(value: String, allowSlash: Boolean = false): Boolean {
        if (value.isBlank()) return false
        if (value == "." || value == "..") return false
        if (value.contains("..")) return false
        return value.all { ch ->
            ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || (allowSlash && ch == '/')
        }
    }

    override fun toString(): String = label

    companion object {

        val DEFAULT = RepoSource("YangZiyueZY", "AutoTapperPlugin", "main")

        /**
         * 预览自定义模板拼出来的仓库根地址（设置页"预览地址"按钮用）。
         * 模板不合法、或解析不出主机名时返回 null。不联网、不落盘。
         */
        fun previewMirror(template: String, owner: String, repo: String, branch: String): String? {
            val mirror = Mirror.Custom(template)
            if (mirror.invalidReason() != null) return null
            if (mirror.host.isNullOrBlank()) return null
            val base = mirror.baseFor(
                owner.trim().ifBlank { "owner" },
                repo.trim().ifBlank { "repo" },
                branch.trim().ifBlank { "main" }
            )
            return base.takeIf { it.isNotBlank() }
        }

        /**
         * 解析用户输入："owner/repo"、"owner/repo@branch"、
         * 或者直接粘 GitHub 地址 "https://github.com/owner/repo/tree/branch"。
         * 解析不出来返回 null。
         */
        fun parse(text: String): RepoSource? {
            var raw = text.trim()
            if (raw.isEmpty()) return null
            raw = raw.removePrefix("https://").removePrefix("http://")
            if (raw.startsWith("github.com/", ignoreCase = true)) {
                raw = raw.substring("github.com/".length)
            }
            raw = raw.trimEnd('/')
            var branch = "main"
            val at = raw.lastIndexOf('@')
            if (at > 0) {
                branch = raw.substring(at + 1).trim().ifBlank { "main" }
                raw = raw.substring(0, at)
            }
            // 支持 owner/repo/tree/branch 这种粘贴形式
            val treeIdx = raw.indexOf("/tree/")
            if (treeIdx > 0) {
                branch = raw.substring(treeIdx + "/tree/".length).trim().trimEnd('/').ifBlank { "main" }
                raw = raw.substring(0, treeIdx)
            }
            val parts = raw.split('/').filter { it.isNotBlank() }
            if (parts.size != 2) return null
            val source = RepoSource(parts[0].trim(), parts[1].trim().removeSuffix(".git"), branch)
            return source.takeIf { it.isValid() }
        }
    }
}

/**
 * index.json 的解析器。所有方法都不抛异常：出错只写 LogBus 并返回 null / 跳过该条目。
 */
object PluginRepo {

    /** 宿主认识的索引结构版本；不认识的版本直接拒绝，避免误读字段。 */
    const val SUPPORTED_SCHEMA = 1

    /** 解析整个索引。返回 null 表示整份索引都不可用。 */
    fun parse(jsonText: String): RepoIndex? {
        if (jsonText.isBlank()) {
            LogBus.error("插件索引为空")
            return null
        }
        val root = try {
            JSONObject(jsonText)
        } catch (t: Throwable) {
            LogBus.error("插件索引不是合法 JSON：" + (t.message ?: t.javaClass.simpleName))
            return null
        }

        val schema = if (root.has("schema")) root.optInt("schema", 0) else {
            LogBus.warn("插件索引缺少 schema 字段，按 " + SUPPORTED_SCHEMA + " 处理")
            SUPPORTED_SCHEMA
        }
        if (schema != SUPPORTED_SCHEMA) {
            LogBus.error("插件索引 schema=" + schema + "，宿主只支持 " + SUPPORTED_SCHEMA)
            return null
        }

        val hostApiVersion = root.optInt("hostApiVersion", PluginApi.VERSION)
        if (hostApiVersion > PluginApi.VERSION) {
            LogBus.warn(
                "插件仓库面向 API " + hostApiVersion + "，当前宿主只有 " + PluginApi.VERSION + "，部分插件会被拒绝"
            )
        }

        val array = root.optJSONArray("plugins")
        if (array == null) {
            LogBus.error("插件索引缺少 plugins 数组")
            return null
        }

        val parsed = ArrayList<PluginManifest>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: run {
                LogBus.warn("插件索引第 " + (i + 1) + " 条不是对象，已跳过")
                null
            } ?: continue
            val manifest = parseManifest(obj) ?: continue
            if (!seen.add(manifest.id)) {
                LogBus.warn("插件索引里 id 重复：" + manifest.id + "，只保留第一条")
                continue
            }
            parsed.add(manifest)
        }

        return RepoIndex(
            schema = schema,
            hostApiVersion = hostApiVersion,
            generatedAt = root.optString("generatedAt", ""),
            plugins = parsed
        )
    }

    /**
     * 解析单条插件声明。缺 id / sha256 / downloadUrl 的条目直接跳过并 warn。
     * （另外缺 entryClass 的也跳过：没有入口类的条目装着也用不了。）
     */
    fun parseManifest(obj: JSONObject): PluginManifest? {
        val id = obj.optString("id").trim()
        if (id.isEmpty()) {
            LogBus.warn("插件索引中有一条缺少 id，已跳过")
            return null
        }
        val sha256 = obj.optString("sha256").trim().lowercase(Locale.ROOT)
        if (sha256.isEmpty()) {
            LogBus.warn("插件 " + id + " 缺少 sha256，已跳过")
            return null
        }
        val downloadUrl = obj.optString("downloadUrl").trim()
        if (downloadUrl.isEmpty()) {
            LogBus.warn("插件 " + id + " 缺少 downloadUrl，已跳过")
            return null
        }
        val entryClass = obj.optString("entryClass").trim()
        if (entryClass.isEmpty()) {
            LogBus.warn("插件 " + id + " 缺少 entryClass，已跳过")
            return null
        }
        if (sha256.length != 64 || sha256.any { it !in "0123456789abcdef" }) {
            LogBus.warn("插件 " + id + " 的 sha256 不是 64 位十六进制，安装时必然校验失败：" + sha256)
        }

        val apiVersion = if (obj.has("apiVersion")) {
            obj.optInt("apiVersion", PluginApi.VERSION)
        } else {
            LogBus.warn("插件 " + id + " 未声明 apiVersion，按宿主版本 " + PluginApi.VERSION + " 处理")
            PluginApi.VERSION
        }

        val sigUrl = obj.optString("sigUrl").trim().takeIf { it.isNotEmpty() }

        return PluginManifest(
            id = id,
            name = obj.optString("name").trim().ifBlank { id },
            version = obj.optString("version").trim().ifBlank { "0" },
            apiVersion = apiVersion,
            minHostVersionCode = obj.optInt("minHostVersionCode", 0),
            entryClass = entryClass,
            requires = parseCapabilities(obj, id),
            sizeBytes = obj.optLong("sizeBytes", -1L),
            sha256 = sha256,
            downloadUrl = downloadUrl,
            sigUrl = sigUrl,
            description = obj.optString("description", "").trim(),
            changelog = parseChangelog(obj)
        )
    }

    private fun parseCapabilities(obj: JSONObject, id: String): Set<Capability> {
        val array = obj.optJSONArray("requires") ?: obj.optJSONArray("capabilities") ?: return emptySet()
        val result = LinkedHashSet<Capability>()
        for (i in 0 until array.length()) {
            val raw = array.optString(i).trim()
            if (raw.isEmpty()) continue
            val capability = runCatching { Capability.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()
            if (capability == null) {
                LogBus.warn("插件 " + id + " 声明了宿主不认识的能力 " + raw + "，已忽略")
            } else {
                result.add(capability)
            }
        }
        return result
    }

    private fun parseChangelog(obj: JSONObject): List<String> {
        val array = obj.optJSONArray("changelog")
        if (array != null) {
            val lines = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val line = array.optString(i).trim()
                if (line.isNotEmpty()) lines.add(line)
            }
            return lines
        }
        val text = obj.optString("changelog", "").trim()
        if (text.isEmpty()) return emptyList()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
