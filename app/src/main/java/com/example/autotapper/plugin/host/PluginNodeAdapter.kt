package com.example.autotapper.plugin.host

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.NodeApi

/**
 * 用无障碍节点树实现 [NodeApi]。
 *
 * 为什么这里只拿一个"取当前窗口根节点"的 provider，而不持有 AccessibilityService：
 * 服务会被系统反复重建、也会被用户随手关掉，而 CapabilityHolder 是进程级的交接点；
 * 持有服务实例会让适配器悄悄指向一个已经死掉的对象。provider 每次现取，
 * 服务断开时宿主把交接点换成 [UnavailableNodeApi]，插件那边立刻表现为 isReady()=false。
 *
 * 线程：AccessibilityService.getRootInActiveWindow() 与整棵树的遍历都只能在服务所在的
 * 主线程做（无障碍节点是跨进程的 binder 对象，跨线程读不安全），
 * 而插件步骤本来就在引擎的主线程调度器里执行，所以这里不加锁、不切线程。
 * 节点树一般是几百个节点，扫一遍是毫秒级；为了防 WebView / 超长列表把树撑到上万节点，
 * 遍历有深度与节点数双上限（见 [MAX_DEPTH] / [MAX_NODES]），到上限就按"没找到"返回，
 * 宁可这一步行失败，也不把主线程卡住。
 */
class PluginNodeAdapter(
    private val rootProvider: () -> AccessibilityNodeInfo?
) : NodeApi {

    override fun isReady(): Boolean {
        val root = acquireRoot() ?: return false
        recycleQuietly(root)
        return true
    }

    override fun findByViewId(viewId: String, packageName: String?): TapCoord? {
        val wanted = viewId.trim()
        if (wanted.isEmpty()) return null
        return search { node ->
            matchesPackage(node, packageName) && wanted == safeViewId(node)
        }
    }

    override fun findByText(text: String, exact: Boolean): TapCoord? {
        val wanted = text.trim()
        if (wanted.isEmpty()) return null
        return search { node ->
            val label = safeText(node).trim()
            label.isNotEmpty() && (if (exact) label == wanted else label.contains(wanted))
        }
    }

    override fun findByDescription(description: String): TapCoord? {
        val wanted = description.trim()
        if (wanted.isEmpty()) return null
        return search { node ->
            val label = safeDescription(node).trim()
            label.isNotEmpty() && label.contains(wanted)
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /**
     * 广度优先找第一个满足 [match] 且有实际大小的节点，返回它 boundsInScreen 的中心点。
     *
     * 用广度优先而不是深度优先：同样的 viewId 出现在列表里时，越浅的节点越可能是
     * 我们要点的那个"整行"，而且深度优先在长列表里更容易一脑袋扎进第一行的深处。
     */
    private fun search(match: (AccessibilityNodeInfo) -> Boolean): TapCoord? {
        val root = acquireRoot() ?: return null

        // 遍历中每个节点（根 + 每次 getChild 的返回值）都由我们持有，必须逐个回收。
        // API 33 起 recycle() 已经是空操作，但在 minSdk 26 的老系统上是真回收，
        // 漏掉就会把系统侧的节点池耗干（表现为遍历越来越慢，最后 getChild 全返回 null）。
        val owned = ArrayList<AccessibilityNodeInfo>()
        owned.add(root)

        try {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(Pair(root, 0))
            var visited = 0

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                visited++
                if (visited > MAX_NODES) return null

                val node = current.first
                val depth = current.second

                val hit = try {
                    match(node)
                } catch (t: Throwable) {
                    // 单个节点的属性读取失败（跨进程对象偶发失效）不能中断整次查找
                    false
                }
                if (hit) {
                    val point = centerOf(node)
                    if (point != null) return point
                }

                if (depth >= MAX_DEPTH) continue
                val childCount = try {
                    node.childCount
                } catch (t: Throwable) {
                    0
                }
                for (index in 0 until childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    owned.add(child)
                    queue.add(Pair(child, depth + 1))
                }
            }
            return null
        } finally {
            for (node in owned) recycleQuietly(node)
        }
    }

    private fun acquireRoot(): AccessibilityNodeInfo? =
        try {
            rootProvider()
        } catch (t: Throwable) {
            null
        }

    /** 取节点在屏幕上的中心点；空尺寸的纯容器节点返回 null，让遍历继续找下一个候选。 */
    private fun centerOf(node: AccessibilityNodeInfo): TapCoord? {
        val bounds = Rect()
        val read = try {
            node.getBoundsInScreen(bounds)
            true
        } catch (t: Throwable) {
            false
        }
        if (!read) return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return TapCoord(bounds.centerX(), bounds.centerY())
    }

    private fun matchesPackage(node: AccessibilityNodeInfo, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val actual = try {
            node.packageName?.toString()
        } catch (t: Throwable) {
            null
        }
        return actual == packageName
    }

    private fun safeViewId(node: AccessibilityNodeInfo): String? =
        try {
            node.viewIdResourceName
        } catch (t: Throwable) {
            null
        }

    private fun safeText(node: AccessibilityNodeInfo): String =
        try {
            node.text?.toString() ?: ""
        } catch (t: Throwable) {
            ""
        }

    private fun safeDescription(node: AccessibilityNodeInfo): String =
        try {
            node.contentDescription?.toString() ?: ""
        } catch (t: Throwable) {
            ""
        }

    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (t: Throwable) {
            // 已经被系统回收过再调会抛异常，这里无所谓：回收失败不影响本次查找结果
        }
    }

    private companion object {
        /** 遍历深度上限：比这更深的层基本是 WebView 内部，继续挖只会更慢。 */
        const val MAX_DEPTH = 64

        /** 单次查找最多看这么多节点，超了就按"没找到"处理，绝不拖住主线程。 */
        const val MAX_NODES = 4_000
    }
}

/**
 * 无障碍服务没连上时的空实现：什么都找不到，[isReady] 一直是 false。
 *
 * 为什么必须返回 null 而不是"猜一个坐标"：插件拿到 null 会诚实地报
 * "无障碍服务没有连上"，用户知道该去设置里开权限；猜一个坐标只会点错地方，
 * 而且用户完全不知道发生了什么。
 */
class UnavailableNodeApi : NodeApi {

    override fun findByViewId(viewId: String, packageName: String?): TapCoord? = null

    override fun findByText(text: String, exact: Boolean): TapCoord? = null

    override fun findByDescription(description: String): TapCoord? = null

    override fun isReady(): Boolean = false
}
