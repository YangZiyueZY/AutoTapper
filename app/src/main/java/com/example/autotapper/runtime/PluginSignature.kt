package com.example.autotapper.runtime

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/**
 * 插件包的完整性校验：SHA-256 摘要 + RSA 分离签名。
 *
 * 公钥现在是空字符串 —— 表示"宿主还没配置签名密钥"。这种情况下：
 * - SHA-256 校验照常执行（能防下载损坏、防镜像篡改）；
 * - 分离签名视为"未配置"，安装结果里的 verified 记为 false；
 * - 界面应把"未校验签名"如实标出来，不要假装安全。
 *
 * ---------------------------------------------------------------------------
 * 如何替换成真公钥（三步）
 * ---------------------------------------------------------------------------
 * 1. 在离线机器上生成密钥对（私钥只留在发版机器上，绝不进任何仓库）：
 *      openssl genrsa -out plugin-key.pem 2048
 *      openssl rsa -in plugin-key.pem -pubout -out plugin-key.pub.pem
 *
 * 2. 用私钥给每个插件 jar 出分离签名，并转成 BASE64 文本（.sig 文件内容）：
 *      openssl dgst -sha256 -sign plugin-key.pem -out plugin.jar.sig plugin.jar
 *      base64 -w0 plugin.jar.sig > plugin.jar.sig.b64      # macOS: base64 -i plugin.jar.sig -o plugin.jar.sig.b64
 *    把 plugin.jar.sig.b64 的内容作为 sigUrl 指向的文件内容发布。
 *
 * 3. 把 plugin-key.pub.pem 的正文（去掉 -----BEGIN/END PUBLIC KEY----- 两行和所有换行）
 *    粘到下面 EMBEDDED_PUBLIC_KEY_PEM 里。也可以粘整段 PEM（含 BEGIN/END 行），
 *    解析时会自动剥掉头尾，所以两种写法都行。粘贴后把字符串保持 const，不要从网络读。
 * ---------------------------------------------------------------------------
 */
object PluginSignature {

    /**
     * 内嵌的 RSA 公钥（PEM 或纯 BASE64 正文皆可）。
     * 空字符串 = 未配置，等价于"只做 SHA-256 校验"。
     */
    private const val EMBEDDED_PUBLIC_KEY_PEM = ""

    /** 签名校验的三种结果。 */
    enum class Status {
        /** 签名有效。 */
        VALID,

        /** 签名无效：签名与文件不匹配、公钥损坏、内容不是合法签名。 */
        INVALID,

        /** 宿主没有配置公钥，无法校验（不是失败，但要如实告诉用户）。 */
        NOT_CONFIGURED;

        val isVerified: Boolean get() = this == VALID

        val label: String
            get() = when (this) {
                VALID -> "签名有效"
                INVALID -> "签名无效"
                NOT_CONFIGURED -> "宿主未配置签名公钥"
            }
    }

    /** 宿主是否配置了签名公钥。 */
    fun isConfigured(): Boolean = publicKeyPem().isNotEmpty()

    /** 当前生效的公钥文本（已去空白）。 */
    fun publicKeyPem(): String = EMBEDDED_PUBLIC_KEY_PEM.trim()

    // -----------------------------------------------------------------------
    // SHA-256
    // -----------------------------------------------------------------------

    /** 计算文件 SHA-256，返回 64 位小写十六进制；读文件失败返回 null。 */
    fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            toHex(digest.digest())
        } catch (_: Throwable) {
            null
        }
    }

    /** 文件摘要是否等于期望值（大小写、空白都容错）。 */
    fun verifyJarSha256(file: File, expectedHex: String): Boolean {
        val expected = expectedHex.trim().lowercase(Locale.ROOT)
        if (expected.isEmpty()) return false
        val actual = sha256Hex(file) ?: return false
        return actual == expected
    }

    // -----------------------------------------------------------------------
    // 分离签名
    // -----------------------------------------------------------------------

    /**
     * 校验 jar 的 SHA256withRSA 分离签名。
     *
     * @param jarFile   待校验的插件包
     * @param sigBase64 .sig 文件内容：BASE64（可带 PEM 头尾或换行）或十六进制
     * @param publicKeyPem PEM 或纯 BASE64 公钥；传空串表示未配置
     */
    fun verifyDetachedSignature(
        jarFile: File,
        sigBase64: String,
        publicKeyPem: String = publicKeyPem()
    ): Status {
        val pem = publicKeyPem.trim()
        if (pem.isEmpty()) return Status.NOT_CONFIGURED
        if (!jarFile.isFile) return Status.INVALID

        val key = parsePublicKey(pem) ?: return Status.INVALID
        val signatureBytes = decodeSignature(sigBase64) ?: return Status.INVALID

        return try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            jarFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    verifier.update(buffer, 0, read)
                }
            }
            if (verifier.verify(signatureBytes)) Status.VALID else Status.INVALID
        } catch (_: Throwable) {
            Status.INVALID
        }
    }

    /** 布尔便捷版：只有 VALID 才算通过（未配置视为不通过）。 */
    fun isSignatureValid(jarFile: File, sigBase64: String, publicKeyPem: String = publicKeyPem()): Boolean =
        verifyDetachedSignature(jarFile, sigBase64, publicKeyPem) == Status.VALID

    /** 解析 PEM / 纯 BASE64 的 RSA 公钥。失败返回 null。 */
    private fun parsePublicKey(pem: String): PublicKey? {
        val base64Body = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        if (base64Body.isEmpty()) return null
        return try {
            val encoded = Base64.decode(base64Body, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(encoded)
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 解析 .sig 内容。
     * 先看是不是纯十六进制（这类内容 base64 也能解码成功，所以必须先判），否则按 BASE64。
     */
    private fun decodeSignature(text: String): ByteArray? {
        val body = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        if (body.isEmpty()) return null
        val isHex = body.length % 2 == 0 && body.all { it in "0123456789abcdefABCDEF" }
        return try {
            if (isHex) fromHex(body) else Base64.decode(body, Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // 十六进制工具
    // -----------------------------------------------------------------------

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private fun fromHex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
            i++
        }
        return out
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
