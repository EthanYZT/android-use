package com.androiduse.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.androiduse.display.VirtualScreen
import com.androiduse.root.RootShell
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 按 display 截图。用 `screencap -d <SurfaceFlinger长id>`——注意不是逻辑 displayId，
 * 传错会静默产出空文件。
 *
 * 落盘走 /data/local/tmp（root 可写，且不进相册、不触发媒体扫描），再读回内存。
 *
 * `TMP_PATH` 和 `appCacheFile()` 都是固定的、被复用的路径：write(screencap) -> copy(cp) ->
 * read(decode) 这一串操作没有原子性。UI 目前靠"操作中禁用按钮"顺带把调用串行化了，但
 * Task 7 的 agent 循环会在没有按钮的情况下反复调 `captureAsJpegBase64`——两次重叠调用会在
 * 同一组路径上交错写/读，静默返回错误或半写的帧（exit code 仍是 0）。用 `synchronized(this)`
 * 把整段 write->copy->decode 串行化，和 `VirtualDisplayManager` 保护 `overlay_display_devices`
 * 的做法一致。`captureAsJpegBase64` 只经过 `capture()` 接触这些路径，锁加在 `capture()` 内部
 * 就同时覆盖了 capture-vs-capture、capture-vs-captureAsJpegBase64、
 * captureAsJpegBase64-vs-captureAsJpegBase64 三种重叠。
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"
    private const val TMP_PATH = "/data/local/tmp/androiduse_frame.png"

    fun capture(screen: VirtualScreen): Bitmap? = synchronized(this) {
        val sfId = screen.surfaceFlingerId.toULong().toString()
        val r = RootShell.exec("screencap -d $sfId -p $TMP_PATH")
        if (!r.ok) return@synchronized null

        // App 进程读不到 /data/local/tmp，用 root 拷到 App 私有目录再读。
        // chmod 644：App 自己的 uid 不是 root，落到"other"类别，只需要 other 有读权限即可，
        // 不需要 666 那么宽。
        val appFile = appCacheFile()
        val cp = RootShell.exec("cp $TMP_PATH ${appFile.absolutePath} && chmod 644 ${appFile.absolutePath}")

        // F-7：/data/local/tmp 是 shell 可读目录，DESIGN §8.7 的隐私底线是敏感截图不能留在
        // 那种地方。不管上面 cp 是否成功都要删——成功路径已经把内容复制到 App 私有目录，
        // 失败路径也不该把半成品截图留在 shell 可读的地方。`-f` 保证文件已不存在时不报错；
        // 删除失败只记日志，不影响本次截图的返回值（cp 才是决定返回值的关键路径）。
        val rm = RootShell.exec("rm -f $TMP_PATH")
        if (!rm.ok) Log.w(TAG, "清理临时截图 $TMP_PATH 失败: ${rm.stderr}")

        if (!cp.ok) return@synchronized null
        BitmapFactory.decodeFile(appFile.absolutePath)
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
