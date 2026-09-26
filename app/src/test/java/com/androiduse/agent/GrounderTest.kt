package com.androiduse.agent

import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JevGrounder 编排：无候选不请求、一轮、两轮、传输失败。JevClient 注入假 transport。 */
class GrounderTest {

    private val w = 1000; private val h = 2000

    private fun node(id: Int, top: Int, text: String) = NodeRecord(id, 0, top, 1000, top + 5, text, "", "", "", true, false)

    private fun resp(vararg answers: String) = """{"model":"jev-1.13.0","answers":{${answers.joinToString(",")}},"usage":{"input_tokens":10}}"""
    private fun where(key: String, pick: String, probs: String, conf: Double) = """"$key":{"type":"choice","choice":"$pick","probabilities":{$probs},"confidence":$conf}"""
    private fun exists(v: Double) = """"exists":{"type":"noul","noul":$v}"""

    private fun grounder(vararg bodies: String): Pair<JevGrounder, MutableList<String>> {
        val sent = mutableListOf<String>()
        val queue = bodies.toMutableList()
        val client = JevClient("k", transport = { sent += it; JevClient.HttpResult(200, queue.removeAt(0)) }, sleeper = {})
        return JevGrounder(client) to sent
    }

    @Test
    fun noCandidatesMeansNoRequest() {
        val (g, sent) = grounder()
        val r = g.locate("去结算", listOf(NodeRecord(1, 0, 0, 0, 0, "退化", "", "", "", true, false)), w, h)
        assertEquals(LocateResult.Rejected(TargetLocator.EMPTY_MESSAGE), r)
        assertEquals(0, sent.size)
    }

    @Test
    fun singlePassLocatesTheNode() {
        val nodes = listOf(node(12, 100, "生椰拿铁"), node(13, 200, "选规格"))
        val (g, sent) = grounder(resp(where("where", "13", "\"13\":0.97,\"12\":0.03", 0.95), exists(0.98)))
        val r = g.locate("选规格", nodes, w, h) as LocateResult.Located
        assertEquals(13, r.node.id)
        assertEquals(2 * 0.97 - 1, r.confidence, 1e-9) // 由分布重算（合并后的口径），不是直接取 Jev 的 confidence
        assertEquals(1, sent.size)
    }

    @Test
    fun chunkedPagesTakeASecondPassOverFinalists() {
        val nodes = (0 until TargetLocator.CHUNK_SIZE + 1).map { node(it, it * 7, "项$it") }
        val (g, sent) = grounder(
            resp(where("where_0", "5", "\"5\":0.8,\"6\":0.2", 0.6), where("where_1", "250", "\"250\":1.0", 1.0), exists(0.9)),
            resp(where("where", "5", "\"5\":0.9,\"6\":0.05,\"250\":0.05", 0.85)),
        )
        val r = g.locate("项5", nodes, w, h) as LocateResult.Located
        assertEquals(5, r.node.id)
        assertEquals(2, sent.size)
        val second = MiniJson.parse(sent[1]) as Map<*, *>
        assertEquals(setOf("5", "6", "250"), (((second["questions"] as Map<*, *>)["where"] as Map<*, *>)["criteria"] as Map<*, *>).keys)
    }

    @Test
    fun transportFailureIsReportedAsUnavailable() {
        val client = JevClient("k", transport = { JevClient.HttpResult(401, "bad key") }, sleeper = {})
        val r = JevGrounder(client).locate("选规格", listOf(node(13, 200, "选规格")), w, h) as LocateResult.Rejected
        assertTrue(r.message, r.message.startsWith("按描述定位暂不可用（HTTP 401"))
    }
}
