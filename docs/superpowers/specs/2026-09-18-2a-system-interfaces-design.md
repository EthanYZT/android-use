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
