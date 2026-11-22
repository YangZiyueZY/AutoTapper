package com.example.autotapper.ocr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * PaddleOCR-VL 云端异步任务协议的客户端。只用 HttpURLConnection，不引第三方依赖。
 *
 * ==== 协议（查证于 2026-11，来源：PaddleOCR 官方 SDK 的 api_sdk/go 实现说明，
 * 以及 paddleocr.dev 的 API 参考页；官方文档没有单列 HTTP 参考，以下是 SDK 真正发的东西）====
 *
 * 1) 提交任务   POST  {endpoint}
 *    请求头     Authorization: Bearer <AI Studio 访问令牌>
 *               Content-Type: multipart/form-data; boundary=...
 *    表单       file            图片/PDF 二进制（这里传 PNG）
 *               model           模型名；版面解析默认「PaddleOCR-VL-1.6」
 *               optionalPayload 可选参数的 JSON 串，留空就是默认值，这里传「{}」
 *    响应       {"code":0,"msg":"","data":{"jobId":"..."}}
 *
 * 2) 轮询任务   GET   {endpoint}/{jobId}
 *    请求头     Authorization: Bearer <同一个令牌>
 *    响应       {"code":0,"msg":"","data":{"state":"pending","resultUrl":{"jsonUrl":"..."}}}
 *    state 取值：submitted / pending / running / done / failed
 *               done   → data.resultUrl.jsonUrl 是结果文件的地址
 *               failed → data.errorMsg（或 msg）是人话原因
 *
 * 3) 取结果     GET   {data.resultUrl.jsonUrl}     —— JSONL，一页一行
 *    每行       {"result":{"layoutParsingResults":[{"markdown":{"text":"..."}}, ...]}}
 *
 * 每个响应都套着一层 {code, msg, data} 信封，code == 0 才算成功。
 *
 * ==== 这里是推断、官方没写清楚的部分（已按「可配置 endpoint + 通用异步任务协议」实现）====
 * - 提交成功的 HTTP 状态码：200 / 201 / 202 都当受理；
 * - state 若出现 success / finished 也当完成，failed / error 当失败，
 *   其余（含队列里的各种说法）一律继续等 —— 猜错一个单词就报失败，比多等两秒更糟；
 * - 失败原因字段名可能是 data.errorMsg / data.message / 顶层 msg，三个都试；
 * - 结果行里没有 markdown.text 时，退回 prunedResult.rec_texts 拼行（纯 OCR 模型就是这种形状）。
 * 用户在设置里把 ocr_endpoint 换成自建网关时，需要网关说同一套异步任务协议；
 * paddlex --serve 自带的 /ocr 是同步接口，协议不同，得自己在前面加一层。
 *
 * ==== 关于令牌 ====
 * 结果地址是百度对象存储的预签名 URL，绝不能带 Authorization：
 * 那是把令牌发给另一个域。所以取结果这一步永远匿名请求。
 */
internal object PaddleOcrClient {

    /** 版面解析（带 markdown 的那种）的默认模型。 */
    const val DEFAULT_MODEL = "PaddleOCR-VL-1.6"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val USER_AGENT = "AutoTapper-OCR"

    /** 网络层或协议层的一个具体错误，上层翻译成人话。 */
    class OcrHttpException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 服务端返回了非 2xx，带状态码，上层据此给不同提示。 */
    class OcrStatusException(val statusCode: Int, val body: String) :
        Exception("识字服务返回 HTTP " + statusCode)

    /** 轮询超时。 */
    class OcrTimeoutException(message: String) : Exception(message)

    /** 任务本身失败了（服务端 state = failed）。message 已经是能直接给人看的一句中文。 */
    class OcrJobFailedException(message: String) : Exception(message)

    /** 一次状态查询的结果。jsonUrl 只有 state 为完成时才有值。 */
    data class JobState(val state: String, val jsonUrl: String?, val message: String?)

    // ------------------------------------------------------------------ 提交

