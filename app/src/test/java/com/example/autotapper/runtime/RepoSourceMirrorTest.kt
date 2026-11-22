package com.example.autotapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库镜像：地址生成 + 自定义模板校验。
 *
 * 为什么值得测：镜像地址只差一个字符就会 404，而界面上只会显示"同步失败"。
 * 这里把三种镜像的 rawBase / indexUrl 逐字钉死，自定义模板的每条校验规则也各测一次。
 */
class RepoSourceMirrorTest {

    private val owner = "YangZiyueZY"
    private val repo = "AutoTapperPlugin"
    private val branch = "main"

    private val customTemplate = "https://mirror.example.com/gh/{owner}/{repo}/{branch}"

    // -------------------------------------------------------------------
    // 三种镜像生成的地址
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun directUrls() {
        val source = RepoSource(owner, repo, branch, Mirror.Direct)

        assertEquals("https://raw.githubusercontent.com/YangZiyueZY/AutoTapperPlugin/main", source.rawBase)
        assertEquals(
            "https://raw.githubusercontent.com/YangZiyueZY/AutoTapperPlugin/main/index.json",
            source.indexUrl
        )
        assertEquals("官方直连", source.mirrorLabel)
        assertTrue(source.isMirrorUsable())
    }

    @Test(timeout = 5_000L)
    fun jsdelivrUrls() {
        val source = RepoSource(owner, repo, branch, Mirror.JsDelivr)

        assertEquals("https://cdn.jsdelivr.net/gh/YangZiyueZY/AutoTapperPlugin@main", source.rawBase)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/YangZiyueZY/AutoTapperPlugin@main/index.json",
            source.indexUrl
        )
        assertEquals("jsDelivr 镜像", source.mirrorLabel)
        assertTrue(source.isMirrorUsable())
        // 插件 jar 的相对路径也要跟着镜像走
        assertEquals(
            "https://cdn.jsdelivr.net/gh/YangZiyueZY/AutoTapperPlugin@main/dist/a.jar",
            source.rawUrl("dist/a.jar")
        )
    }

    @Test(timeout = 5_000L)
    fun customUrls() {
        val source = RepoSource(owner, repo, branch, Mirror.Custom(customTemplate))

        assertEquals("https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main", source.rawBase)
        assertEquals(
            "https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main/index.json",
            source.indexUrl
        )
        assertEquals("自定义", source.mirrorLabel)
        assertTrue(source.isMirrorUsable())
    }

    @Test(timeout = 5_000L)
    fun customTemplateTrailingSlashIsNormalised() {
        // 用户粘贴时几乎都会带尾斜杠，不能因此拼出 "//index.json"
        val source = RepoSource(owner, repo, branch, Mirror.Custom(customTemplate + "/"))

        assertEquals("https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main", source.rawBase)
        assertEquals(
            "https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main/index.json",
            source.indexUrl
        )
    }

    @Test(timeout = 5_000L)
    fun baseForBuildsRootUrl() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/o/r@dev",
            Mirror.JsDelivr.baseFor("o", "r", "dev")
        )
        // 不带结尾斜杠：rawUrl 自己补 "/"
        assertFalse(Mirror.JsDelivr.baseFor("o", "r", "dev").endsWith("/"))
        assertEquals(
            "https://mirror.example.com/x/o/r/dev",
            Mirror.Custom("https://mirror.example.com/x/{owner}/{repo}/{branch}").baseFor("o", "r", "dev")
        )
    }

    @Test(timeout = 5_000L)
    fun previewMirrorSubstitutesPlaceholders() {
        assertEquals(
            "https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main",
            RepoSource.previewMirror(customTemplate, owner, repo, branch)
        )
        // 非法模板（缺占位符）给不出预览，界面据此禁用"保存"
        assertNull(RepoSource.previewMirror("https://mirror.example.com/gh", owner, repo, branch))
    }

    // -------------------------------------------------------------------
    // 自定义模板校验：invalidReason() 为 null 才算可用
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun validCustomTemplatePasses() {
        assertNull(Mirror.Custom(customTemplate).invalidReason())
        assertNotNull(Mirror.Custom(customTemplate).host)
        assertEquals("mirror.example.com", Mirror.Custom(customTemplate).host)
    }

    @Test(timeout = 5_000L)
    fun customTemplateMissingPlaceholderIsInvalid() {
        // 每个占位符都必需：缺一个就会把所有人拼到同一个地址上
        assertNotNull(Mirror.Custom("https://mirror.example.com/gh/{repo}/{branch}").invalidReason())
        assertNotNull(Mirror.Custom("https://mirror.example.com/gh/{owner}/{branch}").invalidReason())
        assertNotNull(Mirror.Custom("https://mirror.example.com/gh/{owner}/{repo}").invalidReason())
        assertNotNull(Mirror.Custom("https://mirror.example.com/gh").invalidReason())
        assertNotNull("空模板不可用", Mirror.Custom("").invalidReason())
        assertNotNull("只有空白不可用", Mirror.Custom("   ").invalidReason())
    }

    @Test(timeout = 5_000L)
    fun customTemplateSchemeAndSpacesAreChecked() {
        // 只接受 https：明文 http 会被 PluginHttp 白名单拒掉，不如提前说清楚
        assertNotNull(Mirror.Custom("http://mirror.example.com/{owner}/{repo}/{branch}").invalidReason())
        assertNotNull(Mirror.Custom("mirror.example.com/{owner}/{repo}/{branch}").invalidReason())
        assertNotNull(
            Mirror.Custom("https://mirror.example.com/{owner}/{repo}/ {branch}").invalidReason()
        )
    }

    @Test(timeout = 5_000L)
    fun customTemplateRejectsUnknownBraces() {
        // 除三个占位符外不该出现花括号：用户想写 {tag} 这种，宿主替换不了，只会得到一个坏地址
        assertNotNull(
            Mirror.Custom("https://mirror.example.com/{owner}/{repo}/{branch}/{tag}").invalidReason()
        )
        assertNotNull(
            Mirror.Custom("https://mirror.example.com/{owner}/{repo}/{branch}?x={y}").invalidReason()
        )
    }

    @Test(timeout = 5_000L)
    fun invalidCustomMirrorMakesSourceUnusable() {
        val source = RepoSource(owner, repo, branch, Mirror.Custom("https://mirror.example.com/gh"))
        assertFalse(source.isMirrorUsable())
    }

    // -------------------------------------------------------------------
    // 序列化与缓存键
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun mirrorEncodeDecodeRoundTrip() {
        assertEquals("direct", Mirror.Direct.encode())
        assertEquals("jsdelivr", Mirror.JsDelivr.encode())
        assertEquals("custom:" + customTemplate, Mirror.Custom(customTemplate).encode())

        assertEquals(Mirror.Direct, Mirror.decode("direct"))
        assertEquals(Mirror.JsDelivr, Mirror.decode("jsdelivr"))
        assertEquals(Mirror.Custom(customTemplate), Mirror.decode("custom:" + customTemplate))
        assertEquals("大小写不该影响读档", Mirror.JsDelivr, Mirror.decode("JsDelivr"))
        // 空串/认不出来的值返回 null，由调用方回退到直连（这里不替调用方决定）
        assertNull(Mirror.decode(""))
        assertNull(Mirror.decode("不认识的镜像"))
    }

    @Test(timeout = 5_000L)
    fun cacheKeysSeparateMirrors() {
        val direct = RepoSource(owner, repo, branch, Mirror.Direct)
        val jsdelivr = RepoSource(owner, repo, branch, Mirror.JsDelivr)

        // 仓库键不含镜像（仓库本身还是同一个仓库）
        assertEquals("YangZiyueZY_AutoTapperPlugin_main", direct.cacheKey)
        assertEquals(direct.cacheKey, jsdelivr.cacheKey)

        // 索引缓存 / ETag 必须按镜像分开：同一个仓库走两个镜像的内容可能不同版本
        assertTrue(direct.cacheKeyWithMirror.startsWith(direct.cacheKey + "_"))
        assertTrue(jsdelivr.cacheKeyWithMirror.startsWith(jsdelivr.cacheKey + "_"))
        assertTrue(
            "不同镜像的缓存键必须不同",
            direct.cacheKeyWithMirror != jsdelivr.cacheKeyWithMirror
        )
        assertEquals(direct.cacheKey + "_direct", direct.cacheKeyWithMirror)
        assertEquals(direct.cacheKey + "_jsdelivr", jsdelivr.cacheKeyWithMirror)
        assertTrue(Mirror.Custom(customTemplate).cacheTag.startsWith("custom_"))
    }

    @Test(timeout = 5_000L)
    fun withMirrorKeepsRepoIdentity() {
        val source = RepoSource.DEFAULT
        val switched = source.withMirror(Mirror.JsDelivr)

        assertEquals(source.owner, switched.owner)
        assertEquals(source.repo, switched.repo)
        assertEquals(source.branch, switched.branch)
        assertEquals(Mirror.JsDelivr, switched.mirror)
        // 原对象不变
        assertEquals(Mirror.Direct, source.mirror)
        // 解析出来的地址永远先按直连，镜像由设置决定
        assertEquals(Mirror.Direct, RepoSource.parse("YangZiyueZY/AutoTapperPlugin")!!.mirror)
    }

    // -------------------------------------------------------------------
    // 白名单：镜像域名可用，明文/其它域名不可用
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun mirrorHostsAreAllowedByHttpWhitelist() {
        assertTrue(PluginHttp.isAllowedUrl("https://cdn.jsdelivr.net/gh/o/r@main/index.json"))
        assertTrue(PluginHttp.isAllowedUrl("https://fastly.jsdelivr.net/gh/o/r@main/index.json"))
        assertTrue(PluginHttp.isAllowedUrl("https://gcore.jsdelivr.net/gh/o/r@main/index.json"))
        assertTrue(PluginHttp.isAllowedUrl("https://testingcf.jsdelivr.net/gh/o/r@main/index.json"))
        assertTrue(
            PluginHttp.isAllowedUrl("https://raw.githubusercontent.com/YangZiyueZY/AutoTapperPlugin/main/index.json")
        )
        // 明文 http 一律拒绝（镜像也一样）
        assertFalse(PluginHttp.isAllowedUrl("http://cdn.jsdelivr.net/gh/o/r@main/index.json"))
    }

    @Test(timeout = 5_000L)
    fun customMirrorHostNeedsExplicitRegistration() {
        val url = "https://mirror.example.com/gh/YangZiyueZY/AutoTapperPlugin/main/index.json"
        try {
            assertFalse("没登记过的自定义域名不该被放行", PluginHttp.isAllowedUrl(url))
            assertTrue(PluginHttp.allowHost("mirror.example.com"))
            assertTrue("用户确认后登记，之后就放行", PluginHttp.isAllowedUrl(url))
        } finally {
            PluginHttp.resetAllowedHosts()
        }
        assertFalse("撤销后必须不再放行，避免登记长期残留", PluginHttp.isAllowedUrl(url))
    }
}
