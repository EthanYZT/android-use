# 交接与接管（handoff / takeover）设计

日期：2026-09-18 · 前置：2a 系统接口（已合并）、1e 不可见虚拟屏 · 状态：待实现

## 1. 目标

Agent 在不可见虚拟屏上把任务做到**需要本人操作的那一步**就停下，用户在本 App 的结束卡片上点一下"在手机上继续"，
那一页连同当前状态（购物车、路线、验证码页）搬到物理屏前台，由用户接着做。两种情况必须交接，其余 agent 自己做完：

| 情况 | 谁做 | 说明 |
|---|---|---|
| 付款 / 提交订单 / 结算确认 | **交接** | 停在结算页或"去支付"前一页 |
| 人机验证（滑块、选图、旋转等验证码） | **交接** | 停在验证码页，**绝不自己拖滑块/选图** |
| 短信验证码 | agent | 不交接；现阶段只能从通知/界面读，后续给 `sms_read_latest` 工具（不在本 spec） |
| 发送短信、拨出电话、开始导航、登录页"本机号码一键登录"以外的普通点击 | agent | 直接做 |

不只交接时才有按钮：**任何结束状态**（finish / handoff / 中止）下，只要虚拟屏上还有任务，卡片都显示"在手机上继续"。
高德场景：模型自己点完"开始导航" finish，用户点按钮，导航页到手机上。

## 2. 模型接口

新增工具 `handoff`：

```json
{"name":"handoff","description":"任务做到需要本人操作的一步（付款/提交订单/结算确认，或滑块/选图等人机验证）时调用，停在那一页不要再点。reason 写清停在哪、用户接着要做什么。","parameters":{"type":"object","properties":{"reason":{"type":"string"}},"required":["reason"]}}
```

提示词新增规则（`PromptBuilder.HANDOFF_RULE`，与 `SYSTEM_TOOLS_RULE` 并列）：

> 付款、提交订单、结算确认，以及滑块/选图这类人机验证，必须由用户本人操作：做到那一页就调用 handoff 说明停在哪，不要自己点支付、不要拖滑块。短信验证码不算，自己从通知或界面读。发送短信、拨出电话、开始导航可以直接做。

`handoff` 与 `finish` 一样结束循环；批内 handoff 后面的动作标记未执行（同 finish）。

## 3. 循环与记录

- `Action.Handoff(reason)`；`ToolCallResolver` 解析 `"handoff"`。
- `AgentLoop.Outcome` 增加 `kind: Kind`（`FINISHED` / `HANDOFF` / `ABORTED`），`finished` 保留（= kind == FINISHED）向后兼容；handoff 时 `summary` = reason。
- `TranscriptSink.outcome(t, finished, summary)` → 增加 `handoff: Boolean`；JSONL 末行多一个 `"handoff":true` 字段，旧记录缺省 false。`StoredOutcome` 同步。
- 历史列表 / 详情页 / Markdown 导出：交接显示为 `⇥ 已交接：<reason>`（完成 ✓、中止 ✗ 不变）。
- `AgentCli` 打印 `RESULT: handoff=true …` 后照旧销屏（CLI 没有按钮）。

## 4. 接管（takeover）机制

真机已验证（2026-09-18，ColorOS 15）：`am display move-stack <taskId> 0` 能把虚拟屏上的任务**连状态一起**搬到物理屏前台，
之后销毁虚拟屏它不受影响；`am stack move-stack` 在本 ROM 不存在。

`ScreenHandover`（Android 侧，root argv）：

1. `DisplayTasks.parse(dumpsys activity activities)` 取虚拟屏 `logicalDisplayId` 上的任务列表（自顶向下）。
2. 按 `HandoverPlan.order(tasks)`（纯逻辑）**从底到顶逐个** `am display move-stack <id> 0`：先搬底部的，顶层任务最后搬，
   结果是它在物理屏前台、其余落到后台/最近任务（用户接受残留任务留在后台）。
