package com.androiduse.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2 协议：一行 JSON 请求 / 一行 JSON 响应，cmd 分派。dump 形态与 v1 逐字相同（只加不改）。 */
class DaemonProtocolTest {

    @Test
    fun parsesAllFiveCommands() {
        assertEquals(DaemonProtocol.Request.CreateDisplay(1080, 2376, 480),
            DaemonProtocol.parseRequest("""{"cmd":"create_display","w":1080,"h":2376,"dpi":480}"""))
        assertEquals(DaemonProtocol.Request.DestroyDisplay, DaemonProtocol.parseRequest("""{"cmd":"destroy_display"}"""))
        assertEquals(DaemonProtocol.Request.Frame(720, 80), DaemonProtocol.parseRequest("""{"cmd":"frame","maxWidth":720,"quality":80}"""))
        assertEquals(DaemonProtocol.Request.Dump(9), DaemonProtocol.parseRequest("""{"cmd":"dump","displayId":9}"""))
        assertEquals(DaemonProtocol.Request.Lease, DaemonProtocol.parseRequest("""{"cmd":"lease"}"""))
    }

    @Test
    fun frameDefaultsWhenFieldsMissing() {
        assertEquals(DaemonProtocol.Request.Frame(DaemonProtocol.DEFAULT_MAX_WIDTH, DaemonProtocol.DEFAULT_QUALITY),
            DaemonProtocol.parseRequest("""{"cmd":"frame"}"""))
    }

    @Test
    fun rejectsUnknownOrMalformed() {
        assertNull(DaemonProtocol.parseRequest("""{"cmd":"teleport"}"""))
        assertNull(DaemonProtocol.parseRequest("""{"cmd":"create_display","w":1080}""")) // 缺 h/dpi
        assertNull(DaemonProtocol.parseRequest("""{"cmd":"dump"}"""))
        assertNull(DaemonProtocol.parseRequest("garbage"))
    }

    @Test
    fun encodeRequestsRoundTrip() {
        assertEquals(DaemonProtocol.Request.CreateDisplay(1, 2, 3), DaemonProtocol.parseRequest(DaemonProtocol.encodeCreateDisplay(1, 2, 3)))
        assertEquals(DaemonProtocol.Request.DestroyDisplay, DaemonProtocol.parseRequest(DaemonProtocol.encodeDestroyDisplay()))
        assertEquals(DaemonProtocol.Request.Frame(600, 70), DaemonProtocol.parseRequest(DaemonProtocol.encodeFrame(600, 70)))
        assertEquals(DaemonProtocol.Request.Lease, DaemonProtocol.parseRequest(DaemonProtocol.encodeLease()))
    }

    @Test
    fun dumpRequestIsByteIdenticalToV1() {
        assertEquals("""{"cmd":"dump","displayId":9}""", DumpCodec.encodeRequest(DumpCodec.DumpRequest(9)))
        assertEquals(DaemonProtocol.Request.Dump(9), DaemonProtocol.parseRequest(DumpCodec.encodeRequest(DumpCodec.DumpRequest(9))))
    }

    @Test
    fun createResponseRoundTrip() {
        assertEquals(DaemonProtocol.CreateResult.Ok(9, 1080, 2376),
            DaemonProtocol.parseCreateResponse(DaemonProtocol.encodeCreateOk(9, 1080, 2376)))
        assertEquals(DaemonProtocol.CreateResult.Err("boom"), DaemonProtocol.parseCreateResponse(DumpCodec.encodeError("boom")))
        assertTrue(DaemonProtocol.parseCreateResponse("""{"ok":true}""") is DaemonProtocol.CreateResult.Err) // 缺 displayId
    }

    @Test
    fun simpleResponse() {
        assertNull(DaemonProtocol.parseSimpleResponse(DaemonProtocol.encodeOk()))
        assertEquals("nope", DaemonProtocol.parseSimpleResponse(DumpCodec.encodeError("nope")))
        assertEquals("unparseable response: garbage", DaemonProtocol.parseSimpleResponse("garbage"))
    }

    @Test
    fun frameResponseThreeShapes() {
        assertEquals(DaemonProtocol.FrameResult.Ok("AAAA"), DaemonProtocol.parseFrameResponse(DaemonProtocol.encodeFrameOk("AAAA")))
        assertEquals(DaemonProtocol.FrameResult.Empty, DaemonProtocol.parseFrameResponse(DaemonProtocol.encodeFrameEmpty()))
        assertEquals(DaemonProtocol.FrameResult.Err("x"), DaemonProtocol.parseFrameResponse(DumpCodec.encodeError("x")))
        assertTrue(DaemonProtocol.parseFrameResponse("""{"ok":true}""") is DaemonProtocol.FrameResult.Err) // 既无 jpeg 也无 empty
    }
}
