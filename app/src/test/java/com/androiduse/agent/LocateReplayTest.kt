package com.androiduse.agent

import com.androiduse.BuildConfig
import com.androiduse.daemon.DumpCodec
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 离线回放（spec 2026-09-26 §6.2，计划改为 Mac 侧）：用机上拉下来的 step-N.nodes.json + 手写描述，
 * 调真实 Jev 统计命中/拒绝。默认跳过；设 LOCATE_CASES=<用例 TSV> 才联网跑，报告写到 app/build/locate-replay.txt。
 */
class LocateReplayTest {

    data class Case(val nodesPath: String, val screenW: Int, val screenH: Int, val expectedId: Int?, val target: String)

    companion object {
        fun parseCase(line: String): Case? {
            if (line.isBlank() || line.trimStart().startsWith("#")) return null
            val f = line.split('\t', limit = 5)
            if (f.size != 5) return null
            val sw = f[1].trim().toIntOrNull() ?: return null
            val sh = f[2].trim().toIntOrNull() ?: return null
            val expected = if (f[3].trim() == "none") null else (f[3].trim().toIntOrNull() ?: return null)
            val target = f[4].trim().ifEmpty { return null }
            return Case(f[0].trim(), sw, sh, expected, target)
        }

        /**
         * HIT 点中预期；WRONG 点了别的（最坏）；REJECT 该点没点；CORRECT_REJECT 不存在且拒绝了。
         * 期望写可点容器：定位到的节点中心落在它范围内就算点中（容器里的文字子节点点下去是同一个按钮）。
         */
        fun grade(expectedId: Int?, r: LocateResult, nodes: List<DumpCodec.NodeRecord>): String = when (r) {
            is LocateResult.Located -> {
                val e = nodes.firstOrNull { it.id == expectedId }
                val cx = (r.node.left + r.node.right) / 2; val cy = (r.node.top + r.node.bottom) / 2
                val inside = e != null && cx >= e.left && cx < e.right && cy >= e.top && cy < e.bottom
                if (expectedId != null && (r.node.id == expectedId || inside)) "HIT" else "WRONG"
            }
            is LocateResult.Rejected -> if (expectedId == null) "CORRECT_REJECT" else "REJECT"
        }
    }

    @Test
    fun parsesCasesAndSkipsCommentsAndBadLines() {
        assertEquals(Case("/a/step-3.nodes.json", 1080, 2400, 27, "底部的去结算"), parseCase("/a/step-3.nodes.json\t1080\t2400\t27\t底部的去结算"))
        assertEquals(Case("/a/b.json", 1080, 2400, null, "搜索框"), parseCase("/a/b.json\t1080\t2400\tnone\t搜索框"))
        assertEquals(null, parseCase("# 注释"))
        assertEquals(null, parseCase("/a\t1080\t2400\tx\t描述"))
        assertEquals(null, parseCase("/a\t1080\t2400\t27"))
    }

    @Test
    fun locatingALabelInsideTheExpectedContainerIsAHit() {
        // 可点容器 #179（底栏"订单"格子）与其文字子节点 #178：点哪个中心都点中同一个按钮。
        val container = DumpCodec.NodeRecord(179, 540, 2200, 810, 2260, "", "", "", "", true, false)
        val label = DumpCodec.NodeRecord(178, 640, 2210, 710, 2225, "订单", "", "", "", false, false)
        val nodes = listOf(container, label)
        assertEquals("HIT", grade(179, LocateResult.Located(label, 0.9, 1), nodes))
        assertEquals("WRONG", grade(178, LocateResult.Located(container, 0.9, 1), nodes))
    }

    @Test
    fun gradesEveryOutcome() {
        val n = DumpCodec.NodeRecord(27, 0, 0, 1, 1, "", "", "", "", true, false)
        assertEquals("HIT", grade(27, LocateResult.Located(n, 0.9, 1), listOf(n)))
        assertEquals("WRONG", grade(5, LocateResult.Located(n, 0.9, 1), listOf(n)))
        assertEquals("WRONG", grade(null, LocateResult.Located(n, 0.9, 1), listOf(n)))
        assertEquals("REJECT", grade(27, LocateResult.Rejected("x"), listOf(n)))
        assertEquals("CORRECT_REJECT", grade(null, LocateResult.Rejected("x"), listOf(n)))
    }

    @Test
    fun replay() {
        val path = System.getenv("LOCATE_CASES")
        assumeTrue("未设置 LOCATE_CASES，跳过联网回放", path != null)
        assumeTrue("未配置 typesafe.apiKey", BuildConfig.TYPESAFE_API_KEY.isNotBlank())
        val grounder = JevGrounder(JevClient(BuildConfig.TYPESAFE_API_KEY))
        val report = StringBuilder()
        val tally = LinkedHashMap<String, Int>()
        val latencies = ArrayList<Long>()
        for (line in File(path!!).readLines()) {
            val c = parseCase(line) ?: continue
            val nodes = DumpCodec.parseNodesFile(File(c.nodesPath).readText())
            val t0 = System.currentTimeMillis()
            val r = grounder.locate(c.target, nodes, c.screenW, c.screenH)
            latencies += System.currentTimeMillis() - t0
            val g = grade(c.expectedId, r, nodes)
            tally[g] = (tally[g] ?: 0) + 1
            val detail = when (r) {
                is LocateResult.Located -> "#${r.node.id} conf=${"%.2f".format(java.util.Locale.ROOT, r.confidence)}"
                is LocateResult.Rejected -> r.message
            }
            report.append("$g\texpect=${c.expectedId ?: "none"}\tcands=${TargetLocator.candidates(nodes, c.screenW, c.screenH).size}\t${latencies.last()}ms\t${c.target}\t$detail\n")
        }
        latencies.sort()
        report.append("\nSUMMARY $tally median=${latencies.getOrNull(latencies.size / 2)}ms max=${latencies.lastOrNull()}ms\n")
        File("build/locate-replay.txt").writeText(report.toString())
        println(report)
    }
}
