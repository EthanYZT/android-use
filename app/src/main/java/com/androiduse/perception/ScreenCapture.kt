package com.androiduse.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.androiduse.daemon.DaemonProtocol
import com.androiduse.daemon.FrameGeometry
import com.androiduse.display.DisplayService
import com.androiduse.display.VirtualScreen
import com.androiduse.root.DaemonClient
import java.io.ByteArrayOutputStream

/**
 * 截图 = 向守护进程要最近一帧 JPEG（spec 1e §4.2）。不再有 screencap、/data/local/tmp 中转、
 * SurfaceFlinger id：帧只在两个进程的内存之间走 LocalSocket，不落盘（§8.7 从"事后删除"变为"根本不落盘"）。
 *
 * 空屏（没有 App 渲染过任何帧）返回一张同尺寸**纯黑** JPEG——黑屏是空屏的真实状态，模型应该
 * 看到它；只有守护进程不可达/出错才返回 null（真正的截图失败）。
 */
object ScreenCapture {

    /** 可替换以便测试；真机是 DaemonClient。 */
    @Volatile
    var service: DisplayService = DaemonClient

    fun captureAsJpegBase64(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): String? =
        when (val f = service.frame(maxWidthPx, quality)) {
            is DaemonProtocol.FrameResult.Ok -> f.jpegBase64
            DaemonProtocol.FrameResult.Empty -> blackJpegBase64(screen, quality, maxWidthPx)
            is DaemonProtocol.FrameResult.Err -> null
        }

    /** 设置页调试用：整幅解码成 Bitmap。 */
    fun capture(screen: VirtualScreen): Bitmap? {
        val b64 = captureAsJpegBase64(screen, quality = 90, maxWidthPx = 0) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private var blackCache: Pair<Triple<Int, Int, Int>, String>? = null

    @Synchronized
    private fun blackJpegBase64(screen: VirtualScreen, quality: Int, maxWidthPx: Int): String {
        val (w, h) = FrameGeometry.scaledSize(screen.widthPx, screen.heightPx, maxWidthPx)
        val key = Triple(w, h, quality)
        blackCache?.let { if (it.first == key) return it.second }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { eraseColor(Color.BLACK) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        val s = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        blackCache = key to s
        return s
    }
}
