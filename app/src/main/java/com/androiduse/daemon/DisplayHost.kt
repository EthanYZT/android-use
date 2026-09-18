package com.androiduse.daemon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * 守护进程持有的不可见虚拟屏（spec 1e §4.1）。
 *
 * 建屏：root `app_process` 里 `ActivityThread.systemMain().getSystemContext()` 拿 DisplayManager，
 * `createVirtualDisplay` 到一个 ImageReader 的 Surface。flags 见 [FLAGS]（2026-09-18 spike 真机验证）：
 * TRUSTED 让它接受注入与 Activity 启动；OWN_CONTENT_ONLY 空屏不镜像物理屏；
 * DESTROY_CONTENT_ON_REMOVAL 销屏时任务被销毁而不是搬到物理屏。root uid 0 过 DMS 的包名与权限校验。
 * 不加 SHOULD_SHOW_SYSTEM_DECORATIONS：会在屏上起一套桌面/导航栏，多一个进程和一层点错的可能。
 *
 * 取帧：界面静止时 `acquireLatestImage` 返回 null（spike 实测），所以在回调线程里始终**保留最近一帧**，
 * 新帧到就替换并关闭旧帧。maxImages=3 保证我们持有一帧时生产者还有缓冲。
 *
 * 同一时间只有一块屏。进程退出屏随之消失，任务被系统销毁，不会泄漏到物理屏。
 */
object DisplayHost {

    /** PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL|TRUSTED|OWN_FOCUS */
    const val FLAGS = 0x45c9
    private const val NAME = "androiduse-headless"

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var w = 0
    private var h = 0
    private val cbThread by lazy { HandlerThread("aud-frames").apply { start() } }
    private val cbHandler by lazy { Handler(cbThread.looper) }

    @Synchronized fun hasDisplay(): Boolean = vd != null

    /** 幂等：已有同尺寸屏返回其 id；尺寸不同先销后建。失败抛异常。 */
    @Synchronized
    fun create(w: Int, h: Int, dpi: Int): Int {
        vd?.let { existing ->
            if (this.w == w && this.h == h) return existing.display.displayId
            destroy()
        }
        val ctx = systemContext()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            synchronized(this) {
                if (reader !== rd) { img.close(); return@synchronized } // 已销屏，迟到的帧直接丢
                latest?.close()
                latest = img
            }
        }, cbHandler)
        val created = dm.createVirtualDisplay(NAME, w, h, dpi, r.surface, FLAGS, null, cbHandler)
        vd = created
        reader = r
        this.w = w
        this.h = h
        return created.display.displayId
    }

    @Synchronized
    fun destroy() {
        latest?.close(); latest = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        w = 0; h = 0
    }

    /** 最近一帧压成 JPEG（按 rowStride 拷入、裁掉 padding、缩到 maxWidth）；没有帧（空屏）返回 null。 */
    @Synchronized
    fun encodeJpeg(maxWidth: Int, quality: Int): ByteArray? {
        val img = latest ?: return null
        val plane = img.planes[0]
        val padded = FrameGeometry.paddedWidth(plane.rowStride, plane.pixelStride)
        val full = Bitmap.createBitmap(padded, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (FrameGeometry.needsCrop(plane.rowStride, plane.pixelStride, img.width))
            Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
        val (sw, sh) = FrameGeometry.scaledSize(cropped.width, cropped.height, maxWidth)
        val scaled = if (sw != cropped.width) Bitmap.createScaledBitmap(cropped, sw, sh, true) else cropped
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        full.recycle()
        return out.toByteArray()
    }

    /** 裸 app_process 没有应用 Context；与 AgentCli 取 PackageManager 同路。 */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }
}
