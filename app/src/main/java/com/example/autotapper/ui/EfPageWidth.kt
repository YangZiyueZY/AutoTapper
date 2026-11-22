package com.example.autotapper.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.autotapper.Ef

/**
 * 二级页面（规则、插件、日志、设置）的内容宽度收口。
 *
 * 为什么要它：主界面有响应式两栏，但规则/插件这类"表单 + 短列表"页面如果铺满
 * 1600px 的平板，一行只放一个规则名会显得空。这里把内容收窄到 [MAX_WIDTH_DP] 并居中，
 * 手机上（可用宽度本来就不足上限）完全无感。
 *
 * 做法：容器本身是 match_parent 的，改成固定像素宽度 + 在父容器里居中。
 * 只在 FrameLayout 父级下生效（这些页面的内容区都是 FrameLayout）。
 */
object EfPageWidth {

    /** 内容宽度上限（dp）。720dp 大致等于平板横屏时一屏能舒服读两栏的表单宽度。 */
    private const val MAX_WIDTH_DP = 720

    /**
     * 把 [container] 以及同一父容器里所有需要跟它对齐的兄弟 View 一起收窄。
     * 传进来多个 View 时会统一宽度（比如规则页的「列表屏 / 步骤屏」两个兄弟）。
     */
    fun cap(vararg containers: View?) {
        containers.forEach { view ->
            if (view == null) return@forEach
            val context = view.context ?: return@forEach
            val dm = context.resources.displayMetrics
            val maxPx = Ef.dp(context, MAX_WIDTH_DP.toFloat())
            if (dm.widthPixels <= maxPx) return@forEach
            applyWidth(view, maxPx)
        }
    }

    private fun applyWidth(view: View, widthPx: Int) {
        when (val params = view.layoutParams) {
            is FrameLayout.LayoutParams -> {
                params.width = widthPx
                if (params.gravity == -1) params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }

            is LinearLayout.LayoutParams -> {
                params.width = widthPx
                if (params.gravity == -1) params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }

            is ViewGroup.MarginLayoutParams -> {
                params.width = widthPx
                view.layoutParams = params
            }

            else -> Unit
        }
    }
}
