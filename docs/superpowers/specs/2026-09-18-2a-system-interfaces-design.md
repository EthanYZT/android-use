# 阶段 2a · 系统接口能力层设计

日期：2026-09-18。对应 `docs/DESIGN.md` §3（能力路由 ② 系统接口）与 §9 阶段 2 前半。
阶段 2b（MCP 客户端）另开 spec。

## 0. 目标与边界

**目标**：闹钟 / 日历 / 联系人 / 短信 / 拨号 / 导航 / 设置页 七类能力做成模型可直接调用的工具，
任务能走协议就不走 GUI；Intent 停在中间页（短信编辑页、拨号盘、地图）时由现有 GUI 循环接手。

**决策**（与用户逐条确认）：
- 能力范围：一整组九个工具（见 §2），不砍。
- 路由：**软路由**。工具平铺进现有 tool 列表，系统提示加"优先用系统接口工具"规则，模型自选。
  否决硬路由（任务前意图分类强制走接口）：多一轮请求、混合任务不好处理，YAGNI。
- Provider 访问：**App 进程直接用 `ContentResolver`**，Manifest 声明权限，首次调用未授权时走 root
  `pm grant` 一次性自授。`AgentCli` 跑在 root app_process（uid 0）里天然全权限，同一份代码。
  否决走守护进程（职责混、要加通用 query/insert 协议）与 root `content` 命令解析文本（值含逗号即崩）。

**不做**：确认门（阶段 3；本阶段只打副作用标签）、直接发送短信 / 直接拨出 / 删日历事件（都要门）、
重复事件改期、定时器、联系人写入、`CALL` 权限。

## 1. 架构与代码组织

- **模型侧**：九个新工具加进 `PromptBuilder.toolsJson()`；系统提示加两条：
  1. 「现在是 YYYY-MM-DD HH:mm 星期X」——每步生成请求体时从 `Transcript.startedAtMs`（任务开始时间）
     投影，模型算"明天""下周二"靠它；
  2. 「闹钟/日历/联系人/短信/拨号/导航/设置页有专用工具，能用就用，不要在界面里一步步点；工具停在中间页
     时再用界面操作接着做」。
- **解析侧**（纯 Kotlin，可单测）：新包 `com.androiduse.capability`。
  - `SystemCall` sealed class，每个工具一个 data class，字段已经校验完毕（时间解析成 epoch ms、号码只留
    `+` 和数字、设置页是枚举、日历 id 是 Long）。
  - `SystemCallParser.parse(name, argumentsJson, nowMs): Result`——用 `ResponseParser` 的锚定式字段读取，
    失败返回错误文本（"start 要 `2026-09-22 15:00` 格式"）。
  - `ToolCallResolver` 对九个名字调用它，成功包成 `Action.System(call)`，失败 `Resolution.Err`。
- **执行侧**：`Environment` 加 `performSystem(call: SystemCall): SystemResult(ok, text)`；`text` 直接作为
  tool 消息内容。`AndroidEnvironment` 委托给 `SystemInterfaces(context, screen)`：
  - Intent 类（闹钟/短信/拨号/导航/设置页）→ `SystemIntents.argv(call, displayId, mapPackage)` 纯函数拼
    `am start --display <虚拟屏> -f 0x18000000 -a … -d … --es …`，走 `RootShell.execArgv`（不经 shell 二次解析）。
    非零退出 → 失败。成功后 settle 1.5s（与 open_app 相同）。
  - Provider 类（日历查/建/改、联系人查）→ `CalendarStore` / `ContactsStore` 用 `ContentResolver`；
    调用前 `PermissionGrant.ensure(context, perms)`：未授权则 `pm grant com.androiduse <perm>` 走 execArgv，
    仍未授权则失败并说明缺哪个权限。
- **`AgentLoop`**：批处理里 `Action.System` 走 `env.performSystem`，`Execution.result` 用返回的 text
  （成功失败都用），其余逻辑（失败即停本批、连续 3 步失败中止）不变。
- **副作用标签**：`SystemCall.sideEffect: SideEffect { NONE, DEVICE_STATE, SHARED }`。只是数据，2a 不接门。
- **不改**：守护进程、协议、显示、感知链路一律不动。

## 2. 九个工具

时间一律用设备本地时区 `YYYY-MM-DD HH:mm`（全天事件 `YYYY-MM-DD`），解析严格，不做自然语言。
号码只允许 `+` 与数字，3–20 位。所有 `am start` 带 `--display <虚拟屏> -f 0x18000000`。

