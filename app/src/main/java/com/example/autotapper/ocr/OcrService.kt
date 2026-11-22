package com.example.autotapper.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.autotapper.LogBus
import com.example.autotapper.R
import com.example.autotapper.plugin.OcrApi
import com.example.autotapper.plugin.OcrResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/**
 * 云端识字服务（PaddleOCR-VL 异步任务）。协议细节全部在 [PaddleOcrClient] 的类注释里。
 *
 * 三条硬要求：
 * 1. **绝不抛异常给界面/插件**：任何失败都变成 [OcrResult.text] 为空 + error 里一句人话；
 *    唯一的例外是协程取消（CancellationException）—— 那是控制流，吞掉会让插件停不下来。
 * 2. **同一帧不重复花钱**：按像素哈希缓存结果，LRU [CACHE_CAPACITY] 条。
 *    连点场景里同一块屏幕会被反复识别，这张缓存是省钱也是省时间的关键。
 * 3. **密钥只在主程序**：token 从 [OcrSettings] 读，插件拿到的是 [OcrApi] 门面，看不到令牌。
 */
class OcrService(context: Context) : OcrApi {

    private val appContext: Context = context.applicationContext

    /** 访问顺序的 LinkedHashMap：迭代顺序就是最近使用顺序，从头部淘汰即 LRU。 */
    private val cache = LinkedHashMap<String, OcrResult>(CACHE_INITIAL_CAPACITY, 0.75f, true)
    private val cacheLock = Any()

    override fun isConfigured(): Boolean = OcrSettings.isConfigured(appContext)

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val startedAt = SystemClock.elapsedRealtime()

        if (!isConfigured()) {
            return failure(startedAt, appContext.getString(R.string.ocr_error_not_configured))
        }

