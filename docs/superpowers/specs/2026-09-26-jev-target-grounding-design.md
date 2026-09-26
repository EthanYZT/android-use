# 按描述定位（Jev target grounding）设计

日期：2026-09-26 · 前置：1c 节点 grounding、元素列表选取修复（均已合并）· 状态：待实现

## 1. 目标与范围

规划器（方舟 VLM）在提示词里只看得到经 `NodeGrounding.selectForPrompt` 筛过的最多 80 个元素。密页面（高德详情 577 节点、
美团店铺 260 节点）上，要点的按钮可能不在这 80 条里，规划器只能凭截图猜 x/y。

本设计给 `tap` 增加第三种形态 **`target`（自然语言描述）**：规划器写"点底部的'去结算'"，由 TypeSafe 的
**Jev**（System One 结构化判断模型）在**全量节点**里选出对应元素的 id，再点它的中心。

| 形态 | 何时用（提示词顺序） | 谁定位 |
|---|---|---|
| `tap{id}` | 目标在元素列表里（**首选，不变**） | 规划器 |
| `tap{target}` | 目标不在列表里 | Jev，在全量节点里选 |
| `tap{x,y}` | 以上都不行（图片/无节点的图标） | 规划器凭截图 |

**不在本次范围**：付款/发送按钮执行前拦截（场景 ②）、finish 完成核验（场景 ①）、用 Jev 给提示词列表排序、
"看错相邻键"（规划器自信给错 id 时 target 形态不会介入——那是场景"id 校验"，未选）。

**关于 Jev**（文档 `docs/typesafe_docs/`，本地副本不入库）：`POST https://api.typesafe.ai/v1/systemone`，
输入 `state`（文本/JSON）+ 类型化问题，返回结构化答案。本设计用两种原语：**Choice**（≤255 个选项，返回 `choice`、
`probabilities`、`confidence`）与 **Noul**（陈述为真的概率 0–1）。只收文本（截图不发送）；数字/位置判断弱；
官方说明中文准确率低于英文——所以验收以真机中文页面为准。模型 `jev-latest`（当前指向 `jev-1.13.0`），
按输入 token 计费，输出免费。

**可行性探测（2026-09-26，Mac 经代理）**：10 个中文节点、4 个问题一次请求，788 input token，往返 0.65–0.8 s。
「生椰拿铁那一行的选规格按钮」在两个同文字按钮里选对（confidence 1.00）；「底部的购物车图标」只凭 `res=iv_cart`
选对；不存在的「搜索框」exists=0.02。玩具数据，不代替验收。

## 2. 模型接口

`tap` 工具声明增加 `target`（**仅当配置了 TypeSafe key 时**出现）：

```json
{"name":"tap","description":"点击。首选传 id（元素列表里的编号，最准）；目标不在列表里时传 target 描述它；都不行才传 x/y 归一化坐标。","parameters":{"type":"object","properties":{"id":{"type":"integer","description":"元素列表里的 id"},"target":{"type":"string","description":"要点的元素的描述，写清文字和位置，例如 底部的'去结算'按钮、生椰拿铁那一行的'选规格'"},"x":{"type":"integer","description":"归一化 x，0-1000"},"y":{"type":"integer","description":"归一化 y，0-1000"}}}}
```

提示词规则替换现有两条 tap 规则（key 为空时保持原文不变）：

> - 目标元素在列表里时，**优先用 tap 的 id 形态**，不要自己猜坐标。
> - 目标不在列表里时，用 tap 的 target 描述它（写清上面的文字、在屏幕哪一部分、属于哪一行），由系统在完整界面里找到它；定位失败会告诉你原因和候选。
> - 只有目标没有文字也不是界面元素（图片、画面里的图标）时，才用 tap 的 x/y 坐标兜底。

解析（`ToolCallResolver`）：`id` 存在 → 现有逻辑，忽略 target；否则 `target` 非空 → `Action.TapTarget(target)`；
`target` 为空串 → 解析错误回给模型；否则 x/y → 现有逻辑。

## 3. 组件

### 3.1 `JevClient`（`agent/`，纯传输）

仿 `ArkChatClient`：构造参数 `apiKey`、`baseUrl`（默认 `https://api.typesafe.ai`）、`model`（默认 `jev-latest`）、
可注入 `transport: (url, body) -> HttpResult`（测试用），生产走 OkHttp，单次超时 10 s。

```kotlin
sealed class Result {
    data class Ok(val answers: Map<String, Answer>, val model: String, val inputTokens: Int, val latencyMs: Long) : Result()
    data class Err(val message: String, val retryable: Boolean) : Result()
}
sealed class Answer {
    data class Choice(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : Answer()
    data class Noul(val value: Double) : Answer()
}
fun evaluate(requestBody: String): Result
```

