package com.example.autotapper.capture

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.example.autotapper.LogBus
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.CaptureApi

/**
 * 真截图能力。[ScreenCaptureService] 负责把帧喂进来，插件通过 [CapabilityHolder] 只读它。
 *
 * 为什么缓存「最新一帧」而不是每次调用现取：
 * 1. 插件的 [isReady] 必须在动手之前就能回答「现在能不能截」，只有真的拿到过帧才敢说 true；
 * 2. ImageReader 的缓冲区必须持续排空，不取走的话画面会一直停在旧帧上；
 * 3. 交给插件的永远是副本（插件会自己 recycle），缓存帧绝不外借，否则下一次取帧会踩到已回收内存。
 *
 * 线程模型：ImageReader 回调线程写缓存，插件的执行线程读缓存，两边都进同一把锁；
 * 拷贝放在锁内完成，因此不会拷到「半张新半张旧」的帧。
 */
class ScreenCaptureApi(private val isProjectionAlive: () -> Boolean) : CaptureApi {

    private val lock = Any()

    /** 最新一帧（整屏，ARGB_8888）。只在这里和 [clear] 里替换。 */
    private var frame: Bitmap? = null

    /** 最近一次写缓存的时间；转换节流用，抖动型读取。 */
    @Volatile
    private var frameAt = 0L

    override fun isReady(): Boolean {
        if (!isProjectionAlive()) {
            return false
        }
        return synchronized(lock) {
            val current = frame
            current != null && !current.isRecycled
        }
    }

    override fun grabFullscreen(): Bitmap? = copyFrame()

    /**
     * 取最新一帧的裁剪副本。
     * 区域可能来自「旋转前」的屏幕尺寸（用户先画区域再转屏），所以先夹进帧内，
     * 否则 Bitmap.createBitmap 会抛 IllegalArgumentException 打断插件。
     */
    override fun grabRegion(region: Region): Bitmap? {
        // 先复制一份源图出来：缓存帧要留给下一次取帧，绝不能被回收，
        // 而交给插件的裁剪结果必须是独立对象（插件用完就 recycle）。
        val source = copyFrame() ?: return null

        val x = region.x.coerceIn(0, source.width - 1)
        val y = region.y.coerceIn(0, source.height - 1)
        val w = region.w.coerceIn(1, source.width - x)
        val h = region.h.coerceIn(1, source.height - y)

        if (x == 0 && y == 0 && w == source.width && h == source.height) {
            // 整帧就是裁剪结果：source 本身就是私有副本，直接交出去，不再多分配一张
            return source
        }

        var cropped: Bitmap? = null
        try {
            cropped = Bitmap.createBitmap(source, x, y, w, h)
            return cropped
        } catch (t: Throwable) {
            LogBus.warn("裁剪截图失败：" + t.javaClass.simpleName + " " + (t.message ?: ""))
            return null
        } finally {
            // source 就是这张的「原图」：裁出结果后立刻回收，插件手里不需要再多挂一张整屏图
            if (cropped !== source) {
                runCatching { source.recycle() }
            }
        }
    }

    /**
     * 用 ImageReader 的一帧更新缓存。返回是否真的写了缓存。
     *
     * 节流：1080p 一帧 RGBA_8888 就是 8 MB，60 fps 逐帧转换纯属浪费；
     * 200 ms 的陈旧度对识别 / 取色毫无影响，却能把开销压到 5 次/秒。
     * 但 ImageReader 的帧本身每帧都会被取走并 close（见 ScreenCaptureService），
     * 所以「新鲜度」由取帧保证，「转换频率」由这里保证。
     */
    fun updateFrame(image: Image): Boolean {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return false
        }
        if (SystemClock.elapsedRealtime() - frameAt < FRAME_TTL_MS && isReady()) {
            return false
        }

        val plane = runCatching { image.planes.firstOrNull() }.getOrNull() ?: return false
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != BYTES_PER_PIXEL) {
            // PixelFormat.RGBA_8888 的像素步长一定是 4；不是就说明帧格式变了，直接跳过这一帧
            LogBus.warn("截图帧的像素步长异常（" + pixelStride + "），跳过这一帧。")
            return false
        }
        val rowPadding = rowStride - pixelStride * width

        synchronized(lock) {
            val cached = frame
            if (rowPadding == 0 && cached != null && !cached.isRecycled &&
                cached.width == width && cached.height == height
            ) {
                // 行间没有填充：直接灌进已有缓存，省掉一次整屏分配（这是绝大多数设备的情形）
                try {
                    cached.copyPixelsFromBuffer(plane.buffer)
                } catch (t: Throwable) {
                    LogBus.warn("写入截图缓存失败：" + t.javaClass.simpleName)
                    return false
                }
                frameAt = SystemClock.elapsedRealtime()
                return true
            }

            // 行间有填充（少见）：先按带填充的宽度建图，再把左边有效像素裁出来
            val paddedWidth = width + rowPadding / pixelStride
            val padded = try {
                Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                LogBus.warn("创建截图位图失败：" + t.javaClass.simpleName)
                return false
            }
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val trimmed = if (paddedWidth == width) {
                    padded
                } else {
                    Bitmap.createBitmap(padded, 0, 0, width, height)
                }
                frame = trimmed
                frameAt = SystemClock.elapsedRealtime()
                // 旧缓存此刻已经没有任何人持有（取帧都是当场复制的），立刻回收，
                // 否则每换一次屏幕尺寸就多攒一张整屏图
                if (cached != null && cached !== trimmed && !cached.isRecycled) {
                    runCatching { cached.recycle() }
                }
                return true
            } catch (t: Throwable) {
                LogBus.warn("裁剪截图行填充失败：" + t.javaClass.simpleName)
                return false
            } finally {
                // 带行填充的那张就是「原图」：有效区域裁出来后立刻回收，进程里只留一张干净的缓存
                if (padded !== frame && !padded.isRecycled) {
                    runCatching { padded.recycle() }
                }
            }
        }
    }

    /** 服务收尾时调用：把缓存帧还回去，之后 [isReady] 一定是 false。 */
    fun clear() {
        synchronized(lock) {
            val current = frame
            frame = null
            frameAt = 0L
            if (current != null && !current.isRecycled) {
                runCatching { current.recycle() }
            }
        }
    }

    /** 复制缓存帧；锁内完成拷贝，避免和写缓存的线程拷出半新半旧的图。 */
    private fun copyFrame(): Bitmap? = synchronized(lock) {
        val current = frame
        if (current == null || current.isRecycled) {
            return null
        }
        runCatching { current.copy(Bitmap.Config.ARGB_8888, false) }
            .onFailure { LogBus.warn("复制截图失败：" + it.javaClass.simpleName) }
            .getOrNull()
    }

    private companion object {
        /** 缓存帧的陈旧上限：超过就重新转换一帧。 */
        const val FRAME_TTL_MS = 200L

        /** RGBA_8888 每像素 4 字节。 */
        const val BYTES_PER_PIXEL = 4
    }
}
