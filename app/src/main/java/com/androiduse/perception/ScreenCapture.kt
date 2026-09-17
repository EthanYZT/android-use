package com.androiduse.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.androiduse.display.VirtualScreen
import com.androiduse.root.RootShell
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 按 display 截图。用 `screencap -d <SurfaceFlinger长id>`——注意不是逻辑 displayId，
 * 传错会静默产出空文件。
 *
 * 落盘走 /data/local/tmp（root 可写，且不进相册、不触发媒体扫描），再读回内存。
 */
object ScreenCapture {

    private const val TMP_PATH = "/data/local/tmp/androiduse_frame.png"

    fun capture(screen: VirtualScreen): Bitmap? {
        val sfId = screen.surfaceFlingerId.toULong().toString()
        val r = RootShell.exec("screencap -d $sfId -p $TMP_PATH")
        if (!r.ok) return null

        // App 进程读不到 /data/local/tmp，用 root 拷到 App 私有目录再读。
        val appFile = appCacheFile()
        val cp = RootShell.exec("cp $TMP_PATH ${appFile.absolutePath} && chmod 666 ${appFile.absolutePath}")
        if (!cp.ok) return null

        return BitmapFactory.decodeFile(appFile.absolutePath)
    }

    fun captureAsJpegBase64(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): String? {
        val bmp = capture(screen) ?: return null
        val scaled = if (bmp.width > maxWidthPx) {
            val ratio = maxWidthPx.toFloat() / bmp.width
            Bitmap.createScaledBitmap(bmp, maxWidthPx, (bmp.height * ratio).toInt(), true)
        } else bmp

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** App 私有缓存里的落图位置。由外部在初始化时注入。 */
    lateinit var cacheDir: File
    private fun appCacheFile() = File(cacheDir, "frame.png")
}
