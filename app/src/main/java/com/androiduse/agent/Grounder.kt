package com.androiduse.agent

import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * tap 的 target 形态：按自然语言描述在**全量**节点里找元素。AgentLoop 只依赖这个接口，单测注入假实现。
 * spec `docs/superpowers/specs/2026-09-26-jev-target-grounding-design.md` §3.3。
 */
interface Grounder {
    fun locate(target: String, nodes: List<NodeRecord>, screenW: Int, screenH: Int): LocateResult
}

sealed class LocateResult {
    data class Located(val node: NodeRecord, val confidence: Double, val latencyMs: Long) : LocateResult()
    /** message 直接作为 tool 结果回给规划器。 */
    data class Rejected(val message: String) : LocateResult()
}

/** [TargetLocator] 出题与判定 + [JevClient] 传输。候选 > [TargetLocator.CHUNK_SIZE] 时两轮。 */
class JevGrounder(private val client: JevClient) : Grounder {

    override fun locate(target: String, nodes: List<NodeRecord>, screenW: Int, screenH: Int): LocateResult {
        val t0 = System.currentTimeMillis()
        val cands = TargetLocator.candidates(nodes, screenW, screenH)
        if (cands.isEmpty()) return LocateResult.Rejected(TargetLocator.EMPTY_MESSAGE)

        val req1 = TargetLocator.firstRequest(target, cands)
        val answers1 = when (val r = client.evaluate(req1.stateJson, req1.questionsJson)) {
            is JevClient.Result.Err -> return LocateResult.Rejected(TargetLocator.unavailable(r.message))
            is JevClient.Result.Ok -> r.answers
        }
        var verdict = TargetLocator.interpretFirst(target, cands, answers1)
        if (verdict is TargetLocator.Verdict.NeedsSecondPass) {
            val finalists = verdict.finalists
            val req2 = TargetLocator.secondRequest(target, finalists)
            val answers2 = when (val r = client.evaluate(req2.stateJson, req2.questionsJson)) {
                is JevClient.Result.Err -> return LocateResult.Rejected(TargetLocator.unavailable(r.message))
                is JevClient.Result.Ok -> r.answers
            }
            verdict = TargetLocator.interpretSecond(target, finalists, answers2)
        }
        return when (verdict) {
            is TargetLocator.Verdict.Located -> LocateResult.Located(verdict.candidate.node, verdict.confidence, System.currentTimeMillis() - t0)
            is TargetLocator.Verdict.Rejected -> LocateResult.Rejected(verdict.message)
            is TargetLocator.Verdict.NeedsSecondPass -> LocateResult.Rejected(TargetLocator.unavailable("分块定位未收敛"))
        }
    }
}
