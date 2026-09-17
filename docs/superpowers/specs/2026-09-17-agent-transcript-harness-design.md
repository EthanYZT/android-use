# Agent 上下文 Harness（Transcript + tool calling）设计

日期：2026-09-17。分支 `stage1-perception`。属阶段 1 的补充项，排在 open_app 动作与 5 App 验收之前。

## 背景与问题

阶段 0 的 `AgentLoop` 每步都是一次独立请求：系统提示 + 当前截图 + 当前节点列表 + 一段手工拼的
"已执行步骤：Tap(77,32)…"。模型上一轮的判断、看到的内容、得出的结论全部丢失。

真机复现（任务"看下我手机的型号是什么然后把屏幕超时设置成30秒"，15 步用完）：模型进"关于本机"
读到型号后返回，回到首页既不记得型号也不知道超时改没改，于是再进"关于本机"，循环三次。
根因不是模型能力，是 **Agent 没有记忆**。

## 目标

像 Claude Code / Codex 一样：**整个任务是一条持续增长的 messages 数组**，模型每轮的笔记和每步的
执行结果都留在对话里。记忆就是对话本身。同时这份对话记录（Transcript）就是可导出的结构化日志
（DESIGN §11.2③、§6.3）。

范围（"最小版"）：模型不换（glm-5.3-flash 看图 + 节点列表）；不做 Planner/Grounder 两层拆分。

## 决策

- **动作用 OpenAI tool calling**（2026-09-17 探测：glm-5.3-flash 在方舟上 tool calling 带图、多轮、
  tool 结果均正常）。content 是模型的一句观察笔记，tool_calls 是动作。
- **Transcript 是唯一事实源**：发给模型的 messages 与导出的日志都是它的投影，由纯函数生成。
- **错误反馈给模型而不是中止**：id 不存在、注入失败等写进 tool 结果，让模型自己纠正。

## 数据结构（`agent/Transcript.kt`，纯 Kotlin）

- `Transcript(taskId, task, model, startedAtMs, screenW, screenH, steps)`
- `Step(index, observation, reply?, execution?)`
  - `Observation(screenshotBase64?, screenshotPath?, nodes, nodesBlock, dumpError?)`
  - `ModelReply(note, toolCalls, finishReason?, reasoningTokens?, latencyMs)`；`ToolCall(id, name, argumentsJson)`
  - `Execution(action?, ok, result, costMs)`
- 序列化：每步一行 JSONL（`TranscriptCodec`），手写编码，不用 org.json（JVM 单测约定）。

## messages 生成（`PromptBuilder`）

```
system                      角色、坐标约定、规则、"content 写一句观察笔记，动作用工具调用"
user                        任务目标
[每一步]
  user                      观察：截图 + 节点列表文本（或占位）
  assistant                 content 笔记 + tool_calls（原样回放）
  tool                      执行结果文本
user                        当前观察（模型对它作答）
```

保留策略（常量，可调）：截图只留最近 2 步，更早换成 `[第 N 步截图已省略]`；节点列表最近 4 步全文，
更早压成 `[第 N 步节点列表已省略，共 M 个元素]`；assistant 笔记与 tool 结果全部保留。

tools：`tap`（id 或 x/y）、`swipe`、`back`、`wait`、`finish`。不声明 home（虚拟屏 Home 会串到物理屏）。

## 循环与错误处理（`AgentLoop`）

每步：观察 → 生成 messages → 调模型 → 取第一个 tool call → 解析成 Action → 执行 → 写回 Transcript。

- 截断（finish_reason=length）：客户端层重试一次，不进对话。
- 无 tool call 只有文字：记入 Transcript，追加 user "请调用一个工具继续"，同一步最多补一次。
- 多个 tool call：只执行第一个，tool 结果里说明。
- id 不存在 / bounds 退化 / 注入失败：写进 tool 结果，让模型下一轮纠正；连续 3 步失败才中止。
- 连续 3 步节点列表相同且动作相同：判定卡住，中止。
- max_steps 默认 15；到达时结论带上最后一条笔记。
- finish 的 summary 就是任务答案，允许带查到的信息。

`ArkVisionClient` → `ArkChatClient`：纯聊天客户端，输入请求体，输出 content / tool_calls /
finish_reason / usage；重试与超时逻辑保留。决策逻辑全部在 `AgentLoop`。

`AgentLoop` 通过 `Environment` 接口（observe / perform）访问截图、节点树、注入，Android 实现在
`AndroidEnvironment`；单测用假实现覆盖上面的错误路径。

## 落盘与测试

- `TranscriptStore`：任务开始建目录 `<filesDir>/transcripts/<taskId>/`，每步追加一行 JSONL，截图存
  `step-N.jpg`。这是子项目 B（日志查看/导出）的输入。
- 单测：TranscriptCodec、PromptBuilder 保留策略、tool call 解析与 Action 解析、ArkChatClient 重试、
  AgentLoop 各错误路径。
- E2E：真机 `AgentCli` 跑两段式任务"看下我手机的型号是什么然后把屏幕超时设置成30秒"，期望 finish
  的 summary 含型号且 `screen_off_timeout` 变为 30000。

## 实施结果（2026-09-17）

- 单测 130 绿（新增 TranscriptCodec / ToolCallResolver / PromptBuilder 投影与保留策略 / ArkChatClient 重试 /
  AgentLoop 各错误路径 / TranscriptStore 落盘）。`ArkVisionClient`、`TaskLogger` 删除。
- 真机 E2E（`AgentCli`，任务"看下我手机的型号是什么然后把屏幕超时设置成30秒"）：**9 步完成**，
  finish summary 同时带型号"一加 Ace 5"和"自动息屏 30 分钟 → 30 秒"，`screen_off_timeout` 实测变为 30000。
  改造前同一任务 15 步用完、在"关于本机"来回三次。每步推理 26–102 token，笔记明确写出"已查到型号…现在去设置超时"。
- transcript.jsonl 在 `/data/local/tmp/androiduse_transcripts/<taskId>/`（CLI）或 `<filesDir>/transcripts/<taskId>/`（App），
  每步一行可被标准 JSON 解析，截图另存 `step-N.jpg`。
