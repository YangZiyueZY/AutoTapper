package com.example.autotapper.ocr

import android.content.Context
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.R
import java.net.URI
import java.util.Locale

/**
 * 识字服务的设置项：访问令牌、服务地址、开关。
 *
 * 三个 key 都落在 [AutoTapperConfig.prefs]（主程序设置的同一份文件），
 * 插件拿不到密钥 —— 插件只看到 [com.example.autotapper.plugin.OcrApi] 这个门面。
 *
 * 这个对象是给「设置页」用的：读写三个 key，外加一句能直接显示给人看的状态描述。
 * 本轮不做设置页 UI，接口先摆好，后面接上就行。
 */
object OcrSettings {

    const val KEY_TOKEN = "ocr_token"
    const val KEY_ENDPOINT = "ocr_endpoint"
    const val KEY_ENABLED = "ocr_enabled"

    /**
     * PaddleOCR-VL 官方托管异步任务接口。
     * 令牌在 https://aistudio.baidu.com/account/accessToken 取（「AI Studio 访问令牌」）。
     * 协议细节见 [PaddleOcrClient] 的类注释。
     */
    const val DEFAULT_ENDPOINT = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"

    /** 访问令牌。空串表示用户还没填。 */
    fun token(context: Context): String =
        AutoTapperConfig.prefs(context).getString(KEY_TOKEN, "")?.trim().orEmpty()

    /** 服务地址。没填过就用官方默认值，避免用户对着空输入框发愁。 */
    fun endpoint(context: Context): String {
        val saved = AutoTapperConfig.prefs(context).getString(KEY_ENDPOINT, null)?.trim()
        return if (saved.isNullOrEmpty()) DEFAULT_ENDPOINT else saved
    }

    /** 总开关。默认开着：用户填了令牌就是想要用，不该还要求他再点一次开关。 */
    fun enabled(context: Context): Boolean =
        AutoTapperConfig.prefs(context).getBoolean(KEY_ENABLED, true)

    fun saveToken(context: Context, token: String) {
        AutoTapperConfig.prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun saveEndpoint(context: Context, endpoint: String) {
        AutoTapperConfig.prefs(context).edit().putString(KEY_ENDPOINT, endpoint.trim()).apply()
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        AutoTapperConfig.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 设置页一次保存三个字段用。 */
    fun save(context: Context, token: String, endpoint: String, enabled: Boolean) {
        AutoTapperConfig.prefs(context).edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** 能不能用：开关打开 + 令牌非空 + 地址是能发请求的 https 地址。 */
    fun isConfigured(context: Context): Boolean =
        enabled(context) && token(context).isNotEmpty() && isEndpointUsable(endpoint(context))

    fun isEndpointUsable(endpoint: String): Boolean {
        if (endpoint.isBlank()) {
            return false
        }
        return runCatching {
            val uri = URI(endpoint.trim())
            // 令牌是要塞进 Authorization 头的，明文 http 等于把令牌交出去
            uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrEmpty()
        }.getOrDefault(false)
    }

    /** 一句人话的状态，设置页直接显示。 */
    fun statusText(context: Context): String {
        if (!enabled(context)) {
            return context.getString(R.string.ocr_status_disabled)
        }
        if (token(context).isEmpty()) {
            return context.getString(R.string.ocr_status_no_token)
        }
        // 局部变量不叫 endpoint：和同名函数撞在一起读起来太绕
        val serviceUrl = endpoint(context)
        if (!isEndpointUsable(serviceUrl)) {
            return context.getString(R.string.ocr_status_bad_endpoint)
        }
        val host = runCatching { URI(serviceUrl).host }.getOrNull().orEmpty()
        return context.getString(R.string.ocr_status_ready, host)
    }
}
