package com.example.autotapper.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件专用 HTTP 客户端。只用 HttpURLConnection，不引第三方依赖。
 *
 * 安全底线（与 UpdateChecker 的思路一致，插件通道只认受信主机）：
 * 1. 只允许 https，http 和任何其他 scheme 一律拒绝；
 * 2. host 必须在白名单内：GitHub 四域 + jsDelivr 四节点 + 用户显式登记的自定义镜像主机
 *    （[allowHost] / [resetAllowedHosts]）；
 * 3. 端口只允许默认 443；
 * 4. 不接受带 userinfo 的地址（https://raw.githubusercontent.com@evil.com 这种绕过）；
 * 5. 重定向手动跟随，每一跳都重新做上面四条检查。
 *
 * 为什么要有可登记的 host：国内直连 raw.githubusercontent.com 经常不通，用户会填一个自定义镜像。
 * 这类主机只能通过 [allowHost] 登记，而 [allowHost] 只接受"像主机名"的值（不含路径/端口/空白），
 * 登记范围永远只有用户自己在设置页保存过的那一个，上面五条底线一条都没松。
 *
 * 同步阻塞实现：调用方负责放到 Dispatchers.IO 上跑。
 */
object PluginHttp {

    private const val USER_AGENT = "AutoTapper-PluginClient"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val BUFFER_SIZE = 64 * 1024

    /** DNS 主机名的长度上限（RFC 1035），超过一律当非法值拒绝。 */
    private const val MAX_HOST_LENGTH = 253

    /** 固定白名单：GitHub 内容分发四域 + jsDelivr 镜像四节点。 */
    private val ALLOWED_HOSTS = setOf(
        // 仓库页 / raw / API / Release 资产重定向目标
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
        "objects.githubusercontent.com",
        // jsDelivr 的常用节点：国内直连 GitHub 常常不通，选镜像后就走这些域
        "cdn.jsdelivr.net",
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net",
        "testingcf.jsdelivr.net"
    )