    fun submit(endpoint: String, token: String, png: ByteArray, model: String = DEFAULT_MODEL): String {
        val boundary = "----AutoTapper" + System.currentTimeMillis()
        val body = buildMultipart(boundary, png, model)
        val conn = open("POST", endpoint, token)
        try {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.doOutput = true
            try {
                conn.outputStream.use { stream ->
                    stream.write(body)
                    stream.flush()
                }
            } catch (t: Throwable) {
                throw OcrHttpException("上传截图失败：" + (t.message ?: t.javaClass.simpleName), t)
            }
            val response = readResponse(conn)
            if (response.first !in 200..299) {
                throw OcrStatusException(response.first, response.second)
            }
            val data = parseEnvelope(response.second)
            val jobId = data.optString("jobId", "")
            if (jobId.isEmpty()) {
                throw OcrHttpException("识字服务没有返回任务号 jobId。")
            }
            return jobId
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 轮询

    /**
     * 轮询到出结果或超时，返回结果文件地址。
     *
     * 用 delay 而不是 Thread.sleep：插件的 execute 是挂起函数，等待期间不该占着线程。
     * 每轮都检查协程是否已被取消（用户点了停止 / 引擎停了），被取消就立刻抛出去。
     */
    suspend fun awaitResult(
        endpoint: String,
        token: String,
        jobId: String,
        timeoutMs: Long,
        intervalMs: Long,
        maxAttempts: Int,
        onState: (String) -> Unit
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            attempt++
            val job = query(endpoint, token, jobId)
            when (job.state.lowercase(Locale.ROOT)) {
                "done", "success", "finished", "succeeded" -> {
                    val url = job.jsonUrl
                    if (url.isNullOrEmpty()) {
                        throw OcrHttpException("识字任务完成了，但服务没给结果地址。")
                    }
                    return url
                }

                "failed", "error" -> {
                    throw OcrJobFailedException(
                        "识字任务失败：" + (job.message ?: "服务没有说明原因")
                    )
                }

                else -> onState(job.state)
            }
            if (attempt >= maxAttempts || System.currentTimeMillis() > deadline) {
                throw OcrTimeoutException("识字任务超时（已轮询 " + attempt + " 次）")
            }
            delay(intervalMs)
        }
    }

    fun query(endpoint: String, token: String, jobId: String): JobState {
        val url = endpoint.trimEnd('/') + "/" + jobId
        val conn = open("GET", url, token)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val response = readResponse(conn)
            if (response.first !in 200..299) {
                throw OcrStatusException(response.first, response.second)
            }
            val data = parseEnvelope(response.second)
            val state = data.optString("state", "")
            if (state.isEmpty()) {
                throw OcrHttpException("识字服务没有返回任务状态 state。")
            }
            val resultUrl = data.optJSONObject("resultUrl")
            val jsonUrl = resultUrl?.optString("jsonUrl", "")?.takeIf { it.isNotEmpty() }
            val message = firstNonEmpty(
                data.optString("errorMsg", ""),
                data.optString("message", ""),
                data.optString("msg", "")
            )
            return JobState(state, jsonUrl, message)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 取结果

    /** 取结果 JSONL 原文。这里一定不带 Authorization（见类注释）。 */
    fun fetchResultJsonl(jsonUrl: String): String {
        val conn = open("GET", jsonUrl, null)
        try {
            conn.setRequestProperty(
                "Accept",
                "application/x-ndjson, application/json, text/plain, */*"
            )
            val response = readResponse(conn)
            if (response.first !in 200..299) {
                throw OcrStatusException(response.first, response.second)
            }
            return response.second
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun open(method: String, url: String, token: String?): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw OcrHttpException("识字服务地址无法解析：" + url, t)
        }
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (!token.isNullOrEmpty()) {
            // 只有提交与轮询会带令牌；取结果传的是 null，绝不把令牌发给对象存储的域
            connection.setRequestProperty("Authorization", "Bearer " + token)
        }
        return connection
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = try {
            conn.responseCode
        } catch (t: Throwable) {
            throw OcrHttpException("连不上识字服务：" + (t.message ?: t.javaClass.simpleName), t)
        }
        val stream = runCatching {
            if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        }.getOrNull()
        val text = runCatching {
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        return code to text
    }

    /** 拆 {code, msg, data} 信封，返回 data。没有 code 字段的网关当作成功。 */
    private fun parseEnvelope(body: String): JSONObject {
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            throw OcrHttpException("识字服务的响应不是 JSON。", t)
        }
        if (root.has("code") && root.optInt("code", 0) != 0) {
            val message = firstNonEmpty(
                root.optString("msg", ""),
                root.optString("message", "")
            )
            throw OcrHttpException(
                "识字服务拒绝了请求（code=" + root.optInt("code", 0) + "）：" + message
            )
        }
        return root.optJSONObject("data") ?: JSONObject()
    }

    private fun buildMultipart(boundary: String, png: ByteArray, model: String): ByteArray {
        val out = ByteArrayOutputStream(png.size + 1024)
        writeText(out, "--" + boundary + CRLF)
        writeText(out, "Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF)
        writeText(out, model + CRLF)

        writeText(out, "--" + boundary + CRLF)
        writeText(out, "Content-Disposition: form-data; name=\"optionalPayload\"" + CRLF + CRLF)
        writeText(out, "{}" + CRLF)

        writeText(out, "--" + boundary + CRLF)
        // filename 必须带扩展名：服务端靠它判断这是图片而不是 PDF
        writeText(
            out,
            "Content-Disposition: form-data; name=\"file\"; filename=\"screen.png\"" + CRLF
        )
        writeText(out, "Content-Type: image/png" + CRLF + CRLF)
        out.write(png)
        writeText(out, CRLF + "--" + boundary + "--" + CRLF)
        return out.toByteArray()
    }

    private fun writeText(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            if (value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    /** 结果地址与接口是否同一个主机。留给以后做「只信任白名单域」的校验。 */
    fun sameHost(endpoint: String, other: String): Boolean {
        val first = runCatching { URI(endpoint).host?.lowercase(Locale.ROOT) }.getOrNull()
        val second = runCatching { URI(other).host?.lowercase(Locale.ROOT) }.getOrNull()
        return first != null && first == second
    }

    private const val CRLF = "\r\n"
}
