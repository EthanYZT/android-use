# 交接与接管（handoff / takeover）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 模型在付款/人机验证前调用 `handoff` 停下；用户在结束卡片点"在手机上继续"，虚拟屏上的任务连状态搬到物理屏前台。

**Architecture:** 新增 `Action.Handoff` 与 `handoff` 工具，`AgentLoop.Outcome` 增加 `kind`（FINISHED/HANDOFF/ABORTED）并写进 transcript 结束行；Android 侧 `ScreenHandover` 用 root `am display move-stack <id> 0` 按 `HandoverPlan.order`（纯逻辑，底→顶、本包排除）逐个搬任务，核对后销毁虚拟屏；`MainActivity` 结束区加按钮。

**Tech Stack:** Kotlin / Android（root shell argv、无障碍守护进程已有）、JUnit4 JVM 单测、Gradle（`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`）、真机 `ANDROID_SERIAL=3B658700ZQ400000`。

**Spec:** `docs/superpowers/specs/2026-09-18-handoff-takeover-design.md`

## Global Constraints

- 红线：Agent 从不主动占物理屏；搬任务只在用户点按钮后发生。
- root 命令一律 `RootShell.execArgv(List<String>)` argv 形式；不可信字符串不进 shell 字符串。
- `capability/`、`agent/` 下被 JVM 单测的纯文件不 import `android.*`。
- 工具名 `handoff`，参数 `reason`（必填）。提示词规则原文（`PromptBuilder.HANDOFF_RULE`）：
  `付款、提交订单、结算确认，以及滑块/选图这类人机验证，必须由用户本人操作：做到那一页就调用 handoff 说明停在哪，不要自己点支付、不要拖滑块。短信验证码不算，自己从通知或界面读。发送短信、拨出电话、开始导航可以直接做。`
- 搬任务命令：`am display move-stack <taskId> 0`（本 ROM 无 `am stack move-stack`）。
- 交接显示文案：`⇥ 已交接：<reason>`；按钮文案：`在手机上继续`。
- JSONL 结束行新增字段 `"handoff":true|false`，旧记录缺省 false。
- 注释用中文；测试风格跟随现有文件（JUnit4，`org.junit.Assert.*`）。
- 单测：`./gradlew :app:testDebugUnitTest -q`；安装：`./gradlew :app:installDebug -q`。

---

### Task 1: `Action.Handoff` + `handoff` 工具声明与提示词规则

**Files:**
- Modify: `app/src/main/java/com/androiduse/actuation/Action.kt`（`Finish` 之后）
- Modify: `app/src/main/java/com/androiduse/agent/ToolCallResolver.kt`（`"finish"` 分支旁）
- Modify: `app/src/main/java/com/androiduse/agent/PromptBuilder.kt`（`SYSTEM_TOOLS_RULE` 旁加常量；`systemPrompt` 规则列表第 89 行附近加一条；`toolsJson` 在 `finish` 前加声明）
- Modify: `app/src/main/java/com/androiduse/actuation/ActionCommand.kt`（`toShell` 对 `Handoff` 返回 null，与 `Finish`/`System` 同）
- Test: `app/src/test/java/com/androiduse/agent/ToolCallResolverTest.kt`、`app/src/test/java/com/androiduse/agent/PromptBuilderTest.kt`、`app/src/test/java/com/androiduse/actuation/ActionCommandTest.kt`

**Interfaces:**
- Produces: `data class Action.Handoff(val reason: String) : Action()`；`PromptBuilder.HANDOFF_RULE: String`；`toolsJson()` 含 `"name":"handoff"`。

- [ ] **Step 1: 写失败测试**

`ToolCallResolverTest.kt` 在 `homeAndUnknownToolsAreErrors` 前加：

```kotlin
    @Test
    fun handoffResolvesToActionWithReason() {
        assertEquals(Action.Handoff("停在结算页，需要你付款"), ok(ToolCallResolver.resolve(ToolCall("c", "handoff", """{"reason":"停在结算页，需要你付款"}"""), nodes, w, h)))
        assertEquals(Action.Handoff(""), ok(ToolCallResolver.resolve(ToolCall("c", "handoff", "{}"), nodes, w, h)))
    }
```