| 工具 | 参数 | 执行 | 回给模型的 text | 标签 |
|---|---|---|---|---|
| `set_alarm` | `hour` 0–23, `minute` 0–59, `label?` | `-a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR --ei …MINUTES --es …MESSAGE --ez …SKIP_UI true` | "已请求时钟设置 07:30 闹钟；要核对可 open_app 时钟" | DEVICE_STATE |
| `calendar_query` | `from?`, `to?`（默认今天 00:00 起 7 天） | 查 `content://com.android.calendar/instances/when/<begin>/<end>`（展开重复事件），按 `begin` 排，最多 50 条 | 每行 `id=<event_id> 09-22 15:00–16:00 <title> @<location>`；全天写 `全天`；空 → "这段时间没有事件" | NONE |
| `calendar_create` | `title`, `start`, `end?`（默认 +1h）, `location?`, `all_day?` | 插 `events`：`calendar_id` = 第一个 `account_type=LOCAL` 的日历（无则第一个可见日历），`eventTimezone` = 设备默认 | "已创建事件 id=N" | SHARED |
| `calendar_update` | `id`, `title?`/`start?`/`end?`/`location?` 至少一个 | 先查该 `_id` 存在；`rrule` 非空 → 拒绝"重复事件暂不支持改期"；只给 `start` 不给 `end` 时保持原时长；update 影响行数须为 1 | "已更新事件 id=N（start,end）" | SHARED |
| `contacts_lookup` | `name` | 查 `content://com.android.contacts/data`，`mimetype=phone_v2`，`display_name LIKE %name%`，最多 10 条 | 每行 `<姓名> <号码> (<类型>)`；空 → "没找到叫 X 的联系人" | NONE |
| `sms_compose` | `number`, `body` | `-a android.intent.action.SENDTO -d smsto:<num> --es sms_body <body> -p com.android.mms`（不指定包 WhatsApp 也接） | "已打开短信编辑页，收件人与正文已填，尚未发送" | NONE |
| `dial` | `number` | `-a android.intent.action.DIAL -d tel:<num>` | "已打开拨号盘并填入号码，未拨出" | NONE |
| `navigate` | `query` | `-a android.intent.action.VIEW -d geo:0,0?q=<urlencode>`，`-p` 取偏好序 `com.autonavi.minimap` → `com.baidu.BaiduMap` → `com.tencent.map` 中第一个已安装的；都没有不指定包（系统弹选择器，由 GUI 接手） | "已在高德地图打开 天安门" | NONE |
| `open_settings` | `page` 枚举 | 映射表 → `-a android.settings.<X>_SETTINGS` | "已打开 WLAN 设置页" | NONE |

`open_settings` 枚举（本机全部解析到具体 Activity）：`wifi, bluetooth, display, sound, battery_saver, apps, date, location,
notification, accessibility, nfc, storage, security, input_method, about, network, home`。未知值报错附可选列表。

文本安全：Provider 读回的 title/location/姓名/号码类型都是不可信数据，逐字段过 `UntrustedText.sanitize`。

## 3. 提示词、错误处理

- 工具描述里写清时间格式与"停在中间页"语义（`sms_compose` 不发送、`dial` 不拨出）。
- 参数校验失败、Provider 抛异常（信息 `take(120)`）、`am start` 非零退出、id 不存在、权限授不下来，
  全部是 `Execution(ok=false, result=原因)` 回给模型，走现有中止逻辑，不新增中止路径。

## 4. 测试

- **JVM 单测**：`SystemCallParser`（时间/全天/号码/枚举/缺参/默认值/边界）、`SystemIntents`（argv：display、
  flags、extras、urlencode、包名偏好序）、`CalendarText`/`ContactsText`（行格式化与 sanitize）、
  `ToolCallResolver` / `PromptBuilder`（工具声明、时间行、规则）/ `AgentLoop`（`Action.System` 走
  `performSystem`，text 回填 tool 消息，失败停本批）各 2–3 个。
- Provider 读写与 Intent 落屏不做 JVM 单测，靠真机。

## 5. 真机验收（DESIGN §9 阶段 2：走系统接口完成，不经 GUI）

准备：`content insert` 造 2 条事件（明天下午一条）、1 个联系人「张伟」。

| # | 任务 | 期望路径 |
|---|---|---|
| 1 | 设一个 7:30 的闹钟 | `set_alarm` → finish（可选 open_app 时钟核对） |
| 2 | 把明天下午的会改到下周二 15:00 | `calendar_query` → `calendar_update` → finish |
| 3 | 给张伟发短信说我晚点到 | `contacts_lookup` → `sms_compose` → 停在编辑页 finish |
| 4 | 导航去天安门 | `navigate`（高德） → finish |
| 5 | 打开 WLAN 设置 | `open_settings(wifi)` → finish |

判定：1–4 全程无 tap/swipe 即到达目标状态；物理屏顶层始终不变；`set_alarm` 的 `SKIP_UI` 在 ColorOS
`HandleApiActivity` 上是否真的不弹页面，验收时确认（不成立则退回不带 SKIP_UI，由 GUI 点确认）。

## 6. 探针记录（2026-09-18 OnePlus Ace 5）

- `SET_ALARM` / `SET_TIMER` → `com.coloros.alarmclock/com.oplus.alarmclock.cts.HandleApiActivity`；闹钟 Provider 不可读。
- `SENDTO smsto:` → `com.android.mms/.ui.conversation.LaunchConversationActivity`（WhatsApp 也接，故指定包）。
- `DIAL tel:` → `com.android.contacts/.DialtactsActivityAlias`。
- `geo:` → 高德 / 百度 / 滴滴 三家都接，裸 Intent 弹 `ResolverActivity`。
- 日历：`_id=1 local account (LOCAL)`，其余是 heytap 生日/纪念日/倒数日；当前无事件、无联系人。
- 15 个 `android.settings.*` 全部解析到具体 Activity（WLAN/蓝牙/NFC 在 `com.oplus.wirelesssettings`）。

