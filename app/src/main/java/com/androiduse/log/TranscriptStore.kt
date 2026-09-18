package com.androiduse.log

import com.androiduse.agent.OcrLine
import com.androiduse.agent.Step
import com.androiduse.daemon.DumpCodec.NodeRecord
import com.androiduse.agent.StoredTranscript
import com.androiduse.agent.Transcript
import com.androiduse.agent.TranscriptCodec
import com.androiduse.agent.TranscriptSink
import java.io.File
import java.util.Base64

/**
 * Transcript 落盘（DESIGN §11.2③：日志即调试基础设施）。
 *
 * 目录结构：`<root>/<taskId>/transcript.jsonl` 每步一行，`step-N.jpg` 是该步截图，
 * `step-N.nodes.json` 是该步的全量节点树与 OCR 原始行（提示词里的元素列表是筛选过的，归因看这个）。
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

    override fun saveNodes(t: Transcript, stepIndex: Int, nodes: List<NodeRecord>, ocrLines: List<OcrLine>?): String? = try {
        val f = File(dir(t), "step-$stepIndex.nodes.json")
        f.writeText(TranscriptCodec.encodeNodesFile(nodes, ocrLines))
        f.absolutePath
    } catch (_: Exception) {
        null
    }

    override fun step(t: Transcript, step: Step) {
        file(t).appendText(TranscriptCodec.encodeStep(step) + "\n")
    }

    override fun outcome(t: Transcript, finished: Boolean, summary: String, handoff: Boolean) {
        file(t).appendText(TranscriptCodec.encodeOutcome(finished, summary, System.currentTimeMillis(), handoff) + "\n")
    }

    /** 所有任务，最新的在前。读不出来的目录跳过。 */
    fun list(): List<StoredTranscript> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.startedAtMs }

    fun load(taskId: String): StoredTranscript? {
        val f = File(File(root, taskId), "transcript.jsonl")
        if (!f.isFile) return null
        return TranscriptCodec.decode(f.readLines())
    }
}
