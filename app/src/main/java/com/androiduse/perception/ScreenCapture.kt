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
 * 截图 = 向守护进程要最近一帧全分辨率 JPEG（spec 1e §4.2；1d 起一帧两用，见 captureFrame）。不再有 screencap、/data/local/tmp 中转、
 * SurfaceFlinger id：帧只在两个进程的内存之间走 LocalSocket，不落盘（§8.7 从"事后删除"变为"根本不落盘"）。
 *
 * 空屏（没有 App 渲染过任何帧）返回一张同尺寸**纯黑** JPEG——黑屏是空屏的真实状态，模型应该
 * 看到它；只有守护进程不可达/出错才返回 null（真正的截图失败）。
 */
object ScreenCapture {

    /** 可替换以便测试；真机是 DaemonClient。 */
    @Volatile
    var service: DisplayService = DaemonClient

    /** 一帧两用：[fullBitmap] 原分辨率给 OCR（空屏为 null），[jpegBase64] 缩到 maxWidth 给模型。 */
    data class Frame(val fullBitmap: Bitmap?, val jpegBase64: String)

    /**
     * 向守护进程要**全分辨率**帧（本机 LocalSocket，150–250 KB 可忽略），App 解码一次、两用：
     * 原图 Bitmap 给 OCR，缩到 [maxWidthPx] 再压 JPEG q[quality] 给模型（与 1d 之前的形态与大小一致）。
     * 空屏 → fullBitmap=null + 黑帧；守护进程不可达/出错 → null。
     */
    fun captureFrame(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): Frame? =
        when (val f = service.frame(0, FULL_FRAME_QUALITY)) {
            is DaemonProtocol.FrameResult.Ok -> {
                val bytes = Base64.decode(f.jpegBase64, Base64.NO_WRAP)
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                Frame(full, scaledJpegBase64(full, quality, maxWidthPx))
            }
            DaemonProtocol.FrameResult.Empty -> Frame(null, blackJpegBase64(screen, quality, maxWidthPx))
            is DaemonProtocol.FrameResult.Err -> null
        }

    fun captureAsJpegBase64(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): String? =
        captureFrame(screen, quality, maxWidthPx)?.jpegBase64

    /** 设置页调试用：整幅 Bitmap。 */
    fun capture(screen: VirtualScreen): Bitmap? = captureFrame(screen)?.fullBitmap

    private const val FULL_FRAME_QUALITY = 85

    private fun scaledJpegBase64(full: Bitmap, quality: Int, maxWidthPx: Int): String {
        val (w, h) = FrameGeometry.scaledSize(full.width, full.height, maxWidthPx)
        val scaled = if (w != full.width) Bitmap.createScaledBitmap(full, w, h, true) else full
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== full) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