- 请求头 `Authorization: Bearer <key>`、`Content-Type: application/json`。
- 重试：429 与 5xx、连接失败/超时重试 **1 次**；429 带 `retry-after` 时按它等，最多 2 s。401/403/400/422 不重试。
- 响应用现有 `MiniJson` 解析；缺字段/非法 JSON → `Err`。
- key 来源：`local.properties` 的 `typesafe.apiKey` → `BuildConfig.TYPESAFE_API_KEY`（`local.properties.example` 补占位）。

### 3.2 `TargetLocator`（`agent/`，纯逻辑，不碰网络）

**阈值与常量集中在这里**，便于审阅和按验收数据调整：

```kotlin
const val EXISTS_MIN = 0.5        // exists Noul 低于此 → 判定不存在
const val CONFIDENCE_MIN = 0.6    // Choice confidence 低于此 → 判定不确定
const val CHUNK_SIZE = 250        // Choice 选项上限 255，留余量
const val PER_CHUNK_KEEP = 2      // 分块时每块进入第二轮的名额
```

1. **`candidates(nodes, w, h)`**：保留 bounds 不退化、且（可点 或 可输入 或 有 text/desc）的节点；
   同一中心点的无文字可点节点只留第一个（与 `selectForPrompt` 口径一致）；OCR 伪节点（`className=ocr`）保留。
2. **区域词**：节点中心归一化坐标落入 3×3 格子——`上部/中部/下部` × `左/中/右`，如 `下部-右`（Jev 对数字坐标弱，给词不给数）。
3. **`buildRequest(target, cands)`**：
   - `state = {"elements": [ 每个候选一行 ]}`，行格式 `#<id> <区域词> [click] [edit] [ocr] text="…" desc="…" res=<resId 末段>`，
     text/desc 经 `UntrustedText.field`（净化、转义、截断），resId 只取 `:id/` 之后的部分；
   - 问题 `where`（Choice）：instructions =「`elements` 是手机屏幕上的元素列表，每行以 #编号 开头。要点击的元素是：「<target>」。它是哪个编号？」，
     criteria = 候选编号 → null；
   - 问题 `exists`（Noul）：「`elements` 里是否有这个元素：「<target>」？」，criteria true/false 各一句说明；
   - target 本身经 `UntrustedText.sanitize`（它可能是规划器从屏幕抄来的文字）。
4. **分块**：候选 > `CHUNK_SIZE` 时按阅读顺序切块，同一请求里每块一个 Choice（`where_0`、`where_1`…，每块只列本块编号，
   但 state 含全部候选）+ 一个 exists；第二个请求只把各块前 `PER_CHUNK_KEEP` 名作为选项再问一次 `where`。门控用第二轮的 confidence 与第一轮的 exists。
5. **`interpret(...)`** → `Located(id, confidence)` 或 `Rejected(reason, top3)`，`top3` 为 where 概率前三（编号、文字、概率）。

### 3.3 `Grounder` 接口

```kotlin
interface Grounder {
    fun locate(target: String, nodes: List<NodeRecord>, screenW: Int, screenH: Int): LocateResult
}
sealed class LocateResult {
    data class Located(val node: NodeRecord, val confidence: Double, val latencyMs: Long) : LocateResult()
    data class Rejected(val message: String) : LocateResult()   // message 直接作为 tool 结果
}
```

真实实现 `JevGrounder(JevClient)` 串起 `TargetLocator` 与 `JevClient`；`AgentLoop` 以可空参数注入
（null = 未配置 key，工具声明与提示词都不出现 target）。`AgentCli` 同样注入（CLI 也可用）。

### 3.4 `AgentLoop` 改动

- 执行 `Action.TapTarget` 时：节点取本步观察的全量 `obs.nodes`；批内第二个动作起先 `env.refreshNodes()`（与现有 tap-by-id 一致，
  条件从"tap 且带 id"扩到"tap 且带 id 或 target"）→ `grounder.locate` → `Located` 则换算节点中心为 `Action.Tap` 照常执行。
- tool 结果如实写：`按描述定位到 #27 text="去结算"（置信 0.97，Jev 640ms），已点击`；`Rejected` 则该动作失败，结果 = 拒绝文案。
- `Execution` 与 JSONL 不新增字段——定位信息就在 result 文本里，读回与导出无需改动。

## 4. 门控与失败处理

原则：**定位不确定就不点**，把原因和候选退回给规划器，由它改用 id、x/y 或换描述。不做静默降级。

