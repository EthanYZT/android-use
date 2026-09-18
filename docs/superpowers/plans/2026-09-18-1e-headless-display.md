# 1e 真 headless 虚拟屏 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把虚拟屏从可见的 `overlay_display_devices` 换成 root 守护进程持有的不可见 TRUSTED 虚拟屏，截图改从守护进程取帧，销屏不再把任务顶到物理屏。

**Architecture:** 守护进程（`app_process` root，已有）新增 `DisplayHost` 用 `DisplayManager.createVirtualDisplay` 建屏并持有 ImageReader 的最近一帧；LocalSocket 协议在现有 `dump` 之外加 `create_display` / `destroy_display` / `frame` / `lease` 四个命令（新 `DaemonProtocol`，`DumpCodec` 保留只管 dump 与节点）。App 侧 `DaemonClient` 扩成 v2 客户端，`ScreenSession` 状态机抽成可单测的 `ScreenSessionCore`，`ScreenCapture` 改为要帧，删掉 `VirtualDisplayManager` / `DisplayParser` / `screencap` / `/data/local/tmp` 整条链路。

**Tech Stack:** Kotlin，Android 16 hidden API（反射取 `ActivityThread.systemMain` 系统 Context），`android.media.ImageReader`，`android.net.LocalSocket`，JUnit4 JVM 单测，真机 OnePlus Ace 5（KernelSU root）。

**Spec:** `docs/superpowers/specs/2026-09-18-1e-headless-display-design.md`

## Global Constraints

