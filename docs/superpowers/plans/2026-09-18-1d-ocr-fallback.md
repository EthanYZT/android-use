# 1d OCR 兜底 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每步对全帧截图跑端侧 ML Kit 中文 OCR，把节点树里没有的文字作为 `ocr` 条目补进元素列表，模型可按 id 点/读。

**Architecture:** `ScreenCapture` 向守护进程要全分辨率帧，一次解码两用（720 宽给模型、原图给 OCR）。`TextReader` 接口隔离 ML Kit（`MlKitTextReader` 只在 App 进程），`OcrMerge` 纯逻辑做补洞（行中心落在带字节点框内则丢弃）并把保留行伪装成 `NodeRecord`（className=`ocr`，id 续编）追加到节点列表，下游解析/重定位/净化零改动。`MainActivity` 加 `--es task` 调试入口供 adb 驱动验收。

**Tech Stack:** Kotlin，ML Kit `text-recognition-chinese` 16.0.1（打包版，已在 gradle 缓存），JUnit4，真机 OnePlus Ace 5。

**Spec:** `docs/superpowers/specs/2026-09-18-1d-ocr-fallback-design.md`

## Global Constraints

- 单测：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --offline -q --tests '<类>'`
- 纯逻辑模块（`OcrMerge`）不得 import 任何 `android.*`；`OcrLine` 是纯 data class；`TextReader` 接口签名里的 `Bitmap` 用 `android.graphics.Bitmap`，接口本身放 Android 侧包 `com.androiduse.perception`，`OcrMerge` 放 `com.androiduse.agent`。
- OCR 文字进提示词只能经 `NodeGrounding.promptBlock` → `UntrustedText.field`（伪装成 NodeRecord 自动满足）。
- `OcrMerge.OCR_CLASS = "ocr"`，`OcrMerge.MAX_OCR_ENTRIES = 40`。
- 提交信息末尾 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。

---

### Task 1: 依赖 + `OcrLine` / `OcrMerge`（纯逻辑）

**Files:**
- Modify: `gradle/libs.versions.toml`（加 `mlkitTextChinese = "16.0.1"`、`mlkit-text-recognition-chinese`）
- Modify: `app/build.gradle.kts`（`implementation(libs.mlkit.text.recognition.chinese)`）
- Create: `app/src/main/java/com/androiduse/agent/OcrMerge.kt`（含 `OcrLine`）
- Test: `app/src/test/java/com/androiduse/agent/OcrMergeTest.kt`

**Interfaces:**
```kotlin
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
object OcrMerge {
    const val OCR_CLASS = "ocr"; const val MAX_OCR_ENTRIES = 40
    fun merge(nodes: List<NodeRecord>, lines: List<OcrLine>): List<NodeRecord>   // nodes + 补入的 ocr 条目
}
```

- [ ] Step 1 写失败测试（见 OcrMergeTest 内容：带字节点内丢弃 / 无字节点内保留 / 无节点全保留 / 空白与纯标点丢弃 / id 从 max+1 按 (top,left) 续编 / 40 条截断 / 退化 bounds 不算覆盖 / 空 lines 原样返回）
- [ ] Step 2 跑：`Unresolved reference 'OcrMerge'`
- [ ] Step 3 实现 `OcrMerge`
- [ ] Step 4 跑：8 绿
- [ ] Step 5 提交 `feat(1d): OcrMerge 补洞纯逻辑 + ML Kit 依赖`

### Task 2: 提示词与 grounding 认识 `ocr` 条目

**Files:** `NodeGrounding.kt`（promptBlock 打 ` ocr`）、`PromptBuilder.kt`（系统提示一句）、`NodeGroundingTest.kt`、`PromptBuilderTest.kt`

- [ ] 测试：`promptBlock_marksOcrEntries`（含 `" ocr text=\"…\""`、无 `click`）；`resolveTapId`/`relocate` 对 ocr 条目工作；`systemPromptExplainsOcrEntries`（含 "ocr" 与 "截图里识别"）
- [ ] 实现；跑绿；提交 `feat(1d): 提示词与 promptBlock 标记 ocr 条目`

### Task 3: `ScreenCapture.captureFrame` 一帧两用 + `TextReader` / `MlKitTextReader`

**Files:** `perception/ScreenCapture.kt`、`perception/TextReader.kt`（接口 + `MlKitTextReader` 单例对象）

```kotlin
data class Frame(val fullBitmap: Bitmap?, val jpegBase64: String)
fun ScreenCapture.captureFrame(screen, quality = 80, maxWidthPx = 720): Frame?   // 守护进程 frame(0, 85) → 解码 → 缩放编码
interface TextReader { fun read(bitmap: Bitmap): List<OcrLine>? }
object MlKitTextReader : TextReader   // ChineseTextRecognizerOptions，Tasks.await 3s，异常→null+Log.w
```
- [ ] 实现（Android 侧，无 JVM 单测）；`captureAsJpegBase64` 改为 `captureFrame(...)?.jpegBase64`；编译；提交 `feat(1d): 一帧两用 + MlKitTextReader`

### Task 4: 接线（`Observation.ocrCount`、`AndroidEnvironment`、进度行、`AgentCli`、`MainActivity` + intent extra）

**Files:** `agent/Transcript.kt`（Observation 加 `ocrCount: Int = 0`）、`agent/AgentLoop.kt:74`（进度行 ` ocr+N` / ` ocr失败`）、`AndroidEnvironment.kt`（构造加 `textReader: TextReader?`，observe 合并）、`daemon/AgentCli.kt`（传 null）、`MainActivity.kt`（传 `MlKitTextReader`；`onCreate`/`onNewIntent` 读 `intent.getStringExtra("task")` 非空则填入并 `startTask()`）、`AgentLoopTest.kt`（FakeEnv Observation ocrCount=2 → onProgress 行含 `ocr+2`）

- [ ] 测试红 → 实现 → 全量绿 → 编译安装 → 提交 `feat(1d): OCR 接入观察链路；MainActivity 支持 --es task 调试入口`

### Task 5: 真机验收

- [ ] 推图：`adb push <scratchpad>/probe_1_settings.png /sdcard/Pictures/aud_ocr_test.png` + `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/aud_ocr_test.png`
- [ ] `su root am start -n com.androiduse/.MainActivity --es task "打开相册，看最新一张图片，读出图里 WLAN 连接的名称"`；等结束后 `su root cat` 最新 transcript，检查 outcome 含"爱学A"、看图那步 nodesBlock 含 ` ocr `
- [ ] 不回归：同样入口跑「打开显示与亮度」「打开时钟App」，检查完成与设置首页步的 ocr 条目数 ≤ 3
- [ ] 结果写进 spec §7

### Task 6: 文档与记忆
- [ ] stage1 spec §0 表 1d 行 ✅；DESIGN §5.3 加状态行；记忆更新；提交 `docs(1d): 实施结果`