| 情况 | 判定 | tool 结果（动作失败，除最后一行） |
|---|---|---|
| 无候选 | 不调 Jev | `屏幕上没有可定位的元素，请用 x/y` |
| 不存在 | exists < 0.5 | `没找到「…」（存在概率 0.12）。最接近的：#… text="…" 0.41；…。可能是图片/图标，请用 x/y` |
| 不确定 | exists ≥ 0.5 且 confidence < 0.6 | `不确定「…」是哪个：#… text="…" 0.48；#… 0.44；…。请用 id 指定，或写得更具体（文字、哪一行、屏幕哪部分）` |
| 网络/超时/5xx/429 | 重试 1 次后仍失败 | `按描述定位暂不可用（<原因>），请改用 id 或 x/y` |
| 401/403 | 不重试 | 同上；logcat 报错 |
| 确定 | 两道门都过 | **点击成功**：`按描述定位到 #id …（置信 …），已点击` |

- **同一可点元素合并（2026-09-26 回放后加，用户确认）**：可点容器与它里面的文字/图标子节点会在 Choice 里分摊概率
  （京东搜索栏 0.58/0.28/0.10 分给 desc、文字、容器三条，单看都不够 0.6）。判定前每个候选归到**完整包含它的最小**
  可点/可输入候选（找不到归自己），按组求和；胜者是合并后概率最高的组，点它的中心；"不确定"门控用的 confidence
  按 TypeSafe 的定义 `(n·peak − 1)/(n − 1)` 在合并后的分布上重算（n 为本题选项数，无合并时与 Jev 返回值一致）。
  必须**完整包含**而非"中心落在其中"：浮层下被盖住的邻居会与文字部分重叠（回放中"华为"曾被并进被盖住的"秒杀"）。
  子按钮本身可点时归它自己，不会被并进整张卡片。
- 定位失败是一次**失败动作**：批内后续动作标"未执行"，计入现有的"连续 3 步失败中止"。
- 付款/提交订单保护仍由现有 handoff 规则负责；target 形态不绕过也不加强它。

**隐私**：只有规划器用 target 时，当前屏候选元素的文字（text/desc/resId 末段）才发到 api.typesafe.ai（美国）。
发送的只有这些候选文字和 target 描述本身；截图、任务原文与对话历史都不发送。

## 5. 测试

JVM 单测，TDD。`JevClient` 注入 `transport`，不起网络。

- `TargetLocatorTest`：候选过滤（退化 bounds、同中心去重、纯容器剔除、OCR 保留）；区域词边界（333/666 附近）；
  请求 JSON（选项为编号、含 exists、注入串被转义、resId 取末段）；251 个候选分两块、第二轮只含各块前 2；
  `interpret` 的确定 / 不存在 / 不确定 / 第二轮四种结果与 top3 文案。
- `JevClientTest`：解析 choice/noul；Bearer 头；401 不重试；429、5xx 重试一次；非法 JSON → Err。
- `ToolCallResolverTest`：`tap{target}` → `TapTarget`；id+target → id 优先；空 target → 错误；grounder 为 null 时 target 当解析错误。
- `AgentLoopTest`（假 `Grounder`）：Located → 注入 Tap、结果含 `#id` 与置信；Rejected → 失败、批内后续"未执行"、计入连续失败；
  批内第二个 target 动作用刷新后的节点。
- `PromptBuilderTest`：无 key 时工具声明与规则都不含 target（与现状逐字一致）；有 key 时规则顺序 id → target → x/y。

## 6. 真机验收

1. **机上可达性**：`adb shell` 在手机上 curl `https://api.typesafe.ai/v1/models`，记录状态码与延迟。
2. **离线回放**：新增 `daemon/LocateCli`（`app_process` 起，参数 `<step-N.nodes.json> <描述>`，输出候选数、结果、前三名、延迟）。
   从机上拉已有任务的 `step-N.nodes.json`（美团店铺、高德详情等密页面），手写约 20 个已知目标的描述，统计命中与拒绝是否合理。
3. **E2E**（App 进程）：目标在 80 条之外的任务——美团点单到"选规格"/"去结算"（到结算页 handoff，不付款）、高德详情页点"导航"。
4. **回归**：5 App 验收的 10 个任务再跑一遍，id 形态命中率不低于此前 ≈98%，确认规划器没有被 target 引偏。

**通过标准**：target 形态点中预期元素 ≥ 90%（以下一步截图判定）；该拒的都拒了，没有高置信点错；
机上单次定位中位延迟 < 1.5 s；回归无退化。不达标时带数据回来调阈值或改描述规则，不硬调到通过。
