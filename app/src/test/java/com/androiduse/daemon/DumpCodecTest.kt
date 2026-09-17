package com.androiduse.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpCodecTest {

    private fun node(id: Int, text: String = "", desc: String = "", resId: String = "",
                     cls: String = "android.widget.TextView", click: Boolean = false) =
        DumpCodec.NodeRecord(id, 0, 0, 100, 50, text, desc, resId, cls, click, false)

    @Test
    fun parseRequest_validDump() {
        assertEquals(DumpCodec.DumpRequest(16), DumpCodec.parseRequest("{\"cmd\":\"dump\",\"displayId\":16}"))
    }

    @Test
    fun parseRequest_toleratesTrailingNewlineAndSpaces() {
        assertEquals(DumpCodec.DumpRequest(2), DumpCodec.parseRequest("  {\"cmd\":\"dump\",\"displayId\":2}  \n"))
    }

    @Test
    fun parseRequest_rejectsUnknownOrMalformed() {
        assertNull(DumpCodec.parseRequest("{\"cmd\":\"quit\"}"))
        assertNull(DumpCodec.parseRequest("{\"displayId\":1}"))
        assertNull(DumpCodec.parseRequest("garbage"))
        assertNull(DumpCodec.parseRequest("{\"cmd\":\"dump\"}")) // 缺 displayId
    }

    @Test
    fun encodeDecodeRequest_roundTrips() {
        val req = DumpCodec.DumpRequest(7)
        assertEquals(req, DumpCodec.parseRequest(DumpCodec.encodeRequest(req)))
    }

    @Test
    fun encodeOk_shapeAndFields() {
        val out = DumpCodec.encodeOk(16, listOf(node(0, text = "显示与亮度", resId = "android:id/title", click = true)))
        assertTrue(out.startsWith("{\"ok\":true,\"displayId\":16,\"nodes\":["))
        assertTrue(out.contains("\"id\":0"))
        assertTrue(out.contains("\"text\":\"显示与亮度\""))
        assertTrue(out.contains("\"resId\":\"android:id/title\""))
        assertTrue(out.contains("\"click\":true"))
        assertTrue(out.contains("\"b\":[0,0,100,50]"))
    }

    @Test
    fun encodeOk_emptyNodes() {
        assertEquals("{\"ok\":true,\"displayId\":3,\"nodes\":[]}", DumpCodec.encodeOk(3, emptyList()))
    }

    @Test
    fun encodeError_escapes() {
        val out = DumpCodec.encodeError("boom \"x\"\n")
        assertEquals("{\"ok\":false,\"error\":\"boom \\\"x\\\"\\n\"}", out)
    }

    @Test
    fun nodeText_withQuotesAndNewlines_staysValidJsonString() {
        // 屏幕文字含引号/换行不能破坏线格式（wire 层保真+转义，不截断）。
        val out = DumpCodec.encodeOk(1, listOf(node(0, text = "say \"hi\"\nthen delete")))
        assertTrue(out.contains("\"text\":\"say \\\"hi\\\"\\nthen delete\""))
        // 用自己的 strField 能把它原样解回来（往返保真）。
        assertEquals("say \"hi\"\nthen delete", DumpCodec.strField("{\"text\":\"say \\\"hi\\\"\\nthen delete\"}", "text"))
    }

    @Test
    fun intField_handlesNegative() {
        assertEquals(-1, DumpCodec.intField("{\"displayId\":-1}", "displayId"))
    }

    @Test
    fun strField_ignoresKeyLikeSubstringInsideValue() {
        // 值里恰好含 "cmd" 片段，不能把它当成 key 命中。
        val json = "{\"note\":\"the cmd is here\",\"cmd\":\"dump\"}"
        assertEquals("dump", DumpCodec.strField(json, "cmd"))
    }
}

// ---- 响应解析（往返 + 健壮性）----
class DumpCodecResponseTest {
    private fun node(id: Int, l: Int, t: Int, r: Int, b: Int, text: String = "", resId: String = "",
                     cls: String = "android.widget.TextView", click: Boolean = false, scroll: Boolean = false) =
        DumpCodec.NodeRecord(id, l, t, r, b, text, "", resId, cls, click, scroll)

    @org.junit.Test
    fun response_roundTrip() {
        val nodes = listOf(
            node(0, 240, 2070, 900, 2195, text = "显示与亮度", resId = "android:id/title", click = true),
            node(1, 0, 0, 1080, 200, text = "say \"hi\"\nx", cls = "android.widget.EditText", scroll = true),
        )
        val wire = DumpCodec.encodeOk(16, nodes)
        val parsed = DumpCodec.parseResponse(wire)
        org.junit.Assert.assertTrue(parsed is DumpCodec.DumpResult.Ok)
        val ok = parsed as DumpCodec.DumpResult.Ok
        org.junit.Assert.assertEquals(16, ok.displayId)
        org.junit.Assert.assertEquals(nodes, ok.nodes)
    }

    @org.junit.Test
    fun response_emptyNodes() {
        val ok = DumpCodec.parseResponse(DumpCodec.encodeOk(2, emptyList())) as DumpCodec.DumpResult.Ok
        org.junit.Assert.assertEquals(2, ok.displayId)
        org.junit.Assert.assertTrue(ok.nodes.isEmpty())
    }

    @org.junit.Test
    fun response_error() {
        val err = DumpCodec.parseResponse(DumpCodec.encodeError("connect failed: boom")) as DumpCodec.DumpResult.Err
        org.junit.Assert.assertEquals("connect failed: boom", err.message)
    }

    @org.junit.Test
    fun response_truncatedIsError() {
        val err = DumpCodec.parseResponse("{\"ok\":true,\"displayId\":1,\"nodes\":[{\"id\":0")
        org.junit.Assert.assertTrue(err is DumpCodec.DumpResult.Err || (err is DumpCodec.DumpResult.Ok && (err).nodes.isEmpty()))
    }

    @org.junit.Test
    fun response_nodeTextWithBracesDoesNotBreakParsing() {
        // 屏幕文字里含 } 或 ] 不能提前结束对象/数组解析。
        val nodes = listOf(node(0, 1, 2, 3, 4, text = "a}b]c{d", click = true))
        val ok = DumpCodec.parseResponse(DumpCodec.encodeOk(1, nodes)) as DumpCodec.DumpResult.Ok
        org.junit.Assert.assertEquals("a}b]c{d", ok.nodes[0].text)
        org.junit.Assert.assertEquals(1, ok.nodes.size)
    }
}
