package com.androiduse.log

import com.androiduse.agent.Step
import com.androiduse.agent.Transcript
import com.androiduse.agent.TranscriptCodec
import com.androiduse.agent.TranscriptSink
import java.io.File
import java.util.Base64

/**
 * Transcript 落盘（DESIGN §11.2③：日志即调试基础设施）。
 *
 * 目录结构：`<root>/<taskId>/transcript.jsonl` 每步一行，`step-N.jpg` 是该步截图。
 * 每步立即追加写，任务中途崩溃也能保住之前的记录。这是子项目 B（日志查看/导出）的数据源。
 */
class TranscriptStore(private val root: File) : TranscriptSink {

    private fun dir(t: Transcript) = File(root, t.taskId)
    private fun file(t: Transcript) = File(dir(t), "transcript.jsonl")

    override fun start(t: Transcript) {
        dir(t).mkdirs()
        file(t).writeText(TranscriptCodec.encodeHeader(t) + "\n")
    }

    override fun saveScreenshot(t: Transcript, stepIndex: Int, jpegBase64: String): String? = try {
        val f = File(dir(t), "step-$stepIndex.jpg")
        f.writeBytes(Base64.getDecoder().decode(jpegBase64))
        f.absolutePath
    } catch (_: Exception) {
        null
    }

    override fun step(t: Transcript, step: Step) {
        file(t).appendText(TranscriptCodec.encodeStep(step) + "\n")
    }
}