`PromptBuilderTest.kt` 末尾加：

```kotlin
    @Test
    fun declaresHandoffToolAndRule() {
        val tools = PromptBuilder.toolsJson()
        assertTrue(tools, tools.contains("\"name\":\"handoff\""))
        assertTrue(tools, tools.contains("\"reason\""))
        val p = PromptBuilder.systemPrompt()
        assertTrue(p, p.contains(PromptBuilder.HANDOFF_RULE))
        assertTrue(p, p.contains("不要拖滑块"))
        assertTrue(p, p.contains("短信验证码不算"))
    }
```

`ActionCommandTest.kt` 加（文件已 import `assertNull`、`VirtualScreen`）：

```kotlin
    @Test
    fun handoffHasNoShellCommand() {
        assertNull(ActionCommand.toShell(Action.Handoff("x"), VirtualScreen(7, 1080, 2376)))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.androiduse.agent.ToolCallResolverTest' --tests 'com.androiduse.agent.PromptBuilderTest' --tests 'com.androiduse.actuation.ActionCommandTest' -q`
Expected: 编译失败 `Unresolved reference: Handoff` / `HANDOFF_RULE`。

- [ ] **Step 3: 实现**

`Action.kt`，`Finish` 之后：

```kotlin
    /**
     * 交接：模型做到需要本人操作的一步（付款/提交订单/结算确认、滑块/选图类人机验证）停下，
     * reason 说明停在哪、用户接着做什么。与 Finish 一样结束循环；不经 Injector（toShell 返回 null）。
     */
    data class Handoff(val reason: String) : Action()
```

`ToolCallResolver.kt`，`"finish"` 分支后：

```kotlin
            "handoff" -> Resolution.Ok(Action.Handoff(ResponseParser.field(a, "reason") ?: ""))
```

`ActionCommand.kt`：在 `toShell` 的 `when` 里，`is Action.Finish` / `is Action.System` 返回 null 的那一支加上 `is Action.Handoff`（读一下现有写法，保持同一处返回 null）。

`PromptBuilder.kt`，`SYSTEM_TOOLS_RULE` 之后：

```kotlin
    /** 交接规则（spec handoff §2）：付款与人机验证必须本人操作；短信验证码、发送/拨出/导航不交接。 */
    const val HANDOFF_RULE = "付款、提交订单、结算确认，以及滑块/选图这类人机验证，必须由用户本人操作：做到那一页就调用 handoff 说明停在哪，不要自己点支付、不要拖滑块。短信验证码不算，自己从通知或界面读。发送短信、拨出电话、开始导航可以直接做。"
```

`systemPrompt` 规则列表里 `- $SYSTEM_TOOLS_RULE` 下一行加 `- $HANDOFF_RULE`。

`toolsJson()` 的 `finish` 声明前加一行：

