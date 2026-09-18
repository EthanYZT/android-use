package com.androiduse.perception

import android.graphics.Bitmap
import android.util.Log
import com.androiduse.agent.OcrLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 端侧文字识别边界（spec 1d §4.2）。真机实现 [MlKitTextReader]；`AgentCli` 传 null（裸 app_process 没有
 * ML Kit 的 ContentProvider 初始化、也加载不了它的 so）；单测用假实现。
 * 返回 null 表示识别失败/超时，调用方退化为纯节点列表。
 */
interface TextReader {
    fun read(bitmap: Bitmap): List<OcrLine>?
}

/**
 * ML Kit 中文识别（`text-recognition-chinese` 打包版，模型在 APK 里，离线、不上传）。
 * 识别器单例复用；阻塞等待最多 [TIMEOUT_SECONDS] 秒（调用方已在 IO 线程）。
 * 输出按**行**（`Text.Line`）——行是可点/可读的自然单位，块会把多行合并。
 */
object MlKitTextReader : TextReader {
    private const val TAG = "MlKitTextReader"
    private const val TIMEOUT_SECONDS = 3L

    private val client: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun read(bitmap: Bitmap): List<OcrLine>? = try {
        val text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            out += OcrLine(line.text, b.left, b.top, b.right, b.bottom)
        }
        out
    } catch (e: Throwable) {
        Log.w(TAG, "OCR 失败: $e")
        null
    }
}
