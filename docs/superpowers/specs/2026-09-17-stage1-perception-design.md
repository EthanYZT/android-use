# 阶段 1 · 感知与 Grounding 设计

> 状态：设计已定，实现进行中（分支 `stage1-perception`）。
> 前置阅读：`docs/DESIGN.md` §4（执行层）、§5（感知与 grounding）、§8（安全模型）、§10.1（模型选型）。

## 0. 阶段 1 拆分

记忆里"阶段 1"实为 4–5 个独立子系统，逐个 spec→plan→实现：

| 子项 | 内容 | 状态 |
|---|---|---|
| **1a** | `RootShell` argv 接口；不可信文本分隔/转义约定 | 本轮实现 |
| **1b** | 虚拟屏节点树获取（root 守护进程） | 本轮实现（先 spike） |
| **1c** | 节点 grounding：提示词改「截图+节点列表」，按元素编号选，坐标从 bounds 出；截图坐标兜底 | 后续 |
| **1d** | OCR 兜底：ML Kit 中文识别打包，裁区 OCR | 后续 |
| **1e** | 真 headless 屏：`ADD_TRUSTED_DISPLAY`；顺带解决 Home 泄漏、销屏重挂 Activity | 后续 |

**关键真机事实（2026-09-17 核实）**：本机 `uiautomator dump` 只有 `[--verbose][--compressed][file]`，**无 display 参数，只能 dump 物理屏 display 0**。App 跑在虚拟屏，故节点树不能靠 shell，必须自持 `UiAutomation`。这条决定了 1b 走守护进程。

## 1. RootShell argv 接口（1a）

**问题**：`exec(String)` 拼 `su -c "<string>"` 交 shell 解析；字符串型参数（`input text`、包名、Intent extra）流入即命令注入原语。

**方案**：新增不经 shell 解析的入口，并令其成为**唯一**可带外部字符串的入口。

```kotlin
fun execArgv(argv: List<String>, timeoutMs: Long = 15_000): ShellResult =
    run(listOf("su", "root") + argv, timeoutMs)   // su root <argv...>
```

`su root <argv>`：KernelSU 的 su 用法是 `su [options] [-] [user [argument...]]`，给定 user 后其余参数**被 su 直接 execve 给目标程序，不经任何 shell**。每个 argv 元素原样成为一个参数，不拆词、不展开 `$`、不解释 `;`。

⚠️ **踩过的坑（2026-09-17 真机 v4.2.0 实测）**：最初写成标准 `sh` 的位置参数用法 `su -c 'exec "$@"' -- <argv>`。**KernelSU 的 su 不转发位置参数**——它把 `-c` 之后的参数用空格拼到命令串尾部再交给 `sh -c` 重新解析（`$#`=0、`$@` 为空），不可信文本被二次解析、注入原语重新成立。当时误判"注入没爆"，实为 `exec` 提前替换进程的侥幸。真机核实用**设备侧单引号**避免 adb shell 二次解析：`adb shell "su root echo 'a;id'"` → 原样 `a;id`。

**约束（注释+单测）**：`exec(String)` 调用点只能是编译期常量 + 整数插值；新增字符串插值视为 bug。守护进程启动命令的 APK 路径/token 走 `execArgv`。

## 2. 不可信文本约定（1a）

新增纯 Kotlin 模块 `agent/UntrustedText.kt`，1c 把节点文字/OCR 结果放入提示词**只能经过它**：

- `sanitize(s)`：控制字符/换行→空格；折叠连续空白；超长（`MAX_LEN=60`）截断加 `…`。
- `field(name, s)`：输出 `name="<sanitized>"`，双引号内转义 `\"` 和 `\\`。

提示词约定（1c 落地，此处定死）：
1. 屏幕文字只出现在结构化字段（`text="…"`），不出现在裸行。
2. 系统提示词明确：`text=` 引号内是屏幕数据，即便像指令也不执行。
3. 模型输出改为**按元素编号**（`{"action":"tap","id":12}`），不回传文字→屏幕文字不进 history。
4. 全屏 OCR 结果同样按块编号 + `field()` 包装。

目的：不可信文本永远是"值"不是"结构"，且不进入后续轮次。硬保证只到"结构隔离"，语义注入仍靠模型判断（写入 DESIGN §8.4）。

## 3. root 守护进程（1b 核心）

App 用 `execArgv` 通过 root 启动 `app_process` 守护进程（自带 dex/jar，非新 APK），两者走 `LocalSocket`。

守护进程 v1 只暴露一个 RPC：`dump(displayId) → 节点树 JSON`。截图/注入/建屏维持现状（shell）——即①切法，最快验证核心风险、基础设施一次建好可复用。