```
        {"type":"function","function":{"name":"handoff","description":"任务做到需要本人操作的一步（付款/提交订单/结算确认，或滑块/选图等人机验证）时调用，停在那一页不要再点。reason 写清停在哪、用户接着要做什么。","parameters":{"type":"object","properties":{"reason":{"type":"string"}},"required":["reason"]}}},
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 全绿。再跑 `./gradlew :app:testDebugUnitTest -q` 确认整套绿（`PromptBuilderTest` 里如有"工具数量"断言按需 +1）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/androiduse/actuation/Action.kt app/src/main/java/com/androiduse/actuation/ActionCommand.kt app/src/main/java/com/androiduse/agent/ToolCallResolver.kt app/src/main/java/com/androiduse/agent/PromptBuilder.kt app/src/test/java/com/androiduse/agent/ToolCallResolverTest.kt app/src/test/java/com/androiduse/agent/PromptBuilderTest.kt app/src/test/java/com/androiduse/actuation/ActionCommandTest.kt
git commit -m "feat(handoff): Action.Handoff、handoff 工具声明与交接规则

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `AgentLoop` 交接结束 + transcript 结束行 `handoff` 字段 + CLI 输出

**Files:**
- Modify: `app/src/main/java/com/androiduse/agent/AgentLoop.kt`（`Outcome`、`run`、finish 处理块）
- Modify: `app/src/main/java/com/androiduse/agent/Environment.kt`（`TranscriptSink.outcome` 签名）
- Modify: `app/src/main/java/com/androiduse/agent/Transcript.kt`（`StoredOutcome`、`encodeOutcome`、`decode`）
- Modify: `app/src/main/java/com/androiduse/log/TranscriptStore.kt`（`outcome` 覆写）
- Modify: `app/src/main/java/com/androiduse/daemon/AgentCli.kt:51`
- Modify: `app/src/main/java/com/androiduse/MainActivity.kt`（sink 的 `outcome` 覆写签名，暂时只透传；UI 在 Task 4）
- Test: `app/src/test/java/com/androiduse/agent/AgentLoopTest.kt`、`app/src/test/java/com/androiduse/agent/TranscriptReadbackTest.kt`

**Interfaces:**
- Consumes: `Action.Handoff(reason)`（Task 1）。
- Produces: `AgentLoop.Outcome(finished, summary, transcript, kind: Kind)`，`enum class Kind { FINISHED, HANDOFF, ABORTED }`，`Outcome.handoff: Boolean get() = kind == Kind.HANDOFF`；`TranscriptSink.outcome(t, finished, summary, handoff: Boolean = false)`；`StoredOutcome(finished, summary, endedAtMs, handoff: Boolean = false)`；`TranscriptCodec.encodeOutcome(finished, summary, endedAtMs, handoff: Boolean = false)`；`AgentLoop.NOT_EXECUTED_AFTER_HANDOFF = "未执行（已 handoff）"`。

- [ ] **Step 1: 写失败测试**

`AgentLoopTest.kt` 在 `finishInTheMiddleExecutesPrecedingCallsThenFinishes` 后加：

```kotlin
    @Test
    fun handoffEndsTheLoopWithHandoffKindAndReasonAsSummary() {
        val (o, env, _) = harness(
            toolReply("到结算页了", "tap" to """{"id":7}"""),
            toolReply("要付款了", "handoff" to """{"reason":"停在结算页，需要你付款"}"""),
        )
        assertFalse(o.finished)
        assertEquals(AgentLoop.Kind.HANDOFF, o.kind)
        assertTrue(o.handoff)
        assertEquals("停在结算页，需要你付款", o.summary)
        assertEquals(2, o.transcript.steps.size)
        assertEquals("handoff", o.transcript.steps[1].execution!!.result)
    }

    @Test
    fun callsAfterHandoffInTheSameStepAreNotExecuted() {
        val (o, env, _) = harness(
            toolReply("交接后还想点", "handoff" to """{"reason":"验证码"}""", "tap" to """{"id":7}"""),
        )
        assertEquals(AgentLoop.Kind.HANDOFF, o.kind)
        assertTrue(env.performed.isEmpty())
        assertEquals(AgentLoop.NOT_EXECUTED_AFTER_HANDOFF, o.transcript.steps[0].executions[1].result)
    }

    @Test
    fun finishedAndAbortedOutcomesCarryTheirKind() {
        val (fin, _, _) = harness(toolReply("完成", "finish" to """{"summary":"done"}"""))
        assertEquals(AgentLoop.Kind.FINISHED, fin.kind)
        val (ab, _, _) = harness(textReply("没有工具"), textReply("还是没有"))
        assertEquals(AgentLoop.Kind.ABORTED, ab.kind)
        assertFalse(ab.handoff)
    }
```

`FakeSink` 加记录（同文件）：

```kotlin
        var outcomeHandoff: Boolean? = null
        override fun outcome(t: Transcript, finished: Boolean, summary: String, handoff: Boolean) { outcomeHandoff = handoff }