        // 像素哈希只是缓存键；算不出来（比如硬件位图）就跳过缓存，不影响识别本身
        val key = runCatching { frameKey(bitmap) }.getOrNull()
        if (key != null) {
            val cached = cacheGet(key)
            if (cached != null) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                LogBus.info("识字命中缓存，跳过这次上传。")
                return OcrResult(cached.text, cached.lines, elapsed)
            }
        }

        return try {
            val result = withContext(Dispatchers.IO) { runJob(bitmap, startedAt) }
            if (result.isSuccess && key != null) {
                cachePut(key, result)
            }
            result
        } catch (cancel: CancellationException) {
            // 取消不是失败：往外抛，让插件的协程真正停下来
            LogBus.info("识字任务已取消。")
            throw cancel
        } catch (t: Throwable) {
            failure(startedAt, describeError(t))
        }
    }

    // ------------------------------------------------------------ 一次完整任务

    private suspend fun runJob(bitmap: Bitmap, startedAt: Long): OcrResult {
        val token = OcrSettings.token(appContext)
        val endpoint = OcrSettings.endpoint(appContext)
        if (token.isEmpty()) {
            return failure(startedAt, appContext.getString(R.string.ocr_error_not_configured))
        }

        val png = compressToPng(bitmap)
            ?: return failure(startedAt, appContext.getString(R.string.ocr_error_encode))

        val jobId = PaddleOcrClient.submit(endpoint, token, png, PaddleOcrClient.DEFAULT_MODEL)
        LogBus.info("识字任务已提交：" + jobId + "（" + (png.size / 1024) + " KB）")

        val jsonUrl = PaddleOcrClient.awaitResult(
            endpoint,
            token,
            jobId,
            POLL_TIMEOUT_MS,
            POLL_INTERVAL_MS,
            MAX_POLL_ATTEMPTS
        ) { state -> LogBus.info("识字任务状态：" + state) }

        val jsonl = PaddleOcrClient.fetchResultJsonl(jsonUrl)
        val lines = parseJsonl(jsonl)
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        if (lines.isEmpty()) {
            return failure(startedAt, appContext.getString(R.string.ocr_error_empty))
        }
        LogBus.result("识字完成：" + lines.size + " 行，" + elapsed + " ms")
        return OcrResult(lines.joinToString("\n"), lines, elapsed)
    }

    // ------------------------------------------------------------ 结果解析

    /**
     * 解析结果 JSONL：一行一页，每页的 layoutParsingResults[*].markdown.text 就是版面解析文本。
     * 没有 markdown 的行（纯 OCR 模型）退回 prunedResult.rec_texts。
     */
    private fun parseJsonl(jsonl: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in jsonl.lineSequence()) {
            val text = raw.trim()
            if (text.isEmpty()) {
                continue
            }
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val result = root.optJSONObject("result") ?: root
            val pages = result.optJSONArray("layoutParsingResults")
            if (pages == null) {
                collectRecTexts(result, lines)
                continue
            }
            for (index in 0 until pages.length()) {
                val page = pages.optJSONObject(index) ?: continue
                val markdown = page.optJSONObject("markdown")?.optString("text", "").orEmpty()
                if (markdown.isNotEmpty()) {
                    appendMarkdownLines(markdown, lines)
                } else {
                    collectRecTexts(page, lines)
                }
            }
        }
        return lines
    }

    private fun appendMarkdownLines(markdown: String, out: MutableList<String>) {
        for (raw in markdown.lineSequence()) {
            val line = raw.trim()
            // 空行对「按行取第几行」没有意义，直接丢掉
            if (line.isNotEmpty()) {
                out.add(line)
            }
        }
    }

    private fun collectRecTexts(source: JSONObject, out: MutableList<String>) {
        val pruned = source.optJSONObject("prunedResult") ?: source
        val texts = pruned.optJSONArray("rec_texts")
            ?: source.optJSONArray("rec_texts")
            ?: return
        for (index in 0 until texts.length()) {
            val line = texts.optString(index, "").trim()
            if (line.isNotEmpty()) {
                out.add(line)
            }
        }
    }

    // ------------------------------------------------------------ 图像与缓存

    /** 转 PNG。不回收 bitmap：它属于插件，插件自己决定什么时候 recycle。 */
    private fun compressToPng(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled) {
            LogBus.warn("要识别的位图已经被回收了，跳过这次识字。")
            return null
        }
        val buffer = ByteArrayOutputStream(bitmap.byteCount.coerceIn(1024, 4 * 1024 * 1024))
        val ok = runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer) }
            .getOrDefault(false)
        if (!ok) {
            LogBus.warn("截图转 PNG 失败，跳过这次识字。")
            return null
        }
        return buffer.toByteArray()
    }

    /**
     * 像素哈希：在整屏上抽 [HASH_GRID] × [HASH_GRID] 个采样点做 FNV-1a。
     * 逐像素算太慢（1080p 是 200 万个点），抽样足够区分「同一屏」和「换了一屏」。
     */
    private fun frameKey(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("位图尺寸异常")
        }
        val stepX = (width / HASH_GRID).coerceAtLeast(1)
        val stepY = (height / HASH_GRID).coerceAtLeast(1)
        var hash = FNV_OFFSET_BASIS
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                hash = (hash xor bitmap.getPixel(x, y).toLong()) * FNV_PRIME
                x += stepX
            }
            y += stepY
        }
        return width.toString() + "x" + height.toString() + "-" + java.lang.Long.toHexString(hash)
    }

    private fun cacheGet(key: String): OcrResult? = synchronized(cacheLock) { cache[key] }

    private fun cachePut(key: String, value: OcrResult) {
        synchronized(cacheLock) {
            cache[key] = value
            while (cache.size > CACHE_CAPACITY) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    // ------------------------------------------------------------ 错误文案

    private fun failure(startedAt: Long, message: String): OcrResult {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        LogBus.warn("识字失败：" + message)
        return OcrResult("", emptyList(), elapsed, message)
    }

    private fun describeError(t: Throwable): String = when (t) {
        is PaddleOcrClient.OcrJobFailedException -> t.message ?: appContext.getString(R.string.ocr_error_failed)
        is PaddleOcrClient.OcrStatusException -> httpStatusMessage(t.statusCode)
        is PaddleOcrClient.OcrTimeoutException ->
            appContext.getString(R.string.ocr_error_timeout, (POLL_TIMEOUT_MS / 1000).toInt())
        is PaddleOcrClient.OcrHttpException ->
            appContext.getString(R.string.ocr_error_network, t.message ?: t.javaClass.simpleName)
        else -> appContext.getString(R.string.ocr_error_unexpected, t.javaClass.simpleName)
    }

    private fun httpStatusMessage(code: Int): String = when (code) {
        401, 403 -> appContext.getString(R.string.ocr_error_http_auth, code)
        413 -> appContext.getString(R.string.ocr_error_http_too_large)
        422 -> appContext.getString(R.string.ocr_error_http_bad_request)
        429 -> appContext.getString(R.string.ocr_error_http_quota)
        503, 504 -> appContext.getString(R.string.ocr_error_http_busy, code)
        else -> appContext.getString(R.string.ocr_error_http_other, code)
    }

    private companion object {
        const val CACHE_CAPACITY = 20
        const val CACHE_INITIAL_CAPACITY = 16

        /** 像素哈希的采样网格边长（两边各取这么多个点）。 */
        const val HASH_GRID = 48

        /** 轮询间隔：一秒半一次，60 秒内没结果就算超时。 */
        const val POLL_INTERVAL_MS = 1_500L
        const val POLL_TIMEOUT_MS = 60_000L

        /** 上限次数，和超时互为保险（网络抖动时 delay 可能被拖长）。 */
        const val MAX_POLL_ATTEMPTS = 40

        /** FNV-1a 64 位常数：offset basis 与 prime。 */
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
