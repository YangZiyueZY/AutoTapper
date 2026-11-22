package com.example.autotapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库地址的解析与 raw 地址拼装。
 *
 * 为什么值得测：用户手输/粘贴的仓库地址千奇百怪，解析歪一个字段就会去请求
 * 一个不存在的 raw 地址，界面上只会显示"仓库同步失败"，查不出原因。
 */
class RepoSourceTest {

    // -------------------------------------------------------------------
    // parse
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun parseOwnerRepo() {
        val source = RepoSource.parse("YangZiyueZY/AutoTapperPlugin")

        assertNotNull(source)
        assertEquals("YangZiyueZY", source!!.owner)
        assertEquals("AutoTapperPlugin", source.repo)
        // 没写分支就是主干，不能凭空造一个空分支
        assertEquals("main", source.branch)
    }

    @Test(timeout = 5_000L)
    fun parseOwnerRepoWithBranch() {
        val source = RepoSource.parse("owner/repo@dev")

        assertEquals("owner", source!!.owner)
        assertEquals("repo", source.repo)
        assertEquals("dev", source.branch)

        // "@" 后留空：退回主干，而不是空分支
        assertEquals("main", RepoSource.parse("owner/repo@")!!.branch)
    }

    @Test(timeout = 5_000L)
    fun parseGithubTreeUrl() {
        val source = RepoSource.parse("https://github.com/owner/repo/tree/dev")

        assertEquals("owner", source!!.owner)
        assertEquals("repo", source.repo)
        assertEquals("dev", source.branch)

        // 分支名本身带斜杠（feature/x）也要完整取到
        assertEquals(
            "feature/x",
            RepoSource.parse("https://github.com/owner/repo/tree/feature/x")!!.branch
        )
    }

    @Test(timeout = 5_000L)
    fun parseGithubUrlWithoutTree() {
        assertEquals("main", RepoSource.parse("https://github.com/owner/repo")!!.branch)
        // 不带协议、末尾带斜杠、粘 URL 时顺手带上的 .git 都要能认
        assertEquals("main", RepoSource.parse("github.com/owner/repo/")!!.branch)
        assertEquals("repo", RepoSource.parse("  https://github.com/owner/repo.git  ")!!.repo)
    }

    @Test(timeout = 5_000L)
    fun parseRejectsGarbage() {
        // 解析不出来一律 null，让界面上提示"地址格式不对"，而不是拼出一个错地址去请求
        assertNull(RepoSource.parse(""))
        assertNull(RepoSource.parse("   "))
        assertNull(RepoSource.parse("justowner"))
        assertNull(RepoSource.parse("owner/repo/extra"))
        assertNull(RepoSource.parse("owner/.."))
        assertNull(RepoSource.parse("../evil"))
        // 别的托管站不接受：宿主只认 GitHub 的 raw
        assertNull(RepoSource.parse("https://gitlab.com/owner/repo"))
    }

    // -------------------------------------------------------------------
    // 直连（GitHub raw）地址
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun directRawUrls() {
        val source = RepoSource.DEFAULT

        assertEquals(
            "https://raw.githubusercontent.com/YangZiyueZY/AutoTapperPlugin/main",
            source.rawBase
        )
        assertEquals(
            "https://raw.githubusercontent.com/YangZiyueZY/AutoTapperPlugin/main/index.json",
            source.indexUrl
        )
        // 仓库内相对路径 → raw 地址；三种写法都要归一成同一个地址
        assertEquals(source.rawBase + "/dist/tap-styles-1.0.0.jar", source.rawUrl("dist/tap-styles-1.0.0.jar"))
        assertEquals(source.rawBase + "/dist/tap-styles-1.0.0.jar", source.rawUrl("/dist/tap-styles-1.0.0.jar"))
        assertEquals(source.rawBase + "/dist/tap-styles-1.0.0.jar", source.rawUrl("./dist/tap-styles-1.0.0.jar"))
    }

    @Test(timeout = 5_000L)
    fun resolveKeepsAbsoluteUrls() {
        val source = RepoSource("owner", "repo", "dev")

        assertEquals(source.rawUrl("dist/a.jar"), source.resolve("dist/a.jar"))
        // 清单里已经写死绝对地址时不要二次拼接
        assertEquals("https://example.com/a.jar", source.resolve("https://example.com/a.jar"))
        assertEquals("http://example.com/a.jar", source.resolve("http://example.com/a.jar"))
        assertEquals("https://example.com/a.jar", source.resolve("  https://example.com/a.jar  "))
    }

    @Test(timeout = 5_000L)
    fun cacheKeyAndLabelAreStable() {
        assertEquals("YangZiyueZY_AutoTapperPlugin_main", RepoSource.DEFAULT.cacheKey)
        // 分支里的斜杠与点会毁掉文件名，必须换成下划线
        assertEquals(
            "owner_repo_feature_x",
            RepoSource("owner", "repo", "feature/x").cacheKey
        )
        assertEquals("YangZiyueZY/AutoTapperPlugin@main", RepoSource.DEFAULT.label)
        assertEquals("YangZiyueZY/AutoTapperPlugin@main", RepoSource.DEFAULT.toString())
    }

    @Test(timeout = 5_000L)
    fun validityChecks() {
        assertTrue(RepoSource("owner", "repo", "main").isValid())
        // 分支允许斜杠（feature/x）
        assertTrue(RepoSource("owner", "repo", "feature/x").isValid())

        assertFalse(RepoSource("", "repo").isValid())
        assertFalse(RepoSource("owner", "").isValid())
        assertFalse(RepoSource("owner", "repo", "").isValid())
        // 路径穿越与协议字符：拼进 URL 前必须挡住
        assertFalse(RepoSource("..", "repo").isValid())
        assertFalse(RepoSource("owner", "repo", "x..y").isValid())
        assertFalse(RepoSource("owner", "re po").isValid())
        assertFalse(RepoSource("owner", "repo", "../etc").isValid())
    }
}
