package com.androiduse.daemon

/**
 * 守护进程协议 v2（spec 1e §4.1）：一行 JSON 请求 / 一行 JSON 响应，按 `cmd` 分派。
 * `dump` 的请求/响应与 v1（[DumpCodec]）逐字相同——v2 只加不改；节点树的编解码仍在 DumpCodec。
 * 手写字符串，不用 org.json（JVM 单测里是桩）。取值原语复用 DumpCodec 的 internal 函数。
 */
object DaemonProtocol {

    sealed class Request {
        data class CreateDisplay(val w: Int, val h: Int, val dpi: Int) : Request()
        data object DestroyDisplay : Request()
        data class Frame(val maxWidth: Int, val quality: Int) : Request()
        data class Dump(val displayId: Int) : Request()
        /** 响应后连接保持打开；守护进程读到 EOF 即销屏。 */
        data object Lease : Request()
        /** 往可编辑节点写入文字（无障碍 ACTION_SET_TEXT，支持中文、不依赖输入法）。nodeId 为空则找焦点/首个可编辑节点。 */
        data class SetText(val displayId: Int, val nodeId: Int?, val text: String, val submit: Boolean) : Request()
    }

    const val DEFAULT_MAX_WIDTH = 720
    const val DEFAULT_QUALITY = 80

    fun parseRequest(line: String): Request? {
        val s = line.trim()
        val cmd = DumpCodec.strField(s, "cmd") ?: return null
        return when (cmd) {
            "create_display" -> {
                val w = DumpCodec.intField(s, "w") ?: return null
                val h = DumpCodec.intField(s, "h") ?: return null
                val dpi = DumpCodec.intField(s, "dpi") ?: return null
                Request.CreateDisplay(w, h, dpi)
            }
            "destroy_display" -> Request.DestroyDisplay
            "frame" -> Request.Frame(
                DumpCodec.intField(s, "maxWidth") ?: DEFAULT_MAX_WIDTH,
                DumpCodec.intField(s, "quality") ?: DEFAULT_QUALITY,
            )
            "dump" -> Request.Dump(DumpCodec.intField(s, "displayId") ?: return null)
            "lease" -> Request.Lease
            "set_text" -> {
                val displayId = DumpCodec.intField(s, "displayId") ?: return null
                val text = DumpCodec.strField(s, "text") ?: return null
                Request.SetText(displayId, DumpCodec.intField(s, "nodeId"), text, DumpCodec.boolField(s, "submit") ?: false)
            }
            else -> null
        }
    }

    fun encodeCreateDisplay(w: Int, h: Int, dpi: Int) = """{"cmd":"create_display","w":$w,"h":$h,"dpi":$dpi}"""
    fun encodeDestroyDisplay() = """{"cmd":"destroy_display"}"""
    fun encodeFrame(maxWidth: Int = DEFAULT_MAX_WIDTH, quality: Int = DEFAULT_QUALITY) =
        """{"cmd":"frame","maxWidth":$maxWidth,"quality":$quality}"""
    fun encodeLease() = """{"cmd":"lease"}"""
    fun encodeSetText(displayId: Int, nodeId: Int?, text: String, submit: Boolean) =
        """{"cmd":"set_text","displayId":$displayId""" + (nodeId?.let { ""","nodeId":$it""" } ?: "") +
            ""","text":${DumpCodec.jsonStr(text)},"submit":$submit}"""

    fun encodeCreateOk(displayId: Int, w: Int, h: Int) = """{"ok":true,"displayId":$displayId,"w":$w,"h":$h}"""
    fun encodeOk() = """{"ok":true}"""
    /** base64 字符集不含需转义字符，直接内嵌。 */
    fun encodeFrameOk(jpegBase64: String) = """{"ok":true,"jpegBase64":"$jpegBase64"}"""
    fun encodeFrameEmpty() = """{"ok":true,"empty":true}"""

    sealed class CreateResult {
        data class Ok(val displayId: Int, val w: Int, val h: Int) : CreateResult()
        data class Err(val message: String) : CreateResult()
    }

    fun parseCreateResponse(line: String): CreateResult {
        val s = line.trim()
        errorOf(s)?.let { return CreateResult.Err(it) }
        val id = DumpCodec.intField(s, "displayId") ?: return CreateResult.Err("missing displayId")
        val w = DumpCodec.intField(s, "w") ?: return CreateResult.Err("missing w")
        val h = DumpCodec.intField(s, "h") ?: return CreateResult.Err("missing h")
        return CreateResult.Ok(id, w, h)
    }

    /** 成功返回 null，否则错误文本。 */
    fun parseSimpleResponse(line: String): String? = errorOf(line.trim())

    sealed class FrameResult {
        data class Ok(val jpegBase64: String) : FrameResult()
        data object Empty : FrameResult()
        data class Err(val message: String) : FrameResult()
    }

    fun parseFrameResponse(line: String): FrameResult {
        val s = line.trim()
        errorOf(s)?.let { return FrameResult.Err(it) }
        if (DumpCodec.boolField(s, "empty") == true) return FrameResult.Empty
        val b64 = DumpCodec.strField(s, "jpegBase64") ?: return FrameResult.Err("missing jpegBase64")
        return FrameResult.Ok(b64)
    }

    /** `ok` 不为 true 时的错误文本；ok 为 true 返回 null。 */
    private fun errorOf(s: String): String? =
        if (DumpCodec.boolField(s, "ok") == true) null
        else DumpCodec.strField(s, "error") ?: "unparseable response: ${s.take(80)}"
}