```

并在 `handoffEndsTheLoopWithHandoffKindAndReasonAsSummary` 里无需断言 sink（harness 内部 new 了 FakeSink 拿不到）——改为在 `TranscriptReadbackTest.kt` 验证编码：

```kotlin
    @Test
    fun outcomeHandoffFlagRoundTripsAndDefaultsToFalse() {
        val withFlag = listOf(TranscriptCodec.encodeHeader(sample())) + TranscriptCodec.encodeOutcome(finished = false, summary = "停在结算页", endedAtMs = 1L, handoff = true)
        assertTrue(TranscriptCodec.decode(withFlag)!!.outcome!!.handoff)
        val legacy = listOf(TranscriptCodec.encodeHeader(sample())) + """{"type":"outcome","finished":true,"summary":"x","endedAtMs":1}"""
        val o = TranscriptCodec.decode(legacy)!!.outcome!!
        assertFalse(o.handoff)
        assertTrue(o.finished)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.androiduse.agent.AgentLoopTest' --tests 'com.androiduse.agent.TranscriptReadbackTest' -q`
Expected: 编译失败（`Kind`、`handoff` 未定义）。

- [ ] **Step 3: 实现**

`AgentLoop.kt`：

```kotlin
    enum class Kind { FINISHED, HANDOFF, ABORTED }

    data class Outcome(val finished: Boolean, val summary: String, val transcript: Transcript, val kind: Kind = if (finished) Kind.FINISHED else Kind.ABORTED) {
        val handoff: Boolean get() = kind == Kind.HANDOFF
    }
```

companion 加 `const val NOT_EXECUTED_AFTER_HANDOFF = "未执行（已 handoff）"`。

`run`：`sink.outcome(o.transcript, o.finished, o.summary, o.handoff)`。

`runSteps` 的批处理：`var finish: Action.Finish? = null` 旁加 `var handoff: Action.Handoff? = null`；`when (action)` 里 `is Action.Finish` 分支后加：

```kotlin
                                is Action.Handoff -> {
                                    handoff = action
                                    Execution(action, true, "handoff", System.currentTimeMillis() - c0)
                                }
```

`else if (finish != null) stopped = NOT_EXECUTED_AFTER_FINISH` 改为：

```kotlin
                else if (finish != null) stopped = NOT_EXECUTED_AFTER_FINISH
                else if (handoff != null) stopped = NOT_EXECUTED_AFTER_HANDOFF
```

finish 返回块之后加：

```kotlin
            if (handoff != null && !anyFailed) {
                onProgress(line(i, "Handoff", summary.costMs, handoff!!.reason))
                return@withContext Outcome(false, handoff!!.reason, t, Kind.HANDOFF)
            }
```

（`abort(...)` 与其它 `Outcome(false, …)` 保持默认 kind=ABORTED；`Outcome(true, …)` 默认 FINISHED。）

`Environment.kt`：`fun outcome(t: Transcript, finished: Boolean, summary: String, handoff: Boolean = false) {}`。

`Transcript.kt`：

```kotlin
data class StoredOutcome(val finished: Boolean, val summary: String, val endedAtMs: Long, val handoff: Boolean = false)
```

`encodeOutcome(finished, summary, endedAtMs, handoff: Boolean = false)`，在 `finished` 后追加 `append(",\"handoff\":").append(handoff)`；`decode` 的 `"outcome"` 分支加 `handoff = m["handoff"] as? Boolean ?: false`。

`TranscriptStore.kt` 的 `outcome` 覆写改为四参并透传 `handoff`。`MainActivity.kt` sink 的 `outcome` 覆写同样改四参透传。

`AgentCli.kt:51`：`println("RESULT: finished=${outcome.finished} handoff=${outcome.handoff} ${outcome.summary}")`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest -q`。Expected: 全绿。`./gradlew :app:compileDebugKotlin -q` 无错（MainActivity/AgentCli 改动）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/androiduse/agent/AgentLoop.kt app/src/main/java/com/androiduse/agent/Environment.kt app/src/main/java/com/androiduse/agent/Transcript.kt app/src/main/java/com/androiduse/log/TranscriptStore.kt app/src/main/java/com/androiduse/daemon/AgentCli.kt app/src/main/java/com/androiduse/MainActivity.kt app/src/test/java/com/androiduse/agent/AgentLoopTest.kt app/src/test/java/com/androiduse/agent/TranscriptReadbackTest.kt
git commit -m "feat(handoff): AgentLoop 交接结束（Outcome.kind）、transcript 结束行 handoff 字段、CLI 输出

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `HandoverPlan`（纯逻辑：搬哪些、什么顺序、什么命令）

**Files:**
- Create: `app/src/main/java/com/androiduse/capability/HandoverPlan.kt`
- Test: `app/src/test/java/com/androiduse/capability/HandoverPlanTest.kt`

**Interfaces:**
- Consumes: `DisplayTasks.Task(id: Int, pkg: String?)`、`DisplayTasks.parse(dump): Map<Int, List<Task>>`（自顶向下）。
- Produces: `HandoverPlan.order(tasksTopToBottom: List<DisplayTasks.Task>, ownPkg: String): List<Int>`（底→顶，排除 ownPkg）；`HandoverPlan.moveArgv(taskId: Int): List<String>`；`HandoverPlan.targetId(tasksTopToBottom, ownPkg): Int?`（顶层非本包任务，即用户要接的那一页）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverPlanTest {
    private fun t(id: Int, pkg: String?) = DisplayTasks.Task(id, pkg)

    @Test fun ordersBottomFirstSoTheTopTaskIsMovedLastAndEndsUpInFront() {
        val topToBottom = listOf(t(30, "com.sankuai.meituan.takeoutnew"), t(20, "com.android.contacts"), t(10, "com.android.settings"))
        assertEquals(listOf(10, 20, 30), HandoverPlan.order(topToBottom, "com.androiduse"))
    }

    @Test fun neverMovesOwnPackage() {
        val topToBottom = listOf(t(30, "com.autonavi.minimap"), t(21, "com.androiduse"), t(10, null))
        assertEquals(listOf(10, 30), HandoverPlan.order(topToBottom, "com.androiduse"))
    }

    @Test fun targetIsTheTopmostForeignTask() {
        assertEquals(30, HandoverPlan.targetId(listOf(t(30, "com.autonavi.minimap"), t(10, "com.android.settings")), "com.androiduse"))
        assertEquals(10, HandoverPlan.targetId(listOf(t(31, "com.androiduse"), t(10, "com.android.settings")), "com.androiduse"))
        assertNull(HandoverPlan.targetId(listOf(t(31, "com.androiduse")), "com.androiduse"))
        assertNull(HandoverPlan.targetId(emptyList(), "com.androiduse"))
    }

    @Test fun moveArgvTargetsPhysicalDisplayZero() {
        assertEquals(listOf("am", "display", "move-stack", "278", "0"), HandoverPlan.moveArgv(278))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.androiduse.capability.HandoverPlanTest' -q`
Expected: 编译失败 `Unresolved reference: HandoverPlan`。

- [ ] **Step 3: 实现**

```kotlin
package com.androiduse.capability

/**
 * 接管（takeover）纯逻辑：用户点"在手机上继续"后，把虚拟屏上的任务搬到物理屏 display 0。
 * 顺序从底到顶——顶层任务最后搬，于是它在物理屏前台，其余落到后台/最近任务。
 * 本 App 自己的任务永远不搬。命令：`am display move-stack <taskId> 0`（本 ROM 无 `am stack move-stack`）。
 */
object HandoverPlan {
    const val PHYSICAL_DISPLAY = 0

    /** 要搬的任务 id，按执行顺序（底→顶）。输入是 DisplayTasks.parse 的自顶向下列表。 */
    fun order(tasksTopToBottom: List<DisplayTasks.Task>, ownPkg: String): List<Int> =
        tasksTopToBottom.filter { it.pkg != ownPkg }.map { it.id }.asReversed()

    /** 用户要接手的那一页：顶层非本包任务；没有则 null（无可接管）。 */
    fun targetId(tasksTopToBottom: List<DisplayTasks.Task>, ownPkg: String): Int? =
        tasksTopToBottom.firstOrNull { it.pkg != ownPkg }?.id

    fun moveArgv(taskId: Int): List<String> = listOf("am", "display", "move-stack", taskId.toString(), PHYSICAL_DISPLAY.toString())
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 4/4 绿。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/androiduse/capability/HandoverPlan.kt app/src/test/java/com/androiduse/capability/HandoverPlanTest.kt
git commit -m "feat(handoff): HandoverPlan——搬任务顺序（底→顶、排除本包）与 move-stack argv

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `ScreenHandover`（Android 搬任务 + 销屏）+ 结束卡片按钮 + 交接文案

**Files:**
- Create: `app/src/main/java/com/androiduse/ScreenHandover.kt`
- Modify: `app/src/main/java/com/androiduse/MainActivity.kt`（`startTask` 结束处、新增 `btnTakeover` 逻辑、`showResult`）
- Modify: `app/src/main/res/layout/activity_main.xml`（`tvResult` 之后加按钮）
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/androiduse/ui/TaskAdapter.kt:37-41`、`app/src/main/java/com/androiduse/TranscriptActivity.kt:58-62`、`app/src/main/java/com/androiduse/log/MarkdownExporter.kt:27`
- Test: `app/src/test/java/com/androiduse/log/MarkdownExporterTest.kt`

**Interfaces:**
- Consumes: `HandoverPlan.order/targetId/moveArgv`（Task 3）、`DisplayTasks.parse`、`RootShell.execArgv`、`ScreenSession.screen/destroy()`、`AgentLoop.Outcome.kind`（Task 2）。
- Produces: `ScreenHandover.hasTakeoverTarget(): Boolean`；`ScreenHandover.takeover(): Result<Unit>`（阻塞，IO 线程）。

- [ ] **Step 1: 写失败测试（导出文案）**

`MarkdownExporterTest.kt` 加（照文件里现有构造 `StoredTranscript`/`StoredOutcome` 的方式，把 outcome 换成 `StoredOutcome(finished = false, summary = "停在结算页", endedAtMs = 1L, handoff = true)`）：

```kotlin
    @Test
    fun handoffOutcomeIsLabelledAsHandoff() {
        val md = MarkdownExporter.export(transcriptWithOutcome(StoredOutcome(false, "停在结算页", 1L, handoff = true)))
        assertTrue(md, md.contains("**已交接**"))
        assertTrue(md, md.contains("停在结算页"))
    }
```

（`transcriptWithOutcome` 若文件里没有同名 helper，就按该测试文件现有 sample 构造方式写一个 3 行的私有 helper。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.androiduse.log.MarkdownExporterTest' -q`
Expected: 断言失败（输出里是 `**中止**`）。

- [ ] **Step 3: 实现文案三处**

`MarkdownExporter.kt:27`：

```kotlin
            append("- 结果：").append(when { o.handoff -> "**已交接**"; o.finished -> "**完成**"; else -> "**中止**" })
```

`TaskAdapter.kt:37-41` 与 `TranscriptActivity.kt:58-62` 的 `when` 都在 `o.finished` 前加一支：`o.handoff -> "⇥ 已交接：${o.summary}"`。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 绿。

- [ ] **Step 5: `ScreenHandover`**

```kotlin
package com.androiduse

import android.util.Log
import com.androiduse.capability.DisplayTasks
import com.androiduse.capability.HandoverPlan
import com.androiduse.root.RootShell

/**
 * 接管：把虚拟屏上的任务搬到物理屏（spec handoff §4）。只在用户点"在手机上继续"后调用；
 * 全部阻塞（root shell），必须在 IO 线程。搬完核对顶层任务已在 display 0 第一位，再销毁已空的虚拟屏。
 */
object ScreenHandover {
    private const val TAG = "ScreenHandover"
    private const val OWN_PKG = BuildConfig.APPLICATION_ID

    private fun tasksOn(displayId: Int): List<DisplayTasks.Task> =
        DisplayTasks.parse(RootShell.execArgv(listOf("dumpsys", "activity", "activities")).stdout)[displayId] ?: emptyList()

    /** 虚拟屏上有没有可接管的页面（非本 App 任务）。 */
    fun hasTakeoverTarget(): Boolean {
        val s = ScreenSession.screen ?: return false
        return HandoverPlan.targetId(tasksOn(s.logicalDisplayId), OWN_PKG) != null
    }

    fun takeover(): Result<Unit> {
        val s = ScreenSession.screen ?: return Result.failure(IllegalStateException("没有虚拟屏"))
        val tasks = tasksOn(s.logicalDisplayId)
        val target = HandoverPlan.targetId(tasks, OWN_PKG) ?: return Result.failure(IllegalStateException("虚拟屏上没有可接管的页面"))
        for (id in HandoverPlan.order(tasks, OWN_PKG)) {
            val r = RootShell.execArgv(HandoverPlan.moveArgv(id))
            if (!r.ok) Log.w(TAG, "move-stack $id failed: ${r.stderr.ifBlank { r.stdout }}")
        }
        val physical = tasksOn(HandoverPlan.PHYSICAL_DISPLAY)
        if (physical.firstOrNull()?.id != target) {
            val left = tasksOn(s.logicalDisplayId).map { it.id }
            return Result.failure(IllegalStateException("没能把页面搬到手机屏幕（目标 #$target，仍在虚拟屏: $left）"))
        }
        ScreenSession.destroy()
        return Result.success(Unit)
    }
}
```

（`BuildConfig.APPLICATION_ID` 在 `com.androiduse` 包下可直接用；若 `buildFeatures.buildConfig` 未开，用 `"com.androiduse"` 常量并注释。）

- [ ] **Step 6: 布局与字符串**

`strings.xml` 加：

```xml
    <string name="action_takeover">在手机上继续</string>
    <string name="status_taking_over">正在切换到手机屏幕…</string>
    <string name="result_handoff">⇥ 已交接</string>
```

`activity_main.xml` 在 `tvResult` 的 `</TextView>`（`android:visibility="gone" />`）之后、同一父容器内加：

```xml
        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnTakeover"
            style="@style/Widget.AndroidUse.Button.Primary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginBottom="16dp"
            android:text="@string/action_takeover"
            android:visibility="gone" />
```

- [ ] **Step 7: `MainActivity`**

`showResult(finished, summary)` 改成接收 `AgentLoop.Outcome`：

```kotlin
    private fun showResult(outcome: AgentLoop.Outcome?) {
        val label = when {
            outcome == null -> getString(R.string.result_stopped)
            outcome.handoff -> getString(R.string.result_handoff) + "：" + outcome.summary
            outcome.finished -> getString(R.string.result_finished) + "\n" + outcome.summary
            else -> getString(R.string.result_unfinished) + "\n" + outcome.summary
        }
        binding.tvResult.text = label
        binding.tvResult.visibility = View.VISIBLE
        lifecycleScope.launch {
            val show = withContext(Dispatchers.IO) { ScreenHandover.hasTakeoverTarget() }
            binding.btnTakeover.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnTakeover.isEnabled = true
        }
    }
```

原来两处调用：`showResult(false, getString(R.string.error_screen_create_failed))` → 保留一个 `showError(text)` 私有方法只设 `tvResult`（按钮隐藏）；`showResult(outcome.finished, outcome.summary)` → `showResult(outcome)`；`CancellationException` 分支 → `showResult(null)`。

`onCreate` 里加：

```kotlin
        binding.btnTakeover.setOnClickListener { takeover() }
```

新增：

```kotlin
    private fun takeover() {
        binding.btnTakeover.isEnabled = false
        binding.toolbar.subtitle = getString(R.string.status_taking_over)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ScreenHandover.takeover() }
            binding.toolbar.subtitle = null
            updateScreenStatus()
            r.onSuccess {
                binding.btnTakeover.visibility = View.GONE
                moveTaskToBack(true)
            }.onFailure { e ->
                binding.btnTakeover.isEnabled = true
                Toast.makeText(this@MainActivity, e.message ?: "切换失败", Toast.LENGTH_LONG).show()
            }
        }
    }
```

（加 `import android.widget.Toast`。）任务开始时 `binding.btnTakeover.visibility = View.GONE`（与 `tvResult.visibility = View.GONE` 同处）。

- [ ] **Step 8: 编译、全量单测、安装**

Run: `./gradlew :app:testDebugUnitTest -q && ANDROID_SERIAL=3B658700ZQ400000 ./gradlew :app:installDebug -q`
Expected: 全绿、`Installed on 1 device.`

- [ ] **Step 9: 真机冒烟（不跑模型）**

```bash
export ANDROID_SERIAL=3B658700ZQ400000
adb shell 'su root am start -n com.androiduse/.MainActivity --es task "打开WLAN设置"'
```

等任务结束（logcat `AgentLoop` 出现 Finish），确认结果区出现"在手机上继续"按钮（`adb shell uiautomator dump` 不可用时用 `adb exec-out screencap -p > /tmp/m.png` 看图）。点按钮（`adb shell input tap` 到按钮中心，坐标从截图量）；随后 `dumpsys activity activities` 应显示设置任务在 `Display #0` 第一位、虚拟屏 `Display #N` 段消失、本 App 不在前台。把观察结果写进报告。

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/androiduse/ScreenHandover.kt app/src/main/java/com/androiduse/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/androiduse/ui/TaskAdapter.kt app/src/main/java/com/androiduse/TranscriptActivity.kt app/src/main/java/com/androiduse/log/MarkdownExporter.kt app/src/test/java/com/androiduse/log/MarkdownExporterTest.kt
git commit -m "feat(handoff): ScreenHandover 搬任务到物理屏 + 结束卡片"在手机上继续"按钮 + 交接文案

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: 真机验收（spec §7）+ 文档

**Files:**
- Modify: `docs/superpowers/specs/2026-09-18-handoff-takeover-design.md`（追加 `## 8. 验收结果`）
- Modify: `docs/DESIGN.md`（§9 阶段状态加一行）

- [ ] **Step 1: 三个任务各跑一次（App 进程，先唤醒：`input keyevent KEYCODE_WAKEUP; input keyevent 82`）**

```bash
export ANDROID_SERIAL=3B658700ZQ400000
adb shell 'su root am force-stop com.sankuai.meituan.takeoutnew; su root am start -n com.androiduse/.MainActivity --es task "在美团外卖上点一杯霸王茶姬的伯牙绝弦"'
adb shell 'su root am start -n com.androiduse/.MainActivity --es task "导航去深圳北站"'
```

滑块验证码：美团 force-stop 后连续快速操作会触发（2026-09-18 复现过）；若这次没触发，记录"未触发"，不硬造。

每个任务后：transcript 末行 `handoff`/`finished`；点按钮前 `dumpsys activity activities | grep -m1 topResumedActivity` 不变；点按钮后目标任务在 `Display #0` 第一位、虚拟屏段消失；美团**不真付款**（停在结算页即可）。

- [ ] **Step 2: 写 spec §8 表格（# | 任务 | 结束状态 | 步 | 交接 reason | 接管结果 | 备注）与结论，DESIGN §9 加一行**

`docs/DESIGN.md` 阶段 2 状态行后加：`- **交接与接管（2026-09-18）**：`handoff` 工具（付款/人机验证）+ 结束卡片"在手机上继续"（`am display move-stack` 搬任务到物理屏），验收见 spec `2026-09-18-handoff-takeover-design.md` §8。`

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-18-handoff-takeover-design.md docs/DESIGN.md
git commit -m "docs(handoff): 真机验收结果与 DESIGN §9 状态

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