## 7. 验收结果（2026-09-18 晚，部分通过，实现已全部落地在 `stage2a-system-interfaces` 分支）

实现：Task 1–7 全部完成并逐任务审查通过（单测 249 绿，`installDebug` 成功）。跑法：AgentCli（root app_process）与 App 进程
（`am start -n com.androiduse/.MainActivity --es task '…'`）各跑了一部分；物理机同时接了一个模拟器，adb 必须 `-s 3B658700ZQ400000`。

| # | 任务 | 完成 | 步 | 工具序列 | tap/swipe | 备注 |
|---|---|---|---|---|---|---|
| 1 | 设 7:30 闹钟 | ✅ | 2 | set_alarm → finish | 0 | ColorOS `HandleApiActivity` 认 `SKIP_UI`：不弹页面，虚拟屏只出现"距离下次响铃还有 14 小时"提示 |
| 2 | 明天下午的会改到下周二 15:00 | ✅（App 进程） | 3 | calendar_query → calendar_update → finish | 0 | Provider 里 dtstart 确认已变为 09-22 15:00；首次调用走 root `pm grant` 自授成功 |
| 2' | 同上（AgentCli） | ❌ | 10 | calendar_query 报错 → GUI 兜底耗尽步数 | 8 | **AgentCli 不能用 Provider 类工具**：systemMain 系统 Context 的 ContentResolver 抛 `SecurityException: Unable to find app for caller IApplicationThread`（裸 app_process 没有注册的 app 进程）。Provider 工具只能在 App 进程用 |
| 3 | 给张伟发短信说我晚点到 | ❌ **红线** | 10 | contacts_lookup 报错(同上，CLI) → GUI 查到号码 → sms_compose | 6 | `sms_compose` 的 `am start` 成功，但短信编辑页**落到了物理屏**（`LaunchConversationActivity` 是跳板，它以 `NEW_TASK|NEW_DOCUMENT|CLEAR_TASK` 二次启动 `ConversationActivity`，新任务落 display 0）。虚拟屏上模型看不到编辑页，反复点联系人页的"短信"按钮 |
| 4 | 导航去天安门 | ✅ | 10 | navigate → GUI 接手 | 8 | 高德在虚拟屏打开；首次启动隐私页 + 登录弹窗 + "网络不佳"各吃步数，最终进入驾车导航界面。跳板 `SchemeHandleActivity` **没有**泄漏到物理屏 |
| 5 | 打开 WLAN 设置 | ✅ | 2 | tap(WLAN) → finish | 1 | 虚拟屏起手就是设置首页，模型直接点了 WLAN 而没用 open_settings（软路由"能用就用"不够强）；息屏状态的另一次跑用了 open_settings，`am start` 成功 |

**结论：未通过。** 1/2/4 走协议成立；3 触碰"Agent 不抢占物理前台"红线，必须修；5 可接受。

**发现与待办（下一会话）**：
1. **短信跳板泄漏（必修）**：手动验证 `am start --display <虚拟屏> -f 0x18000000 -a SENDTO -d smsto:… --es sms_body … -n com.android.mms/.ui.conversation.ConversationActivity`
   直接起目标 Activity 时任务留在虚拟屏（销屏后随之销毁，物理屏顶层不变）。方案：`sms_compose` 解析 SENDTO 处理者后，对已知跳板
   （本机 `LaunchConversationActivity`）改起真正的会话 Activity；通用做法可在 `am start` 后用 `dumpsys activity` 核对新任务所在 display，
   落到 0 就报失败而不是谎报成功（"停在中间页由 GUI 接手"的前提是页面真的在虚拟屏上）。`dial` 同样手动起过一次（`DialtactsActivityAlias`），
   销屏后消失，判断在虚拟屏，但未经模型 E2E。
2. **AgentCli 与 Provider**：`SystemInterfaces` 对 CLI 应给出明确文案（"CLI 不支持日历/联系人工具，请用 App"），或 CLI 改走 `IActivityManager.getContentProviderExternal`（shell `content` 命令的路径）。
3. **测试前置**：物理机息屏（Dozing）时虚拟屏显示锁屏时钟，模型会瞎点；跑 E2E 前 `input keyevent KEYCODE_WAKEUP` + `keyevent 82`。
4. 造的数据仍在机上：日历事件 `_id=1 周会`（已改到 09-22 15:00）、`_id=2 牙医`，联系人 张伟 13800000000（raw_contact 1）；时钟里多了一个 07:30 闹钟。
5. 物理屏一度被短信编辑页顶到前台，已 `am force-stop com.android.mms` 清掉；跑完后物理屏顶层是本 App（App 进程跑 ② 留下的）。
6. SDD 收尾未做：整分支终审（final review）与 `finishing-a-development-branch` 留到下一会话；账本在 `.superpowers/sdd/2026-09-18-2a-system-interfaces/progress.md`。
