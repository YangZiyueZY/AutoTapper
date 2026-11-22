package com.example.autotapper.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.example.autotapper.LogBus
import com.example.autotapper.R

/**
 * 截图授权的中转页：透明、noHistory（声明见 AndroidManifest.xml），用户看不见它。
 *
 * 它只做三件事：拉起系统的「共享屏幕」弹窗 → 把 resultCode / data 交给 [ScreenCaptureService] → 自己退出。
 * 放这里而不是 MainActivity，是因为截图授权属于插件能力，不该和主界面生命周期绑在一起。
 *
 * 继承平台 Activity 而不是 AppCompatActivity：清单里给的是
 * @android:style/Theme.Translucent.NoTitleBar，AppCompat 遇到非 AppCompat 主题会直接崩。
 * 也正因为是平台 Activity，这里用的是 startActivityForResult 这一套回调。
 */
class CapturePermissionActivity : Activity() {

    /** 是否已经处理过授权结果。只用来在 onDestroy 里判断「结果没回来」这种异常情况。 */
    private var resultHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 转屏等重建会再走一次 onCreate；授权弹窗弹过就别弹第二次（noHistory 之外的双保险）
        if (savedInstanceState == null) {
            requestScreenCapture()
        }
    }

    override fun onDestroy() {
        // 清单里这一页是 noHistory：如果哪天系统弹窗把它顶掉且结果没送回来，
        // 现象是「点了截图什么也没发生」。留一条日志，比让人对着黑屏猜要强。
        if (!resultHandled && !isFinishing) {
            LogBus.warn("截图授权页没有拿到结果就被系统回收了，请重试一次。")
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            LogBus.error("系统没有屏幕录制服务，这台设备不支持截图。")
            Toast.makeText(this, R.string.capture_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (t: Throwable) {
            LogBus.error("打开截图授权弹窗失败：" + t.javaClass.simpleName + " " + (t.message ?: ""))
            Toast.makeText(this, R.string.capture_unavailable, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) {
            finish()
            return
        }
        resultHandled = true
        if (resultCode == RESULT_OK && data != null) {
            val started = ScreenCaptureController.startWithPermission(this, resultCode, data)
            if (!started) {
                Toast.makeText(this, R.string.capture_start_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            LogBus.warn("用户拒绝了截图授权，插件里的截图能力保持不可用。")
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 4201

        /**
         * 申请截图授权。调用后系统弹窗出现，用户同意则 [ScreenCaptureService] 起来。
         * 从非 Activity 的 Context（服务、设置页的工具类）来调也安全：加了 NEW_TASK。
         */
        fun request(context: Context) {
            runCatching {
                val intent = Intent(context, CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                LogBus.error("打不开截图授权页：" + it.javaClass.simpleName + " " + (it.message ?: ""))
            }
        }
    }
}
