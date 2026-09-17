package com.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读回 transcript.jsonl 用的手写 JSON 解析器（Android 自带 org.json 在 JVM 单测里是桩）。
 * 只要求正确解析我们自己写出的 JSON 和方舟返回的 JSON 形态。
 */
class MiniJsonTest {

    @Test
    fun parsesNestedObjectsArraysAndScalars() {
        val v = MiniJson.parse("""{"a":1,"b":-2.5,"c":true,"d":null,"e":"s","f":[1,"x",{"g":[]}],"h":{}}""")
        val m = v as Map<*, *>
        assertEquals(1L, m["a"])
        assertEquals(-2.5, m["b"])
        assertEquals(true, m["c"])
        assertTrue(m.containsKey("d"))
        assertNull(m["d"])
        assertEquals("s", m["e"])
        val f = m["f"] as List<*>
        assertEquals(listOf(1L, "x", mapOf("g" to emptyList<Any?>())), f)
        assertEquals(emptyMap<String, Any?>(), m["h"])
    }

    @Test
    fun unescapesStrings() {
        val v = MiniJson.parse("""{"s":"a\"b\\c\nd一\t"}""") as Map<*, *>
        assertEquals("a\"b\\c\nd一\t", v["s"])
    }

    @Test
    fun toleratesWhitespaceEverywhere() {
        val v = MiniJson.parse(" {\n \"a\" : [ 1 , 2 ] ,\n \"b\" : { \"c\" : \"d\" } }\n") as Map<*, *>
        assertEquals(listOf(1L, 2L), v["a"])
        assertEquals(mapOf("c" to "d"), v["b"])
    }

    @Test
    fun roundTripsWhatPromptBuilderEscapes() {
        val original = "引号\" 反斜杠\\ 换行\n 控制 结尾"
        val v = MiniJson.parse("""{"s":${PromptBuilder.jsonString(original)}}""") as Map<*, *>
        assertEquals(original, v["s"])
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(MiniJson.parse("""{"a":"""))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("not json"))
    }
}
