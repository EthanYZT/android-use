package com.androiduse.agent

import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 补洞（spec 1d §4.2）：行中心落在"带字节点"框内 → 节点树已覆盖，丢弃；否则伪装成 NodeRecord 追加。
 */
class OcrMergeTest {

    private fun node(id: Int, l: Int, t: Int, r: Int, b: Int, text: String = "", desc: String = "") =
        NodeRecord(id, l, t, r, b, text, desc, "", "android.view.View", false, false)

    private fun line(text: String, l: Int, t: Int, r: Int = l + 200, b: Int = t + 40) = OcrLine(text, l, t, r, b)

    private val W = 1080
    private val H = 2376

    @Test
    fun lineInsideTextBearingNodeIsDropped() {
        val nodes = listOf(node(3, 0, 100, 1080, 200, text = "显示与亮度"))
        val out = OcrMerge.merge(nodes, listOf(line("显示与亮度", 200, 130)), W, H)
        assertEquals(nodes, out)
    }

    @Test
    fun lineInsideDescOnlyNodeIsAlsoDropped() {
        val nodes = listOf(node(3, 0, 100, 1080, 200, desc = "返回"))
        assertEquals(1, OcrMerge.merge(nodes, listOf(line("返回", 10, 120)), W, H).size)
    }

    @Test
    fun lineInsideTextlessNodeIsKept() {
        // 相册里的图片节点：有框没字，图里的文字要补出来
        val nodes = listOf(node(5, 0, 0, 1080, 2376))
        val out = OcrMerge.merge(nodes, listOf(line("爱学A", 700, 1200)), W, H)
        assertEquals(2, out.size)
        val ocr = out[1]
        assertEquals("爱学A", ocr.text)
        assertEquals(OcrMerge.OCR_CLASS, ocr.className)
        assertEquals(6, ocr.id)
        assertFalse(ocr.clickable)
        assertEquals(listOf(700, 1200, 900, 1240), listOf(ocr.left, ocr.top, ocr.right, ocr.bottom))
    }

    @Test
    fun withoutNodesAllLinesAreKept() {
        val out = OcrMerge.merge(emptyList(), listOf(line("a", 0, 0), line("b", 0, 100)), W, H)
        assertEquals(listOf("a", "b"), out.map { it.text })
        assertEquals(listOf(0, 1), out.map { it.id })
    }

    @Test
    fun blankAndPunctuationOnlyLinesAreDropped() {
        val out = OcrMerge.merge(emptyList(), listOf(line("  ", 0, 0), line("•", 0, 50), line("- -", 0, 100), line("OK", 0, 150)), W, H)
        assertEquals(listOf("OK"), out.map { it.text })
    }

    @Test
    fun idsContinueAfterMaxNodeIdInTopLeftOrder() {
        val nodes = listOf(node(2, 0, 0, 100, 50, text = "x"), node(9, 0, 60, 100, 110, text = "y"))
        val out = OcrMerge.merge(nodes, listOf(line("下右", 600, 800), line("下左", 100, 800), line("上", 100, 500)), W, H)
        val ocr = out.drop(2)
        assertEquals(listOf("上", "下左", "下右"), ocr.map { it.text })
        assertEquals(listOf(10, 11, 12), ocr.map { it.id })
    }

    @Test
    fun atMostMaxEntriesAreAdded() {
        val lines = (0 until OcrMerge.MAX_OCR_ENTRIES + 5).map { line("t$it", 0, it * 50) }
        assertEquals(OcrMerge.MAX_OCR_ENTRIES, OcrMerge.merge(emptyList(), lines, W, H).size)
    }

    @Test
    fun degenerateNodeBoundsDoNotCover() {
        val nodes = listOf(node(1, 0, 500, 1080, 400, text = "滚出屏幕的行")) // bottom < top
        assertEquals(2, OcrMerge.merge(nodes, listOf(line("可见文字", 100, 450)), W, H).size)
    }

    @Test
    fun emptyLinesReturnSameList() {
        val nodes = listOf(node(1, 0, 0, 10, 10, text = "a"))
        assertSame(nodes, OcrMerge.merge(nodes, emptyList(), W, H))
    }

    @Test
    fun fullScreenLabelledContainerDoesNotCover() {
        // 真机：相册看图页有一个铺满全屏的 desc="图片" 节点，图里的文字必须能补出来。
        val nodes = listOf(node(1, 0, 0, 1080, 2376, desc = "图片"), node(3, 0, 0, 200, 60, text = "今天"))
        val out = OcrMerge.merge(nodes, listOf(line("爱学A", 700, 1200), line("今天", 20, 10)), W, H)
        assertEquals(listOf("爱学A"), out.drop(2).map { it.text })
    }

    @Test
    fun largeTextNodeStillCoversLinesItActuallyContains() {
        // 大段正文的 TextView（超过半屏）：OCR 读出的是它自己的文字，不该重复补
        val nodes = listOf(node(1, 0, 0, 1080, 1500, text = "第一段很长的正文，包含 爱学A 这样的词，一直写到很长"))
        val out = OcrMerge.merge(nodes, listOf(line("爱学A", 300, 700), line("不在正文里的字", 300, 900)), W, H)
        assertEquals(listOf("不在正文里的字"), out.drop(1).map { it.text })
    }
}
