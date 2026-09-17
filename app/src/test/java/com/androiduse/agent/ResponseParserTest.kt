package com.androiduse.agent

import com.androiduse.actuation.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {

    @Test
    fun parsesTapFromCleanJson() {
        val action = ResponseParser.parseAction("""{"action":"tap","x":500,"y":620}""")
        assertEquals(Action.Tap(500, 620), action)
    }

    @Test
    fun parsesJsonWrappedInMarkdownFence() {
        // 模型经常把 JSON 包在 ```json ... ``` 里
        val raw = "好的，我先点开显示设置。\n```json\n{\"action\":\"tap\",\"x\":300,\"y\":900}\n```"
        assertEquals(Action.Tap(300, 900), ResponseParser.parseAction(raw))
    }

    @Test
    fun parsesSwipeWithDuration() {
        val action = ResponseParser.parseAction(
            """{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration":400}"""
        )
        assertEquals(Action.Swipe(500, 800, 500, 200, 400), action)
    }

    @Test
    fun parsesBackAndHome() {
        assertEquals(Action.Back, ResponseParser.parseAction("""{"action":"back"}"""))
        assertEquals(Action.Home, ResponseParser.parseAction("""{"action":"home"}"""))
    }

    @Test
    fun parsesFinishWithSummary() {
        val action = ResponseParser.parseAction("""{"action":"finish","summary":"已进入显示与亮度"}""")
        assertEquals(Action.Finish("已进入显示与亮度"), action)
    }

    @Test
    fun parsesWait() {
        assertEquals(Action.Wait(800), ResponseParser.parseAction("""{"action":"wait","ms":800}"""))
    }

    @Test
    fun returnsNullOnUnknownAction() {
        assertNull(ResponseParser.parseAction("""{"action":"teleport","x":1,"y":2}"""))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(ResponseParser.parseAction("我不知道该做什么"))
    }

    @Test
    fun extractsContentFromArkResponse() {
        val api = """
            {"choices":[{"message":{"role":"assistant","content":"{\"action\":\"home\"}"}}]}
        """.trimIndent()
        assertEquals("""{"action":"home"}""", ResponseParser.extractContent(api))
    }

    @Test
    fun extractContentReturnsNullWhenNoChoices() {
        assertNull(ResponseParser.extractContent("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun systemPromptTellsModelToUseNormalizedCoordinates() {
        assertTrue(PromptBuilder.systemPrompt().contains("0"))
        assertTrue(PromptBuilder.systemPrompt().contains("1000"))
    }
}