3. 再 dump 一次核对：顶层任务已在 display 0 且是第一个 → 成功；否则返回失败原因（哪些任务没搬动）。
4. 成功后 `ScreenSession.destroy()`（虚拟屏已空，销毁不伤任务；下次任务 `ensure()` 会重建并起设置页），
   然后 `MainActivity.moveTaskToBack(true)`，用户直接看到搬过来的页面。

不搬本 App 自己的任务（`com.androiduse` 永远排除，复用 2a 的排除规则）。整个过程只在用户点按钮后发生——物理屏红线不变。

## 5. UI

- `activity_main.xml` 结果区（`tvResult`）下加按钮 `btnTakeover`，文案"在手机上继续"。
- 显示条件：任务已结束（任何 kind）且 `ScreenSession.screen != null` 且虚拟屏上有非本 App 任务（IO 线程查一次 `DisplayTasks`）。
- 点击：按钮置灰 + "正在切换…"，IO 线程跑 `ScreenHandover.takeover()`；成功 → `moveTaskToBack`；失败 → Toast 原因，按钮恢复。
- 交接结束时 `tvResult` 显示 `⇥ 已交接：<reason>`。

## 6. 测试

纯逻辑单测：`ToolCallResolver` 解析 handoff；`PromptBuilder` 声明 handoff 工具且系统提示含规则关键词；`AgentLoop` 收到 handoff 后
Outcome.kind == HANDOFF、summary == reason、批内后续动作未执行（harness FakeEnv）；`TranscriptCodec` outcome 带 `handoff` 往返、旧记录缺省 false；
`HandoverPlan.order` 从底到顶且顶层最后、排除本包；`HandoverPlan.moveArgv(id)` 为 `am display move-stack <id> 0`。
Android 侧（`ScreenHandover`、按钮）不做 JVM 单测，靠真机验收。

## 7. 验收（真机，App 进程）

| # | 任务 | 期望 |
|---|---|---|
| 1 | 在美团外卖上点一杯霸王茶姬的伯牙绝弦 | 模型进到结算页调 handoff（reason 提付款）；点按钮后美团结算页在物理屏前台，购物车原样 |
| 2 | 触发美团滑块验证（force-stop 后快速操作可复现） | 模型不碰滑块、调 handoff；点按钮后滑块页在物理屏 |
| 3 | 导航去深圳北站 | 模型 navigate → 路线页 → 自己点开始导航 → finish；点按钮后导航页在物理屏 |

每次核对：点按钮前物理屏顶层不变；点后 `dumpsys` 显示目标任务在 display 0 顶层、虚拟屏已销毁；`content://sms` / 订单不产生副作用（不真付款）。

## 8. 验收结果（2026-09-18 晚，App 进程，`cd90a1f`）

| # | 任务 | 结束状态 | 步 | 交接 reason | 接管结果 | 备注 |
|---|---|---|---|---|---|---|
| 1 | 在美团外卖上点一杯霸王茶姬的伯牙绝弦 | handoff | 10 | "已选好…切换为到店自取…停在结算页，请勾选协议后点立即支付" | ✅ `OrderConfirmActivity` 任务 #297 到物理屏最前，虚拟屏 #68 消失，本 App 退后台 | 第 2 步后全部按 id 点（元素列表修复生效）；未付款 |
| 2 | 触发美团滑块验证码 | — | 5 | — | — | 未触发（force-stop 后搜索→店铺页 5 步干净完成）；不硬造 |
| 3 | 导航去深圳北站 | finish | 4 | — | ✅ 高德任务 #299（nonResizable）到物理屏最前，虚拟屏 #69 消失 | navigate → 路线(id) → 开始导航(id) → finish，模型自己启动了导航 |

按钮点击前物理屏顶层始终是本 App（`am start` 起的调试入口）；点击后 `dumpsys` 显示目标任务在 `Display #0` 第一位、虚拟屏段消失。
搬过去的其他任务（设置页、之前的美团页）落在 display 0 后台。无副作用：未付款、未发消息。

**结论：通过。** 滑块交接（#2）留待自然触发时补验；提示词规则已明令不碰滑块。