- 单测命令（必须用 Android Studio 的 JBR）：
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --offline -q --tests '<类全名>'`
- JVM 单测里 `org.json` 是桩实现会抛异常：协议编解码一律手写字符串，沿用 `DumpCodec` 的 `strField` / `intField` / `boolField` / `jsonStr`（都是 `internal`，同模块可用）。
- 守护进程侧任何不可信字符串不得拼进 shell；`am start` 等一律 `RootShell.execArgv`（App 侧）或 `ProcessBuilder(argv)`（守护进程侧，已是 root，不需要 su）。
- 虚拟屏 flags 固定 `0x45c9`（PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL|TRUSTED|OWN_FOCUS），不加 SHOULD_SHOW_SYSTEM_DECORATIONS。
- socket 名升为 `androiduse_daemon_v2`。
- `Action.Home` 继续拒绝（`ActionCommand.toShell(Home) == null`），不改。
- 真机验证前先 `adb shell dumpsys window | grep isKeyguardShowing` 确认已解锁；APK 路径用 `adb shell pm path com.androiduse`。
- 提交信息末尾加 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。
- 与 spec 的一处偏差（已记入 Task 8 文档更新）：不把 `DumpCodec` 改名为 `DaemonCodec`——`DumpCodec.NodeRecord` 被 10+ 个文件引用，改名只有噪音。新命令放在新对象 `DaemonProtocol`。

---

### Task 1: `DaemonProtocol` —— v2 协议编解码（纯逻辑）

**Files:**
- Create: `app/src/main/java/com/androiduse/daemon/DaemonProtocol.kt`
- Test: `app/src/test/java/com/androiduse/daemon/DaemonProtocolTest.kt`

**Interfaces:**
- Consumes: `DumpCodec.strField/intField/boolField/jsonStr/encodeError/parseRequest/encodeRequest`（已存在）。
- Produces（后续 Task 2–6 依赖，名字与签名以此为准）:
  ```kotlin
  object DaemonProtocol {
      sealed class Request {
          data class CreateDisplay(val w: Int, val h: Int, val dpi: Int) : Request()
          data object DestroyDisplay : Request()
          data class Frame(val maxWidth: Int, val quality: Int) : Request()
          data class Dump(val displayId: Int) : Request()
          data object Lease : Request()
      }
      const val DEFAULT_MAX_WIDTH = 720
      const val DEFAULT_QUALITY = 80
      fun parseRequest(line: String): Request?
      fun encodeCreateDisplay(w: Int, h: Int, dpi: Int): String
      fun encodeDestroyDisplay(): String
      fun encodeFrame(maxWidth: Int = DEFAULT_MAX_WIDTH, quality: Int = DEFAULT_QUALITY): String
      fun encodeLease(): String
      fun encodeCreateOk(displayId: Int, w: Int, h: Int): String
      fun encodeOk(): String
      fun encodeFrameOk(jpegBase64: String): String
      fun encodeFrameEmpty(): String
      sealed class CreateResult { data class Ok(val displayId: Int, val w: Int, val h: Int) : CreateResult(); data class Err(val message: String) : CreateResult() }
      fun parseCreateResponse(line: String): CreateResult
      /** ok → null；否则错误文本 */
      fun parseSimpleResponse(line: String): String?
      sealed class FrameResult { data class Ok(val jpegBase64: String) : FrameResult(); data object Empty : FrameResult(); data class Err(val message: String) : FrameResult() }
      fun parseFrameResponse(line: String): FrameResult
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
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
        assertEquals(DumpCodec.encodeRequest(DumpCodec.DumpRequest(9)), """{"cmd":"dump","displayId":9}""")
        assertEquals(DaemonProtocol.Request.Dump(9), DaemonProtocol.parseRequest(DumpCodec.encodeRequest(DumpCodec.DumpRequest(9))))
    }

    @Test
    fun createResponseRoundTrip() {
        assertEquals(DaemonProtocol.CreateResult.Ok(9, 1080, 2376),
            DaemonProtocol.parseCreateResponse(DaemonProtocol.encodeCreateOk(9, 1080, 2376)))
        val err = DaemonProtocol.parseCreateResponse(DumpCodec.encodeError("boom"))
        assertEquals(DaemonProtocol.CreateResult.Err("boom"), err)
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --offline -q --tests 'com.androiduse.daemon.DaemonProtocolTest' 2>&1 | grep -E "^e: " | head`
Expected: `Unresolved reference 'DaemonProtocol'`

- [ ] **Step 3: 最小实现**

```kotlin
package com.androiduse.daemon

/**
 * 守护进程协议 v2：一行 JSON 请求 / 一行 JSON 响应，按 `cmd` 分派。
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
            else -> null
        }
    }

    fun encodeCreateDisplay(w: Int, h: Int, dpi: Int) = """{"cmd":"create_display","w":$w,"h":$h,"dpi":$dpi}"""
    fun encodeDestroyDisplay() = """{"cmd":"destroy_display"}"""
    fun encodeFrame(maxWidth: Int = DEFAULT_MAX_WIDTH, quality: Int = DEFAULT_QUALITY) =
        """{"cmd":"frame","maxWidth":$maxWidth,"quality":$quality}"""
    fun encodeLease() = """{"cmd":"lease"}"""

    fun encodeCreateOk(displayId: Int, w: Int, h: Int) = """{"ok":true,"displayId":$displayId,"w":$w,"h":$h}"""
    fun encodeOk() = """{"ok":true}"""
    fun encodeFrameOk(jpegBase64: String) = """{"ok":true,"jpegBase64":"$jpegBase64"}""" // base64 无需转义
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 的命令，再看 `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.androiduse.daemon.DaemonProtocolTest.xml`
Expected: `tests="8" skipped="0" failures="0"`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/androiduse/daemon/DaemonProtocol.kt app/src/test/java/com/androiduse/daemon/DaemonProtocolTest.kt
git commit -m "feat(1e): 守护进程协议 v2 编解码 (create_display/destroy_display/frame/lease)"
```

---

### Task 2: `FrameGeometry` —— 帧几何纯函数

**Files:**
- Create: `app/src/main/java/com/androiduse/daemon/FrameGeometry.kt`
- Test: `app/src/test/java/com/androiduse/daemon/FrameGeometryTest.kt`

**Interfaces:**
- Produces（Task 4 `DisplayHost` 依赖）:
  ```kotlin
  object FrameGeometry {
      /** ImageReader 平面的 rowStride/pixelStride → 承载整行的 Bitmap 宽（含 padding 列）。 */
      fun paddedWidth(rowStride: Int, pixelStride: Int): Int
      /** 是否需要裁掉右侧 padding。 */
      fun needsCrop(rowStride: Int, pixelStride: Int, imageWidth: Int): Boolean
      /** 缩放到 maxWidth 后的 (w,h)；不放大；maxWidth<=0 视为不缩放。 */
      fun scaledSize(w: Int, h: Int, maxWidth: Int): Pair<Int, Int>
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
package com.androiduse.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImageReader RGBA 平面常带行尾 padding（rowStride > width*4），裁剪与缩放的算术在这里单测。 */
class FrameGeometryTest {

    @Test
    fun paddedWidthIsRowStrideOverPixelStride() {
        assertEquals(1088, FrameGeometry.paddedWidth(4352, 4))
        assertEquals(1080, FrameGeometry.paddedWidth(4320, 4))
    }

    @Test
    fun needsCropOnlyWhenPadded() {
        assertTrue(FrameGeometry.needsCrop(4352, 4, 1080))
        assertFalse(FrameGeometry.needsCrop(4320, 4, 1080))
    }

    @Test
    fun scaledSizeKeepsAspectAndNeverUpscales() {
        assertEquals(720 to 1584, FrameGeometry.scaledSize(1080, 2376, 720))
        assertEquals(1080 to 2376, FrameGeometry.scaledSize(1080, 2376, 4000))
        assertEquals(1080 to 2376, FrameGeometry.scaledSize(1080, 2376, 0))
    }

    @Test
    fun scaledHeightIsAtLeastOne() {
        assertEquals(10 to 1, FrameGeometry.scaledSize(1000, 1, 10))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... --tests 'com.androiduse.daemon.FrameGeometryTest' 2>&1 | grep -E "^e: " | head`
Expected: `Unresolved reference 'FrameGeometry'`

- [ ] **Step 3: 最小实现**

```kotlin
package com.androiduse.daemon

/** ImageReader 帧 → Bitmap 的几何算术。纯函数，DisplayHost 用；Android 依赖留在 DisplayHost。 */
object FrameGeometry {
    fun paddedWidth(rowStride: Int, pixelStride: Int): Int = rowStride / pixelStride

    fun needsCrop(rowStride: Int, pixelStride: Int, imageWidth: Int): Boolean =
        paddedWidth(rowStride, pixelStride) != imageWidth

    fun scaledSize(w: Int, h: Int, maxWidth: Int): Pair<Int, Int> {
        if (maxWidth <= 0 || w <= maxWidth) return w to h
        val ratio = maxWidth.toDouble() / w
        return maxWidth to (h * ratio).toInt().coerceAtLeast(1)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Expected: `tests="4" ... failures="0"`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/androiduse/daemon/FrameGeometry.kt app/src/test/java/com/androiduse/daemon/FrameGeometryTest.kt
git commit -m "feat(1e): FrameGeometry 帧裁剪/缩放纯函数"
```

---

### Task 3: `LeaseRegistry` —— 租约代际（纯逻辑）

**Files:**
- Create: `app/src/main/java/com/androiduse/daemon/LeaseRegistry.kt`
- Test: `app/src/test/java/com/androiduse/daemon/LeaseRegistryTest.kt`

**Interfaces:**
- Produces（Task 4 `Daemon` 依赖）:
  ```kotlin
  class LeaseRegistry {
      /** 新租约替换旧租约，返回本租约的代际号。 */
      @Synchronized fun open(): Long
      /** 某租约的连接结束。只有它仍是当前租约时返回 true（调用方据此销屏）。 */
      @Synchronized fun close(token: Long): Boolean
      @Synchronized fun hasLease(): Boolean
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
package com.androiduse.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同一时间最多一份租约；被替换的旧租约 EOF 不销屏，只有当前租约 EOF 才销屏。 */
class LeaseRegistryTest {

    @Test
    fun currentLeaseCloseTriggersDestroy() {
        val r = LeaseRegistry()
        val t = r.open()
        assertTrue(r.hasLease())
        assertTrue(r.close(t))
        assertFalse(r.hasLease())
    }

    @Test
    fun replacedLeaseCloseDoesNotTriggerDestroy() {
        val r = LeaseRegistry()
        val old = r.open()
        val new = r.open()
        assertFalse("旧租约 EOF 不能销掉新租约持有的屏", r.close(old))
        assertTrue(r.hasLease())
        assertTrue(r.close(new))
        assertFalse(r.hasLease())
    }

    @Test
    fun closingTwiceIsIdempotent() {
        val r = LeaseRegistry()
        val t = r.open()
        assertTrue(r.close(t))
        assertFalse(r.close(t))
    }

    @Test
    fun noLeaseInitially() {
        assertFalse(LeaseRegistry().hasLease())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Expected: `Unresolved reference 'LeaseRegistry'`

- [ ] **Step 3: 最小实现**

```kotlin
package com.androiduse.daemon

/**
 * 屏的租约：App 建屏后开一条 LocalSocket 连接一直不关，守护进程读到 EOF 即销屏。
 * 同一时间最多一份；第二份到来时替换第一份，旧连接之后的 EOF 不能误销新租约的屏——用代际号区分。
 */
class LeaseRegistry {
    private var current: Long = 0L
    private var next: Long = 1L

    @Synchronized fun open(): Long { current = next++; return current }

    @Synchronized fun close(token: Long): Boolean {
        if (token != current || current == 0L) return false
        current = 0L
        return true
    }

    @Synchronized fun hasLease(): Boolean = current != 0L
}
```

- [ ] **Step 4: 跑测试确认通过**

Expected: `tests="4" ... failures="0"`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/androiduse/daemon/LeaseRegistry.kt app/src/test/java/com/androiduse/daemon/LeaseRegistryTest.kt
git commit -m "feat(1e): LeaseRegistry 租约代际"
```

---

### Task 4: `DisplayHost` + `Daemon` v2 分派（守护进程侧，真机验证）

**Files:**
- Create: `app/src/main/java/com/androiduse/daemon/DisplayHost.kt`
- Modify: `app/src/main/java/com/androiduse/daemon/Daemon.kt`（`SOCKET_NAME`、`handle`、空闲规则、租约线程）
- Modify: `app/src/main/java/com/androiduse/daemon/DaemonCli.kt`（加子命令，作为本任务的真机验证工具）
- Modify: `app/src/main/java/com/androiduse/root/DaemonClient.kt`（只改 socket 名常量引用；v2 方法留到 Task 5）

**Interfaces:**
- Consumes: Task 1 `DaemonProtocol`，Task 2 `FrameGeometry`，Task 3 `LeaseRegistry`，已有 `UiAutomationFactory` / `NodeExtractor` / `DumpCodec`。
- Produces:
  ```kotlin
  object DisplayHost {
      const val FLAGS = 0x45c9
      /** 幂等：已有同尺寸屏返回其 id；尺寸不同先销后建。失败抛异常。 */
      @Synchronized fun create(w: Int, h: Int, dpi: Int): Int
      @Synchronized fun destroy()
      @Synchronized fun hasDisplay(): Boolean
      /** 最近一帧压成 JPEG；没有帧返回 null。 */
      @Synchronized fun encodeJpeg(maxWidth: Int, quality: Int): ByteArray?
  }
  ```
  Daemon 对 `Request.Lease` 的行为：回 `encodeOk()` 后不关连接，起线程阻塞读到 EOF → `leases.close(token)` 为 true 时 `DisplayHost.destroy()`。

- [ ] **Step 1: 写 `DisplayHost`**（Android 依赖，无 JVM 单测；真机用 DaemonCli 验证）

```kotlin
package com.androiduse.daemon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * 守护进程持有的不可见虚拟屏（spec 1e §4.1）。
 *
 * 建屏：root `app_process` 里 `ActivityThread.systemMain().getSystemContext()` 拿 DisplayManager，
 * `createVirtualDisplay` 到一个 ImageReader 的 Surface。flags 见 [FLAGS]（2026-09-18 spike 真机验证）：
 * TRUSTED 让它接受注入与 Activity 启动；OWN_CONTENT_ONLY 空屏不镜像物理屏；
 * DESTROY_CONTENT_ON_REMOVAL 销屏时任务被销毁而不是搬到物理屏。root uid 0 过 DMS 的包名与权限校验。
 *
 * 取帧：界面静止时 `acquireLatestImage` 返回 null（spike 实测），所以在回调线程里始终**保留最近一帧**，
 * 新帧到就替换并关闭旧帧。maxImages=3 保证我们持有一帧时生产者还有缓冲。
 */
object DisplayHost {

    const val FLAGS = 0x45c9 // PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL|TRUSTED|OWN_FOCUS
    private const val NAME = "androiduse-headless"

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var w = 0; private var h = 0
    private val cbThread by lazy { HandlerThread("aud-frames").apply { start() } }
    private val cbHandler by lazy { Handler(cbThread.looper) }

    @Synchronized fun hasDisplay(): Boolean = vd != null

    @Synchronized fun create(w: Int, h: Int, dpi: Int): Int {
        vd?.let { existing ->
            if (this.w == w && this.h == h) return existing.display.displayId
            destroy()
        }
        val ctx = systemContext()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            synchronized(this) {
                if (reader !== rd) { img.close(); return@synchronized } // 已销屏
                latest?.close(); latest = img
            }
        }, cbHandler)
        val created = dm.createVirtualDisplay(NAME, w, h, dpi, r.surface, FLAGS, null, cbHandler)
        vd = created; reader = r; this.w = w; this.h = h
        return created.display.displayId
    }

    @Synchronized fun destroy() {
        latest?.close(); latest = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        w = 0; h = 0
    }

    @Synchronized fun encodeJpeg(maxWidth: Int, quality: Int): ByteArray? {
        val img = latest ?: return null
        val plane = img.planes[0]
        val padded = FrameGeometry.paddedWidth(plane.rowStride, plane.pixelStride)
        val full = Bitmap.createBitmap(padded, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (FrameGeometry.needsCrop(plane.rowStride, plane.pixelStride, img.width))
            Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
        val (sw, sh) = FrameGeometry.scaledSize(cropped.width, cropped.height, maxWidth)
        val scaled = if (sw != cropped.width) Bitmap.createScaledBitmap(cropped, sw, sh, true) else cropped
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        full.recycle()
        return out.toByteArray()
    }

    /** 裸 app_process 没有应用 Context；与 AgentCli 取 PackageManager 同路。 */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }
}
```

- [ ] **Step 2: 改 `Daemon`**

把 `Daemon.kt` 里的 `SOCKET_NAME`、`handle`、`startIdleWatchdog` 改成下面这样（其余保持）：

```kotlin
    const val SOCKET_NAME = "androiduse_daemon_v2"
    private val leases = LeaseRegistry()
```

`handle(client)` 整体替换为：

```kotlin
    /** 返回 true 表示连接已被租约线程接管，主循环不要关闭它。 */
    private fun handle(client: LocalSocket): Boolean {
        val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
        val line = reader.readLine() ?: return false
        val req = DaemonProtocol.parseRequest(line)
        val out = client.outputStream
        fun reply(s: String) { out.write((s + "\n").toByteArray(StandardCharsets.UTF_8)); out.flush() }
        when (req) {
            null -> reply(DumpCodec.encodeError("bad request: ${line.take(80)}"))
            is DaemonProtocol.Request.Dump -> reply(runDump(DumpCodec.DumpRequest(req.displayId)))
            is DaemonProtocol.Request.CreateDisplay -> reply(try {
                val id = DisplayHost.create(req.w, req.h, req.dpi)
                log("display created id=$id ${req.w}x${req.h}")
                DaemonProtocol.encodeCreateOk(id, req.w, req.h)
            } catch (e: Throwable) { DumpCodec.encodeError("create failed: $e") })
            DaemonProtocol.Request.DestroyDisplay -> { DisplayHost.destroy(); log("display destroyed (rpc)"); reply(DaemonProtocol.encodeOk()) }
            is DaemonProtocol.Request.Frame -> reply(try {
                val jpeg = DisplayHost.encodeJpeg(req.maxWidth, req.quality)
                if (jpeg == null) DaemonProtocol.encodeFrameEmpty()
                else DaemonProtocol.encodeFrameOk(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
            } catch (e: Throwable) { DumpCodec.encodeError("frame failed: $e") })
            DaemonProtocol.Request.Lease -> {
                val token = leases.open()
                reply(DaemonProtocol.encodeOk())
                Thread {
                    try { while (client.inputStream.read() >= 0) { /* 租约期间不期待任何数据 */ } } catch (_: Throwable) {}
                    if (leases.close(token)) { log("lease $token EOF, destroying display"); DisplayHost.destroy() }
                    else log("stale lease $token EOF, ignored")
                    try { client.close() } catch (_: Throwable) {}
                    lastActivity.set(System.currentTimeMillis())
                }.apply { isDaemon = true; name = "aud-lease-$token"; start() }
                return true
            }
        }
        return false
    }
```

主循环里对应改为：

```kotlin
            var handedOff = false
            try {
                handedOff = handle(client)
            } catch (e: Throwable) {
                log("handle error: $e")
            } finally {
                if (!handedOff) try { client.close() } catch (_: Throwable) {}
            }
```

空闲看门狗条件改为：

```kotlin
                val idle = System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS
                if (idle && !DisplayHost.hasDisplay() && !leases.hasLease()) {
                    log("idle ${IDLE_TIMEOUT_MS}ms with no display/lease, exiting")
                    resetConnection()
                    System.exit(0)
                }
```

`Daemon` 顶部文档注释加一段：`v2：守护进程还持有不可见虚拟屏（DisplayHost）；有屏或有租约时不自杀。`

- [ ] **Step 3: 扩 `DaemonCli` 成验证工具**

`DaemonCli.main` 整体替换为（`DaemonClient` 此时还没有 v2 方法，CLI 先自己开 socket 走裸协议，Task 5 再收编）：

```kotlin
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) { println("usage: DaemonCli <apkPath> dump <displayId> | create | frame [out.jpg] | destroy | lease <seconds>"); return }
        DaemonClient.apkPath = args[0]
        when (args[1]) {
            "dump" -> {
                val displayId = args.getOrNull(2)?.toIntOrNull() ?: run { println("bad displayId"); return }
                when (val r = DaemonClient.dump(displayId)) {
                    is DumpCodec.DumpResult.Ok -> {
                        println("OK display=${r.displayId} nodes=${r.nodes.size}")
                        r.nodes.take(40).forEach { n ->
                            println("  #${n.id} [${n.left},${n.top},${n.right},${n.bottom}]" +
                                (if (n.clickable) " CLICK" else "") +
                                (if (n.resId.isNotEmpty()) " id=${n.resId}" else "") +
                                (if (n.text.isNotEmpty()) " text=\"${n.text}\"" else "") +
                                (if (n.desc.isNotEmpty()) " desc=\"${n.desc}\"" else ""))
                        }
                    }
                    is DumpCodec.DumpResult.Err -> println("ERR ${r.message}")
                }
            }
            "create" -> println(DaemonClient.rawRequest(DaemonProtocol.encodeCreateDisplay(1080, 2376, 480)))
            "destroy" -> println(DaemonClient.rawRequest(DaemonProtocol.encodeDestroyDisplay()))
            "frame" -> {
                val line = DaemonClient.rawRequest(DaemonProtocol.encodeFrame()) ?: run { println("no response"); return }
                when (val f = DaemonProtocol.parseFrameResponse(line)) {
                    is DaemonProtocol.FrameResult.Ok -> {
                        val bytes = android.util.Base64.decode(f.jpegBase64, android.util.Base64.NO_WRAP)
                        val out = args.getOrNull(2) ?: "/data/local/tmp/daemoncli_frame.jpg"
                        java.io.File(out).writeBytes(bytes)
                        println("FRAME ${bytes.size} bytes -> $out")
                    }
                    DaemonProtocol.FrameResult.Empty -> println("EMPTY (no frame yet)")
                    is DaemonProtocol.FrameResult.Err -> println("ERR ${f.message}")
                }
            }
            "lease" -> {
                val secs = args.getOrNull(2)?.toIntOrNull() ?: 10
                val s = DaemonClient.openRawSocket() ?: run { println("cannot connect"); return }
                s.outputStream.write((DaemonProtocol.encodeLease() + "\n").toByteArray()); s.outputStream.flush()
                println("lease reply: " + s.inputStream.bufferedReader().readLine())
                println("holding lease ${secs}s then exiting (daemon should destroy display)")
                Thread.sleep(secs * 1000L)
            }
            else -> println("unknown subcommand ${args[1]}")
        }
        System.exit(0)
    }
```

在 `DaemonClient` 里加两个最小工具（Task 5 会在此基础上写正式 v2 方法）：

```kotlin
    /** 连上（必要时拉起）守护进程并返回裸 socket；连不上返回 null。 */
    fun openRawSocket(): LocalSocket? {
        tryConnect()?.let { return it }
        Log.i(TAG, "daemon not up, launching")
        ensureStarted()
        return connectWithBackoff()
    }

    /** 发一行、收一行。连不上/IO 失败返回 null。 */
    fun rawRequest(line: String): String? {
        val s = openRawSocket() ?: return null
        return s.use {
            try {
                it.outputStream.write((line + "\n").toByteArray(StandardCharsets.UTF_8)); it.outputStream.flush()
                BufferedReader(InputStreamReader(it.inputStream, StandardCharsets.UTF_8)).readLine()
            } catch (e: Exception) { null }
        }
    }
```

并把 `dump()` 里 "先直接试连…连不上再拉起" 的三行改为 `val socket = openRawSocket()`。

- [ ] **Step 4: 编译 + 全量单测 + 安装**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest :app:assembleDebug --offline -q 2>&1 | grep -E "^e: |FAILED" ; adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: 无 `e:`，`Success`。

- [ ] **Step 5: 真机验证 create → 起设置 → frame → dump → lease EOF 销屏**

```bash
APK=$(adb shell pm path com.androiduse | sed 's/package://' | tr -d '\r')
adb shell "su root pkill -f com.androiduse.daemon.Daemon" ; sleep 1
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK create"
# 期望：{"ok":true,"displayId":N,"w":1080,"h":2376}
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK frame"
# 期望：EMPTY (no frame yet)
adb shell "su root am start --display <N> -n com.android.settings/.Settings -f 0x18000000" ; sleep 2
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK frame /data/local/tmp/f1.jpg"
# 期望：FRAME <几十KB> bytes；adb pull 看图是设置页
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK frame /data/local/tmp/f2.jpg"
# 期望：界面静止仍能拿到帧（保留最近一帧生效）
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK dump <N>"
# 期望：OK nodes=3x
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.DaemonCli $APK lease 5"
# CLI 5 秒后退出 → 守护进程日志出现 "lease 1 EOF, destroying display"
adb logcat -d -s AudDaemon | tail -5
adb shell dumpsys display | grep -c androiduse-headless   # 期望 0
adb shell dumpsys activity activities | grep -A2 "Display #0" | head -3   # 顶层未变
adb shell "su root rm -f /data/local/tmp/f1.jpg /data/local/tmp/f2.jpg"
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/androiduse/daemon/DisplayHost.kt app/src/main/java/com/androiduse/daemon/Daemon.kt app/src/main/java/com/androiduse/daemon/DaemonCli.kt app/src/main/java/com/androiduse/root/DaemonClient.kt
git commit -m "feat(1e): 守护进程持有不可见虚拟屏 DisplayHost + 协议 v2 分派 + 租约 (真机验证)"
```

---

### Task 5: `DaemonClient` v2 + `DisplayService` 接口

**Files:**
- Modify: `app/src/main/java/com/androiduse/root/DaemonClient.kt`
- Create: `app/src/main/java/com/androiduse/display/DisplayService.kt`

**Interfaces:**
- Consumes: Task 1 `DaemonProtocol`，Task 4 的 `openRawSocket` / `rawRequest`。
- Produces（Task 6 `ScreenSessionCore` 依赖）:
  ```kotlin
  package com.androiduse.display
  interface DisplayService {
      /** 开租约连接；返回可关闭句柄，null 表示连不上守护进程。 */
      fun openLease(): java.io.Closeable?
      fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult
      fun destroyDisplay(): Boolean
      fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult
      /** 在该屏起系统设置（过渡起点）。 */
      fun launchSettings(displayId: Int): Boolean
  }
  ```
  `DaemonClient` 实现它：`object DaemonClient : DisplayService`。

- [ ] **Step 1: 建接口文件**

```kotlin
package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import java.io.Closeable

/**
 * ScreenSession 与守护进程之间的边界。真机实现是 [com.androiduse.root.DaemonClient]，
 * 单测用假实现驱动 ScreenSessionCore 的状态机。
 */
interface DisplayService {
    fun openLease(): Closeable?
    fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult
    fun destroyDisplay(): Boolean
    fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult
    fun launchSettings(displayId: Int): Boolean
}
```

- [ ] **Step 2: `DaemonClient` 实现**

在 `DaemonClient` 上加 `: DisplayService`，新增：

```kotlin
    override fun openLease(): Closeable? {
        val s = openRawSocket() ?: return null
        return try {
            s.outputStream.write((DaemonProtocol.encodeLease() + "\n").toByteArray(StandardCharsets.UTF_8)); s.outputStream.flush()
            val line = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8)).readLine()
            if (line != null && DaemonProtocol.parseSimpleResponse(line) == null) Closeable { try { s.close() } catch (_: Exception) {} }
            else { s.close(); null }
        } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; null }
    }

    override fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult {
        val line = rawRequest(DaemonProtocol.encodeCreateDisplay(w, h, dpi)) ?: return DaemonProtocol.CreateResult.Err("daemon unreachable")
        return DaemonProtocol.parseCreateResponse(line)
    }

    override fun destroyDisplay(): Boolean {
        val line = rawRequest(DaemonProtocol.encodeDestroyDisplay()) ?: return false
        return DaemonProtocol.parseSimpleResponse(line) == null
    }

    override fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult {
        val line = rawRequest(DaemonProtocol.encodeFrame(maxWidth, quality)) ?: return DaemonProtocol.FrameResult.Err("daemon unreachable")
        return DaemonProtocol.parseFrameResponse(line)
    }

    override fun launchSettings(displayId: Int): Boolean =
        RootShell.execArgv(listOf("am", "start", "--display", displayId.toString(), "-a", "android.settings.SETTINGS")).ok
```

注意：租约 socket 上**不能**再发请求；`Closeable` 只负责 close。文档注释写明。

- [ ] **Step 3: 编译**

Run: `... ./gradlew :app:assembleDebug --offline -q 2>&1 | grep -E "^e: "`
Expected: 无输出。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/androiduse/root/DaemonClient.kt app/src/main/java/com/androiduse/display/DisplayService.kt
git commit -m "feat(1e): DaemonClient v2 客户端 + DisplayService 接口"
```

---

### Task 6: `ScreenSessionCore` 状态机 + `ScreenCapture` 改要帧 + 删 overlay 链路

**Files:**
- Create: `app/src/main/java/com/androiduse/display/ScreenSessionCore.kt`
- Test: `app/src/test/java/com/androiduse/display/ScreenSessionCoreTest.kt`
- Modify: `app/src/main/java/com/androiduse/ScreenSession.kt`（变薄：持一个 Core）
- Modify: `app/src/main/java/com/androiduse/display/VirtualDisplayManager.kt` → **删除**；`VirtualScreen` 迁到 `app/src/main/java/com/androiduse/display/VirtualScreen.kt`
- Delete: `app/src/main/java/com/androiduse/display/DisplayParser.kt`，`app/src/test/java/com/androiduse/display/DisplayParserTest.kt`
- Modify: `app/src/main/java/com/androiduse/perception/ScreenCapture.kt`
- Modify: `app/src/test/java/com/androiduse/actuation/InjectorTest.kt:15-20`、`app/src/test/java/com/androiduse/actuation/ActionCommandTest.kt:10-15`（去掉 `surfaceFlingerId`）
- Modify: `app/src/main/java/com/androiduse/SettingsActivity.kt:52`（日志里去掉 sfId）

**Interfaces:**
- Consumes: Task 5 `DisplayService`。
- Produces:
  ```kotlin
  data class VirtualScreen(val logicalDisplayId: Int, val widthPx: Int, val heightPx: Int)
  class ScreenSessionCore(private val service: DisplayService, private val settleMs: Long = 1500, private val sleep: (Long) -> Unit = Thread::sleep) {
      val screen: VirtualScreen?
      fun ensure(w: Int = 1080, h: Int = 2376, dpi: Int = 480): VirtualScreen?
      fun destroy(): Boolean
  }
  object ScreenCapture {
      /** 取一帧 JPEG base64；空屏返回黑帧；守护进程不可达返回 null。 */
      fun captureAsJpegBase64(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): String?
      fun capture(screen: VirtualScreen): Bitmap?
      var service: DisplayService  // 默认 DaemonClient
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** ScreenSession 的状态机：租约 → 建屏 → 起设置；失败回滚；探测失效重建。 */
class ScreenSessionCoreTest {

    private class Fake(
        var createOk: Boolean = true,
        var leaseOk: Boolean = true,
        var frameOk: Boolean = true,
    ) : DisplayService {
        val log = mutableListOf<String>()
        var leaseClosed = 0
        override fun openLease(): Closeable? { log += "lease"; return if (leaseOk) Closeable { leaseClosed++ } else null }
        override fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult {
            log += "create"; return if (createOk) DaemonProtocol.CreateResult.Ok(9, w, h) else DaemonProtocol.CreateResult.Err("nope")
        }
        override fun destroyDisplay(): Boolean { log += "destroy"; return true }
        override fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult =
            if (frameOk) DaemonProtocol.FrameResult.Empty else DaemonProtocol.FrameResult.Err("dead")
        override fun launchSettings(displayId: Int): Boolean { log += "settings$displayId"; return true }
    }

    private fun core(f: Fake) = ScreenSessionCore(f, settleMs = 0, sleep = {})

    @Test
    fun ensureOpensLeaseCreatesAndLaunchesSettingsInOrder() {
        val f = Fake(); val c = core(f)
        assertEquals(VirtualScreen(9, 1080, 2376), c.ensure())
        assertEquals(listOf("lease", "create", "settings9"), f.log)
    }

    @Test
    fun ensureIsIdempotentWhileDaemonAlive() {
        val f = Fake(); val c = core(f)
        val a = c.ensure(); val b = c.ensure()
        assertEquals(a, b)
        assertEquals(1, f.log.count { it == "create" })
    }

    @Test
    fun createFailureClosesLeaseAndReturnsNull() {
        val f = Fake(createOk = false); val c = core(f)
        assertNull(c.ensure())
        assertEquals(1, f.leaseClosed)
        assertNull(c.screen)
    }

    @Test
    fun leaseFailureReturnsNullWithoutCreating() {
        val f = Fake(leaseOk = false); val c = core(f)
        assertNull(c.ensure())
        assertFalse(f.log.contains("create"))
    }

    @Test
    fun destroyReleasesDisplayAndLease() {
        val f = Fake(); val c = core(f)
        c.ensure()
        assertTrue(c.destroy())
        assertNull(c.screen)
        assertEquals(1, f.leaseClosed)
        assertTrue(f.log.contains("destroy"))
    }

    @Test
    fun ensureAfterDaemonDeathRebuilds() {
        val f = Fake(); val c = core(f)
        c.ensure()
        f.frameOk = false          // 守护进程死了：探测失败
        val again = c.ensure()
        f.frameOk = true
        assertEquals(VirtualScreen(9, 1080, 2376), again)
        assertEquals("旧租约要关、屏要重建", 2, f.log.count { it == "create" })
        assertEquals(1, f.leaseClosed)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Expected: `Unresolved reference 'ScreenSessionCore'`（以及 `VirtualScreen` 三参构造不匹配）。

- [ ] **Step 3: 实现**

`app/src/main/java/com/androiduse/display/VirtualScreen.kt`：

```kotlin
package com.androiduse.display

/** 守护进程持有的不可见虚拟屏。逻辑 displayId 用于 `am start --display` / `input -d` / dump。 */
data class VirtualScreen(val logicalDisplayId: Int, val widthPx: Int, val heightPx: Int)
```

`app/src/main/java/com/androiduse/display/ScreenSessionCore.kt`：

```kotlin
package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import java.io.Closeable

/**
 * 虚拟屏会话状态机（spec 1e §4.2）。纯逻辑，Android 依赖都在 [DisplayService] 后面。
 *
 * ensure：租约 → 建屏 → 起设置（open_app 落地前的过渡起点，避免空黑屏）→ 记屏。
 * 中途失败关掉已开的租约、返回 null。已有屏时先用一次 frame 探测守护进程还活着：
 * 活着直接返回；死了（帧请求报错）说明屏已随进程消失，关旧租约后重建。
 * destroy：destroy_display + 关租约。
 */
class ScreenSessionCore(
    private val service: DisplayService,
    private val settleMs: Long = 1500,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    @Volatile var screen: VirtualScreen? = null
        private set
    private var lease: Closeable? = null

    @Synchronized
    fun ensure(w: Int = 1080, h: Int = 2376, dpi: Int = 480): VirtualScreen? {
        screen?.let { existing ->
            if (service.frame(1, 1) !is DaemonProtocol.FrameResult.Err) return existing
            closeLease(); screen = null
        }
        val l = service.openLease() ?: return null
        val created = service.createDisplay(w, h, dpi)
        if (created !is DaemonProtocol.CreateResult.Ok) { l.close(); return null }
        lease = l
        val s = VirtualScreen(created.displayId, created.w, created.h)
        service.launchSettings(s.logicalDisplayId)
        sleep(settleMs)
        screen = s
        return s
    }

    @Synchronized
    fun destroy(): Boolean {
        val ok = service.destroyDisplay()
        closeLease()
        screen = null
        return ok
    }

    private fun closeLease() { try { lease?.close() } catch (_: Exception) {}; lease = null }
}
```

`ScreenSession.kt` 变薄：

```kotlin
package com.androiduse

import com.androiduse.display.ScreenSessionCore
import com.androiduse.display.VirtualScreen
import com.androiduse.root.DaemonClient

/**
 * 进程内唯一的虚拟屏会话（真机接线）。状态机见 [ScreenSessionCore]。
 * 生命周期：主屏执行任务时若无屏则建屏；任务结束保留屏以便连续任务；MainActivity 真正结束时销毁。
 * 即便没调到 destroy，App 进程死 → 租约 EOF → 守护进程自己销屏（不再有跨重启残留的全局 setting）。
 * 全部阻塞调用，必须在 IO 线程。
 */
object ScreenSession {
    private val core = ScreenSessionCore(DaemonClient)
    val screen: VirtualScreen? get() = core.screen
    fun ensure(): VirtualScreen? = core.ensure()
    fun destroy(): Boolean = core.destroy()
}
```

`ScreenCapture.kt` 整体替换：

```kotlin
package com.androiduse.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.androiduse.daemon.DaemonProtocol
import com.androiduse.display.DisplayService
import com.androiduse.display.VirtualScreen
import com.androiduse.root.DaemonClient
import java.io.ByteArrayOutputStream

/**
 * 截图 = 向守护进程要最近一帧 JPEG（spec 1e §4.2）。不再有 screencap、/data/local/tmp 中转、
 * SurfaceFlinger id：帧只在两个进程的内存之间走 LocalSocket，不落盘。
 *
 * 空屏（没有 App 渲染过任何帧）返回一张同尺寸**纯黑** JPEG——黑屏是空屏的真实状态，模型应该
 * 看到它；只有守护进程不可达/出错才返回 null（真正的截图失败）。
 */
object ScreenCapture {

    /** 可替换以便测试；真机是 DaemonClient。 */
    @Volatile var service: DisplayService = DaemonClient

    fun captureAsJpegBase64(screen: VirtualScreen, quality: Int = 80, maxWidthPx: Int = 720): String? =
        when (val f = service.frame(maxWidthPx, quality)) {
            is DaemonProtocol.FrameResult.Ok -> f.jpegBase64
            DaemonProtocol.FrameResult.Empty -> blackJpegBase64(screen, quality, maxWidthPx)
            is DaemonProtocol.FrameResult.Err -> null
        }

    /** 设置页调试用：解码成 Bitmap。 */
    fun capture(screen: VirtualScreen): Bitmap? {
        val b64 = captureAsJpegBase64(screen, quality = 90, maxWidthPx = 0) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private var blackCache: Pair<Triple<Int, Int, Int>, String>? = null

    @Synchronized
    private fun blackJpegBase64(screen: VirtualScreen, quality: Int, maxWidthPx: Int): String {
        val (w, h) = com.androiduse.daemon.FrameGeometry.scaledSize(screen.widthPx, screen.heightPx, maxWidthPx)
        val key = Triple(w, h, quality)
        blackCache?.let { if (it.first == key) return it.second }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { eraseColor(Color.BLACK) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        val s = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        blackCache = key to s
        return s
    }
}
```

删除 `VirtualDisplayManager.kt`、`DisplayParser.kt`、`DisplayParserTest.kt`：

```bash
git rm app/src/main/java/com/androiduse/display/VirtualDisplayManager.kt app/src/main/java/com/androiduse/display/DisplayParser.kt app/src/test/java/com/androiduse/display/DisplayParserTest.kt
```

`InjectorTest.kt` / `ActionCommandTest.kt` 里的 `screen` 改为：

```kotlin
    private val screen = VirtualScreen(logicalDisplayId = 3, widthPx = 1080, heightPx = 2376)
```

`SettingsActivity.kt:52` 那行改为：`else -> "建屏成功 logicalId=${s.logicalDisplayId}（不可见虚拟屏），已打开设置"`。

`MainActivity.kt:46` 删掉 `ScreenCapture.cacheDir = cacheDir`（该属性已不存在）。

- [ ] **Step 4: 全量单测 + 编译**

Run: `... ./gradlew :app:testDebugUnitTest :app:assembleDebug --offline -q 2>&1 | grep -E "^e: |FAILED"`
Expected: 无输出。`ScreenSessionCoreTest` 6 个通过；总数 = 之前 162 + 8 + 4 + 4 + 6 − DisplayParserTest 的数量。

- [ ] **Step 5: 提交**

```bash
git add -A app/src
git commit -m "feat(1e): ScreenSessionCore 状态机 + ScreenCapture 改为向守护进程要帧；删除 overlay_display_devices/screencap/DisplayParser 链路"
```

---

### Task 7: `AgentCli` 接线 + 真机 E2E

**Files:**
- Modify: `app/src/main/java/com/androiduse/daemon/AgentCli.kt`

**Interfaces:**
- Consumes: Task 6 `ScreenSessionCore`、`DaemonClient`。

- [ ] **Step 1: 改 `AgentCli`**

把 `ScreenCapture.cacheDir = ...`、`VirtualDisplayManager.create()`、`launchIntentAction`、`Thread.sleep(2500)`、`finally { VirtualDisplayManager.destroy() }` 替换为：

```kotlin
        val session = ScreenSessionCore(DaemonClient)
        val screen = session.ensure()
        if (screen == null) { println("建屏失败（守护进程不可达或 create_display 失败）"); return }
        println("screen logicalId=${screen.logicalDisplayId} ${screen.widthPx}x${screen.heightPx} (headless)")
        ...
        } finally {
            session.destroy()
        }
```

（import `com.androiduse.display.ScreenSessionCore`，删掉 `VirtualDisplayManager`、`ScreenCapture`、`java.io.File` 中不再用的 import；`TranscriptStore` 的 `File` 仍要。）

- [ ] **Step 2: 编译安装**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug --offline -q 2>&1 | grep -E "^e: " ; adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "su root pkill -f com.androiduse.daemon.Daemon"
```

- [ ] **Step 3: 真机 E2E 三任务，每个前后记物理屏状态**

```bash
APK=$(adb shell pm path com.androiduse | sed 's/package://' | tr -d '\r')
snap() { adb shell dumpsys activity activities | grep -A1 "Display #0" | grep -o "topResumedActivity=ActivityRecord{[^ ]* [^ ]* [^ ]*" ; adb shell dumpsys display | grep -c androiduse-headless; }
snap
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.AgentCli $APK 6 打开显示与亮度" | grep -E "^#|RESULT|screen"
snap   # 顶层不变；headless 计数回到 0（AgentCli 结束销屏）
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.AgentCli $APK 6 打开时钟App" | grep -E "^#|RESULT"
snap
adb shell "su root env CLASSPATH=$APK app_process /system/bin com.androiduse.daemon.AgentCli $APK 8 打开日历，告诉我今天是几月几号，然后再打开计算器" | grep -E "^#|RESULT"
snap
adb shell dumpsys activity activities | grep -E "Display #|\* Task\{" | grep -E "alarmclock|calendar|calculator|settings" | head
# 期望：这些包在 Display #0 下的任务数与三次任务之前一致（虚拟屏上的任务被销毁，没有搬过来）
```

Expected：三个任务 `finished=true`；每次 `snap` 的顶层 Activity 相同；headless 计数任务中为 1、结束为 0；物理屏全程无悬浮窗（肉眼或 `screencap -p` 抽查一次）。

- [ ] **Step 4: App 主屏路径 + 杀进程租约验证**

在手机上打开本 App，主屏输入「打开显示与亮度」执行（走 `ScreenSession` + `AndroidEnvironment`）。完成后：

```bash
adb shell dumpsys display | grep -c androiduse-headless   # 1（任务结束保留屏）
adb shell am force-stop com.androiduse
sleep 2
adb logcat -d -s AudDaemon | grep -E "lease .* EOF|destroy" | tail -3   # 出现 "lease N EOF, destroying display"
adb shell dumpsys display | grep -c androiduse-headless   # 0
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/androiduse/daemon/AgentCli.kt
git commit -m "feat(1e): AgentCli 走守护进程建屏；真机 E2E 三任务通过，销屏不再顶到物理屏"
```

---

### Task 8: 文档与记忆

**Files:**
- Modify: `docs/DESIGN.md:178-184`（§4.2 加"状态：2026-09-18 落地，见 1e spec"一行）
- Modify: `docs/superpowers/specs/2026-09-17-stage1-perception-design.md:16`（§0 表格 1e 行状态改"✅ 2026-09-18"）、§6 末尾"遗留问题"段补一句"已由 1e 解决"
- Modify: `docs/superpowers/specs/2026-09-18-1e-headless-display-design.md`（§4.1 "DumpCodec 改名为 DaemonCodec" 改为"新增 DaemonProtocol，DumpCodec 保留"；§7 追加"实施结果"小节：单测数、E2E 结果、真机观察）
- Modify: `/Users/ethan/.claude/projects/-Users-ethan-Desktop-01-Active-Projects-android-use/memory/stage0-done-next-stage1.md` 与 `MEMORY.md`（1e ✅，下一步 5 App 验收铺开、1d OCR）

- [ ] **Step 1: 改三份文档**（内容按上面逐条写，实施结果一节用 Task 7 的真实数字）

- [ ] **Step 2: 改记忆**：在 `stage0-done-next-stage1.md` 的 open_app 条目后加 `- **1e ✅（2026-09-18，真机 E2E）**：…`，包含 DisplayHost flags 0x45c9、协议 v2、租约、"HOME 仍泄漏"、"删除 overlay/screencap/DisplayParser"；`MEMORY.md` 索引行同步。

- [ ] **Step 3: 提交**

```bash
git add docs /Users/ethan/.claude/projects/-Users-ethan-Desktop-01-Active-Projects-android-use/memory 2>/dev/null; git add docs
git commit -m "docs(1e): 记录 headless 屏实施结果；DESIGN §4.2 与 stage1 spec 状态更新"
```

（记忆目录不在仓库内，只需写文件，不 `git add`。）
