# 1d：OCR 兜底（端侧 ML Kit 中文，自动补洞）设计

日期：2026-09-18。分支 `stage1-perception`。阶段 1 最后一个子项。
上游：`docs/DESIGN.md` §5.1（三级降级，②级裁区 OCR）、§5.3（端侧 OCR）、§8.7（隐私）；
`2026-09-17-stage1-perception-design.md` §2（不可信文本约定第 4 条）、§8 验收、§9 验收后改进。

## 1. 问题

节点树是有洞的：Canvas / WebView 自绘文字、图片里的文字、NAF 空容器（微信语音转文字、聊天页标题，
DESIGN §5.1）对无障碍不可见。现在遇到洞模型只能凭截图猜 x/y 坐标，验收里日历的视图切换图标就是这样点的
（一次点中，但没有文字可读、无法核对）。DESIGN 三级降级的②级（端侧 OCR）还没有落地。

## 2. 目标与验收

1. 节点树读不到的文字，通过端侧 OCR 自动进入元素列表，模型可以按 id 点它、读它。
2. 完全离线：模型打包进 APK，不依赖 Google 服务、不联网、不上传。
3. 节点树完整的页面**不灌水**：OCR 补入的条目接近零。
4. OCR 失败/超时不影响该步（退化为纯节点列表）。

**验收（真机）**：
- 把 spike 的设置页截图推进手机相册。任务「打开相册，看最新一张图片，读出图里 WLAN 连接的名称」：
  finish 摘要含"爱学A"，且看图那一步的元素列表里出现 `ocr` 条目。
- 不回归：设置首页 `ocr` 补入条目 ≤ 3；重跑「打开显示与亮度」「打开时钟」照常完成。

**非目标**：按节点 bounds 裁区 OCR（DESIGN §5.1 原文的②级形态——需要先知道哪个容器有洞，那是特定 App 的
选择器知识，归阶段 5 Skill 层）；给模型一个显式 `ocr` 工具（模型看不出哪些文字没进列表，实测遇到洞直接猜坐标，
决策记录见 §3）；OCR 结果拼成阅读顺序全文（§5.3 提到，本轮每行一个条目就够，读全文的需求出现再做）；
`AgentCli` 里跑 OCR（裸 app_process 加载不了 ML Kit，见 §4.3）。

## 3. 决策记录

- **触发策略：每步自动全帧 OCR，只补洞（方案 A，已选）。** 否决 B（模型按需调 `ocr` 工具：模型不知道
  哪些文字没进列表）、C（按节点 bounds 裁区：需要 App 特定的选择器知识，现在没有触发点）。
- **验收场景：相册里一张带文字的图片（已选）。** 否决 Chrome Canvas 网页（网络与页面都是变量）、
  微信聊天页/提取文字（§3.4 高风险 App，真实账号，隐私风险）。
- **引擎：ML Kit `text-recognition-chinese` 16.0.1 打包版**（DESIGN §5.3 既定；本机 gradle 缓存已有，
  离线可构建；手机虽有 GMS 但不依赖它）。APK 约 +15–20 MB。
- **OCR 条目伪装成 `NodeRecord`**（className=`ocr`，id 续编）而不是新类型：解析、批内重定位、
  `UntrustedText` 净化、`promptBlock` 全部零改动复用；模型不需要学新工具。

## 4. 方案

### 4.1 截图：一帧两用

`ScreenCapture` 改为向守护进程要**全分辨率**帧（`frame(maxWidth=0, quality=85)`，1080×2376 JPEG 约
150–250 KB，LocalSocket 本机传输可忽略），App 解码一次：
- 缩到 720 宽再压 JPEG q80 → base64 给模型（与现在完全一致的形态与大小）；
- 原图 Bitmap 给 OCR。
新接口：`ScreenCapture.captureFrame(screen): Frame?`，`Frame(fullBitmap: Bitmap?, jpegBase64: String)`。
空屏（守护进程 `empty`）→ `fullBitmap = null`、黑帧 base64；不可达 → null。旧的 `captureAsJpegBase64` 保留为
`captureFrame(...)?.jpegBase64`。