    /**
     * 用户显式保存过的自定义镜像主机。
     * 用 ConcurrentHashMap 撑起来的 Set，读写都线程安全（同步线程登记、界面线程同步都可能碰）。
     */
    private val extraHosts: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 登记一个自定义镜像主机（只有用户在设置页显式保存镜像时才会调到这里）。
     * 只接受合法主机名：小写、只含 [a-z0-9.-]、必须带点、不能有空白/路径/端口/userinfo。
     *
     * @return true 表示登记成功（或本来就在），false 表示这个值不像主机名、已忽略
     */
    fun allowHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        extraHosts.add(normalized)
        return true
    }

    /** 撤销所有登记过的自定义镜像主机（切回官方直连 / 内置镜像时调，不留下残留的信任）。 */
    fun resetAllowedHosts() {
        extraHosts.clear()
    }

    /** 规范化并校验主机名；不像主机名就返回 null。 */
    private fun normalizeHost(host: String): String? {
        val trimmed = host.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null
        // 端口由 illegalReason 统一管（只允许 443），这里先把 ":443" 剥掉再校验主机名
        val clean = trimmed.substringBefore(':')
        if (clean.isEmpty() || clean.length > MAX_HOST_LENGTH) return null
        if (clean.startsWith('.') || clean.endsWith('.') || clean.contains("..")) return null
        if (clean.startsWith('-') || clean.endsWith('-')) return null
        if (clean.any { ch -> !((ch in 'a'..'z') || (ch in '0'..'9') || ch == '.' || ch == '-') }) return null
        // 至少得是 name.tld 这种形状：单段主机名（localhost）一律不登记
        if (!clean.contains('.')) return null
        return clean
    }

    /** 网络层错误的统一类型，上层据此返回 NetworkError。 */
    open class HttpException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** URL 被安全检查拒绝。 */
    class UnsafeUrlException(url: String, reason: String) :
        HttpException("拒绝请求不安全的地址（" + reason + "）：" + url)

    /** 一次带 ETag 的文本请求结果。notModified 为 true 时 body 为空串。 */
    data class TextResponse(val body: String, val newEtag: String?, val notModified: Boolean)

    // -----------------------------------------------------------------------
    // 安全检查
    // -----------------------------------------------------------------------

    /** 这个地址能不能请求。界面要提前把不可用的自定义仓库地址标出来时也能用。 */
    fun isAllowedUrl(url: String): Boolean = validate(url) != null

    /** 校验失败时返回一句原因，成功返回 null。 */
    private fun illegalReason(uri: URI): String? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return "缺少协议"
        if (scheme != "https") return "只允许 https"
        val host = uri.host?.lowercase(Locale.ROOT) ?: return "缺少主机名"
        // 固定白名单之外，只认用户显式保存过的自定义镜像主机
        if (host !in ALLOWED_HOSTS && host !in extraHosts) return "主机 " + host + " 不在白名单"
        val port = uri.port
        if (port != -1 && port != 443) return "只允许 443 端口"
        if (!uri.userInfo.isNullOrEmpty()) return "不接受带用户信息的地址"
        return null
    }

    private fun validate(url: String): URL? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return null
        }
        if (illegalReason(uri) != null) return null
        return try {
            uri.toURL()
        } catch (_: Throwable) {
            null
        }
    }

    private fun requireValid(url: String): URL =
        validate(url) ?: throw UnsafeUrlException(url, runCatching {
            val uri = URI(url.trim())
            illegalReason(uri) ?: "地址无法解析"
        }.getOrDefault("地址无法解析"))

    /** 相对 Location 转绝对地址。 */
    private fun resolveLocation(base: URL, location: String): URL {
        val resolved = try {
            base.toURI().resolve(location.trim()).toURL()
        } catch (t: Throwable) {
            throw HttpException("重定向地址无法解析：" + location, t)
        }
        // 每一跳都重新校验
        requireValid(resolved.toString())
        return resolved
    }

    // -----------------------------------------------------------------------
    // 连接
    // -----------------------------------------------------------------------

    /**
     * 建立连接并处理重定向。返回时 responseCode 已经可用。
     * 调用方负责 disconnect。
     */
    private fun open(url: String, accept: String, etag: String?): HttpURLConnection {
        var current = requireValid(url)
        var hops = 0
        while (true) {
            val conn = try {
                current.openConnection() as HttpURLConnection
            } catch (t: Throwable) {
                throw HttpException("无法建立连接：" + current, t)
            }
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", accept)
                if (!etag.isNullOrBlank()) {
                    conn.setRequestProperty("If-None-Match", etag)
                }
                val code = try {
                    conn.responseCode
                } catch (t: Throwable) {
                    throw HttpException("连接失败：" + current, t)
                }
                val redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308
                if (!redirect) {
                    return conn
                }
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) {
                    throw HttpException("服务器返回 " + code + " 却没有 Location")
                }
                hops++
                if (hops > MAX_REDIRECTS) {
                    throw HttpException("重定向次数超过 " + MAX_REDIRECTS + " 次")
                }
                conn.disconnect()
                current = resolveLocation(current, location)
            } catch (t: Throwable) {
                runCatching { conn.disconnect() }
                throw t
            }
        }
    }

    // -----------------------------------------------------------------------
    // 文本请求
    // -----------------------------------------------------------------------

    /**
     * GET 一段文本，带 ETag 条件请求。
     *
     * @param etag 上次拿到的 ETag，第一次传 null
     * @return notModified = true 表示服务端回 304，body 是空串，etag 原样返回
     */
    fun getText(url: String, etag: String? = null, accept: String = "application/json, text/plain, */*"): TextResponse {
        val conn = open(url, accept, etag)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return TextResponse("", etag, true)
            }
            if (code !in 200..299) {
                throw HttpException("HTTP " + code + " " + (conn.responseMessage ?: ""))
            }
            val stream = try {
                conn.inputStream
            } catch (t: Throwable) {
                throw HttpException("读取响应失败：HTTP " + code, t)
            }
            val body = stream.use { it.readTextUtf8() }
            val newEtag = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() } ?: etag
            return TextResponse(body, newEtag, false)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // -----------------------------------------------------------------------
    // 下载
    // -----------------------------------------------------------------------

    /**
     * 流式下载到 [destFile]，先写 destFile.part 再改名，避免留下半截文件。
     *
     * @param onProgress (已下载字节, 总字节；服务端没给 Content-Length 时总字节为 -1)
     * @return 实际写入的字节数
     */
    fun download(url: String, destFile: File, onProgress: ((Long, Long) -> Unit)? = null): Long {
        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw HttpException("无法创建下载目录：" + parent.absolutePath)
        }
        val partial = File(parent, destFile.name + ".part")
        if (partial.exists()) partial.delete()

        val conn = open(url, "application/octet-stream", null)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw HttpException("HTTP " + code + " " + (conn.responseMessage ?: ""))
            }
            val total = try {
                conn.contentLengthLong
            } catch (_: Throwable) {
                -1L
            }
            var downloaded = 0L
            try {
                val input = conn.inputStream
                FileOutputStream(partial).use { output ->
                    input.use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            } catch (t: Throwable) {
                runCatching { partial.delete() }
                throw if (t is HttpException) t else HttpException("下载中断：" + (t.message ?: t.javaClass.simpleName), t)
            }
            if (total > 0 && downloaded != total) {
                runCatching { partial.delete() }
                throw HttpException("下载不完整：收到 " + downloaded + " / " + total + " 字节")
            }
            if (destFile.exists()) destFile.delete()
            if (!partial.renameTo(destFile)) {
                runCatching { partial.delete() }
                throw HttpException("无法写入目标文件：" + destFile.absolutePath)
            }
            return downloaded
        } finally {
            runCatching { conn.disconnect() }
            if (partial.exists()) runCatching { partial.delete() }
        }
    }

    /** 读一段文本，显式按 UTF-8 解码（GitHub 的 index.json 一定是 UTF-8）。 */
    private fun InputStream.readTextUtf8(): String = bufferedReader(Charsets.UTF_8).use { it.readText() }
}