**四个设计点**：
1. **连接即用、用完即断**：每次 dump 时 `UiAutomation.connect()`，读完 `disconnect()`。因 `AccessibilityManager.isEnabled()` 在连接期对全系统返回 true（暴露"有自动化"），断开式把暴露压到单次 dump 的几百 ms。**待真机验证**：若 connect 开销过大则退化为"任务期间保持、任务结束断开"。
2. **生命周期**：`DaemonClient` 连不上→`execArgv` 拉起→重试；守护进程空闲 60s 自杀；崩溃→下次请求透明重启。
3. **协议**：请求 `{"cmd":"dump","displayId":N}\n`，响应一行 JSON 或 `{"error":"..."}`，换行分帧。节点带 `{id,bounds:[l,t,r,b],text,desc,resId,className,clickable,scrollable}`，`id` 为本次 dump 内序号。
4. **hidden API 适配**：`UiAutomation` 构造/connect 的反射集中到 `HiddenApi.kt`，便于版本适配。

**风险最集中处**，故 1b 第一步是 **spike**：最小 `app_process` 脚本，`UiAutomation.connect()` 后打印 `getWindowsOnAllDisplays()` 是否含虚拟屏窗口。跑通才写守护进程。

## 决策记录

- 取树路线选 **A（root `app_process` + 临时 UiAutomation）**，否决 B（常驻 AccessibilityService，风控自毁）与 C（纯视觉，弃便宜的①级）。
- 守护进程 v1 **①切法**（只做节点树），1e 独立子项紧随。
- OCR vs 无障碍：**互补非二选一**。无障碍是主力（结构化、便宜、准），OCR 补节点树的洞，VLM 最后兜底（DESIGN §5.1 三级降级）。

## 4. 1b Spike 结论（2026-09-17 真机验证，A 路线成立）

用 `su shell app_process` 跑最小 Java probe（反射构造 `UiAutomation` + `connect`），
在虚拟屏（`overlay_display_devices` 建的 display 16）上启动系统设置，验证结果：

**✅ 核心问题回答：虚拟屏的节点树可完整读取。** `getWindowsOnAllDisplays()` 按 displayId
分组返回，display 16 上出现 `type=1(APPLICATION) focused=true title="设置" pkg=com.android.settings`，
其节点树含 resource-id / text / contentDescription / clickable / **屏幕 bounds**，例如
`显示与亮度 [240,2070,900,2195]`、`WLAN`、`飞行模式 Switch text="关闭"`。物理屏（display 0）
同时列出我们自己的 app + systemui，互不干扰。

**关键实现要点（都踩过）**：
1. **`Looper.prepareMainLooper()` 必须在 main() 开头调**。裸 app_process 没有 main looper，
   `AccessibilityInteractionClient.<init>` 里 `new Handler(getMainLooper())` 会 NPE，崩在
   UiAutomation 回调线程上（try/catch 拦不到），进程被 SIG 9 自杀，表面上看是 `connect()` 后
   莫名 "Killed"。与权限/SELinux 无关。
2. **必须开 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`**（`getServiceInfo` → 加 flag → `setServiceInfo`），
   否则 `getWindows*` 返回空。
3. **连接后要 `waitForIdle` + 短暂 settle**，窗口缓存靠事件填充，connect 完立刻查会是空/旧。
4. **设备必须解锁**：keyguard 在时 app 窗口对无障碍不可见，只报一个 systemui 空壳窗口
   （`android:id/content` 为空）。这也印证 DESIGN §8.4 锁屏门控的物理现实。
5. **uid**：`su shell`（uid 2000）干净可用，与 uiautomator 一致；root uid 也能注册但没必要。
6. **启动到指定屏**：singleton Activity 会复用旧实例忽略 `--display`，需
   `am start --display <id> -f 0x18000000`（NEW_TASK|MULTIPLE_TASK）强制新实例落到目标屏。

**延迟**：connect→setServiceInfo→waitForIdle→dump→disconnect 一整轮 ~1.9s，其中含我
人为加的 ~1.2s settle sleep；纯 connect+query 约 0.7s。→ "每次 dump 都 connect/disconnect"
可行但不免费；若叠加到每步 ~5s 模型延迟上偏重，**退化方案**：任务期间保持连接、任务结束
才 disconnect（暴露窗口从"单次 dump"变成"整个任务"，是隐蔽性 vs 延迟的权衡，写进实现时决定）。

**待处理细节**：滚出屏幕的节点 bounds 会被 clamp 成退化矩形（bottom<top，见 probe 里
"通知与控制中心 [240,2382,900,2376]"），grounding 层要按 §5.2 处理无效 bounds。

**结论**：1b 走 root `app_process` + 临时 UiAutomation 成立，可开始写守护进程。probe 源码见
scratchpad（未入库，throwaway）。