### 4.2 OCR 与补洞（纯逻辑 + 一个 Android 实现）

```kotlin
/** 一行 OCR 文字，像素框（与屏幕同坐标系）。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** 端侧文字识别边界；真机实现 MlKitTextReader，CLI/单测用空实现或假实现。 */
interface TextReader { fun read(bitmap: android.graphics.Bitmap): List<OcrLine>? }

object OcrMerge {
    const val MAX_OCR_ENTRIES = 40
    const val OCR_CLASS = "ocr"
    /** 补洞：行中心落在任一"带 text 或 desc 的节点"框内 → 丢弃；否则转成 NodeRecord 追加，id 从 max(node.id)+1 续编。 */
    fun merge(nodes: List<NodeRecord>, lines: List<OcrLine>): List<NodeRecord>
}
```

补洞规则细节：
- "带字节点" = `text.isNotBlank() || desc.isNotBlank()` 且 bounds 非退化。
- 行文字 `trim()` 后为空、或全是标点/符号 → 丢弃（图标字体常被识别成单个符号）。
- 保留的行按 (top, left) 排序后编号，`clickable=false`、`scrollable=false`、`resId=""`、`className="ocr"`，
  `text` 为识别文字、`desc=""`。
- 最多 `MAX_OCR_ENTRIES` 条；整个列表仍受 `NodeGrounding.MAX_NODES_IN_PROMPT`（80）约束，取舍规则是优先级 + 按屏幕分段
  （y 方向切 `BANDS` 段，每段先保底配额、剩余名额再按优先级全局补，见 `NodeGrounding.selectForPrompt`），最终按屏幕阅读
  顺序（先 y 后 x）输出——不再是"节点在前 OCR 在后"，OCR 补洞条目按自己在屏幕上的位置跟普通节点交叉穿插。

`NodeGrounding.promptBlock`：`className == "ocr"` 的条目在坐标后打 ` ocr` 标记（与 ` click`/` scroll` 同位）。
系统提示加一句：**带 ocr 标记的条目是从截图里识别出来的文字，不是界面元素：位置可能略偏，不一定能点，
读信息优先用它，点它之前想一想它是不是按钮。**

`MlKitTextReader`（Android）：`TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())`
单例复用；`Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 3, SECONDS)`；遍历
`text.textBlocks[*].lines[*]` 取 `boundingBox` → `OcrLine`。任何异常/超时 → 返回 null 并记日志。

### 4.3 接线

- `AndroidEnvironment(screen, pm, textReader: TextReader?)`：`observe()` = `captureFrame` → dump →
  `textReader?.read(fullBitmap)` → `OcrMerge.merge(nodes, lines)` → `Observation(nodes=merged, ocrCount=…)`。
  `Observation` 加 `ocrCount: Int = 0`，AgentLoop 进度行显示 `nodes=36 ocr+2`。OCR 耗时也进进度行。
- `MainActivity`：`AndroidEnvironment(screen, packageManager, MlKitTextReader)`。
- `AgentCli`：`textReader = null`（裸 app_process 没有 ML Kit 的 ContentProvider 初始化、也加载不了 so），
  行为与现在完全一致。
- **调试入口**：`MainActivity` 支持 `am start -n com.androiduse/.MainActivity --es task "<任务>"`（root 可起），
  收到 extra 自动填入输入框并执行，结果照常落 transcript（`filesDir/transcripts/<taskId>/`）。
  这是 OCR 验收能从 adb 驱动 App 进程的前提，也是以后跑批的入口。

### 4.4 失败处理

| 情形 | 行为 |
|---|---|
| OCR 抛异常 / 超过 3s | 该步 `ocrCount=0`，列表为纯节点，进度行标 `ocr失败` |
| 帧为空（黑屏） | 不跑 OCR |
| 带字节点的框很大，盖住了旁边真正没进树的自绘文字 | 接受，宁可少补不灌水。相册的图片节点没有 text/desc，不算覆盖，图里的字会被保留 |
| 识别出敏感文字 | 与节点文字同一条路：只进提示词、只落本机 transcript，不上传除模型 API 之外的任何地方（§8.7 现状） |

### 4.5 性能预算

720 宽帧模型侧不变。新增：全帧 JPEG 解码约 30 ms，缩放 + 二次编码约 40 ms，ML Kit 中文全帧识别
约 150–400 ms（骁龙 8 系）。单步总增量 < 0.5 s，对比模型 5–8 s 一步可忽略。

## 5. 测试

**纯逻辑单测**：
- `OcrMergeTest`：行中心在带字节点内 → 丢弃；在无字节点（图片/容器）内 → 保留；无节点 → 全保留；
  空白/纯标点行丢弃；id 从 max+1 续编且按 (top,left) 排序；超过 40 条截断；退化 bounds 节点不算覆盖。
- `NodeGroundingTest`：`promptBlock` 对 className=ocr 的条目打 `ocr` 标记；`resolveTapId`/`relocate` 对 ocr 条目照常工作。
- `PromptBuilderTest`：系统提示含 ocr 说明。
- `AgentLoopTest`：`Observation.ocrCount` 出现在进度行（FakeEnv 给 ocrCount=2）。

**真机**：§2 验收两项 + 不回归两项。

## 6. 实施顺序

1. 依赖 + `OcrLine`/`TextReader`/`OcrMerge` + 单测。
2. `ScreenCapture.captureFrame` 一帧两用；`MlKitTextReader`。
3. `AndroidEnvironment` 接线、`Observation.ocrCount`、进度行、系统提示。
4. `MainActivity` intent extra 调试入口。
5. 真机：推图进相册 → 验收 → 不回归。
6. 文档：stage1 spec §0 表格 1d 行、DESIGN §5.3 状态、记忆。

## 7. 实施结果（2026-09-18）

计划 `docs/superpowers/plans/2026-09-18-1d-ocr-fallback.md` 全部完成，4 个提交。单测 193→209 绿。

**验收（真机，App 进程，`--es task` 入口）**：
- 「打开相册，看最新一张图片，读出图里 WLAN 连接的名称」：3 步 25s 完成。看图那一步节点 30 个、OCR 补入 16 条
  （`#23 (287,534) ocr text="WLAN"`、`#22 (836,533) ocr text="爱学A〉"`…），finish 摘要含"爱学A"。
- 不回归：设置首页 OCR 补入 2 条（ColorOS 图标文字、开关的"关"）≤ 3；「打开显示与亮度」「打开时钟App」各 2 步完成。
- OCR 耗时：全帧 1080×2376 中文识别 170–390 ms，18–52 行/帧，与预算相符。

**实施中修正的规则**：第一次验收看图那步补入 0 条——相册看图页有一个铺满全屏的 `desc="图片"` 节点，按"带字即覆盖"
把图里所有文字都盖掉了。改为：带字节点面积 ≥ 屏幕一半视为带标签的容器不算覆盖，除非它的文字本身包含这行 OCR 文字
（大段正文的 TextView 仍覆盖，避免重复）。`OcrMerge.merge` 因此多了 screenW/screenH 参数。

**观察**：
- APK 从 15 MB 涨到 60 MB（ML Kit 中文打包模型比 spec 估的 15–20 MB 大得多）。可接受；若日后在意，可按 ABI 拆分
  （已是 arm64 单 ABI）或改用非打包版（依赖 GMS，与 §2 目标 2 冲突，不推荐）。
- 一次相册验收在第 2 步（相册网格页 171 节点）超过 5 分钟没有落 outcome，被下一任务 force-stop；logcat 主缓冲已被
  系统日志冲掉，未定位。重跑 25s 完成，另两次同页面也正常。怀疑是模型端一次超长延迟触发 180s 读超时+重试。
  以后跑批时把 App 日志实时抓到文件（本次已这样做）。
- `ocrCount` 最初没写进 JSONL（读回全 0），已补。
