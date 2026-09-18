# 1e：真 headless 虚拟屏（守护进程持屏）设计

日期：2026-09-18。分支 `stage1-perception`。阶段 1 子项 1e，提到 1d（OCR）之前做。
上游：`docs/DESIGN.md` §4.2（后台执行：headless VirtualDisplay）、§8.7（隐私）；
`2026-09-17-stage1-perception-design.md` §0 表格 1e 行、§6 open_app 遗留问题。

## 1. 问题

阶段 0 用 `overlay_display_devices` 建虚拟屏是捷径，留下三个用户可感知的问题：

1. **可见**：它是开发者选项的"模拟辅助显示设备"，在物理屏上叠一个悬浮窗，Agent 干活全程被看见。
2. **销屏重挂**：销屏时系统把虚拟屏上的任务搬到 display 0，任务结束后最后一个 App 直接成为物理屏前台
   （open_app 落地后每次演示都撞到，见 stage1 spec §6）。
3. **空屏镜像**：虚拟屏上没有 App 时镜像物理屏，模型看到本 App 自己的界面（隐私泄漏 + 推理暴涨）。

另有两处工程债顺带解决：截图走 `screencap -d <SurfaceFlinger长id>` 经 `/data/local/tmp` 中转
（shell 可读目录，§8.7 有瑕疵）；建屏改全局 setting 跨重启持久化，需要 dumpsys 轮询配对两套 id。

## 2. 目标与验收

1. 虚拟屏在物理屏上**完全不可见**。
2. 销屏后虚拟屏上的任务被**销毁**，不出现在物理屏；物理屏顶层 Activity 不变。
3. 空屏**不镜像**物理屏。
4. 现有真机 E2E（设置「打开显示与亮度」、「打开时钟」、「日历→计算器」）在新屏上照常跑通，
   `input -d`、节点 dump、截图三条链路都工作。
5. 不再有 `screencap`、`/data/local/tmp` 截图中转、`overlay_display_devices`、SurfaceFlinger id。

**非目标**：监工实时预览（决定：本轮不做，主屏步骤卡片已带每步截图；设置页保留"截一张看看"）；
按屏隔离的 HOME（spike 证实仍泄漏，继续拒绝提供）；锁屏下工作（§8.4 锁屏门控不变）。

## 3. Spike 结论（2026-09-18 真机，Android 16 / OnePlus Ace 5 / KernelSU root）

探针 `HeadlessProbe`（throwaway，未入库，源码在会话 scratchpad）逐项验证：

| 验证项 | 结果 |
|---|---|
| root `app_process` 里 `ActivityThread.systemMain().getSystemContext()` 取 `DisplayManager` | 可用（与 AgentCli 取 PackageManager 同路） |
| `DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)`，flags = `0x45c9` | 成功，displayId 分配为 8/9；dumpsys 显示 FLAG_TRUSTED / OWN_CONTENT_ONLY / DESTROY_CONTENT_ON_REMOVAL / OWN_FOCUS / ROTATES_WITH_CONTENT 均生效；owner `android (uid 0)` |
| 物理屏可见性 | 同一时刻 `screencap` display 0 是干净桌面，无任何悬浮窗 |
| 空屏 | ImageReader **没有任何帧**（不镜像） |
| `am start --display <id> -n com.android.settings/.Settings -f 0x18000000` | 成功，取到 1080×2376 非黑帧 |
| `UiAutomationFactory` + `NodeExtractor.extractForDisplay(id)` | 36 节点，与 overlay 屏一致 |
| `input -d <id> tap` | 生效（页面切到显示与亮度，dump 28 节点） |
| `input -d <id> keyevent 3` | **仍泄漏**：物理屏顶层从计算器变成 Launcher。Home 继续在 `ActionCommand` 拒绝 |
| `vd.release()` | 设置任务从 D9 消失，D0 任务列表前后完全一致，顶层仍是 Launcher；`dumpsys display` 不再列出 |
| 界面静止时连续 `acquireLatestImage` | 均为 null → 必须**保留最近一帧** |

flags 取值（`DisplayManager` hidden 常量）：PUBLIC `1<<0`、OWN_CONTENT_ONLY `1<<3`、SUPPORTS_TOUCH `1<<6`、
ROTATES_WITH_CONTENT `1<<7`、DESTROY_CONTENT_ON_REMOVAL `1<<8`、TRUSTED `1<<10`、OWN_FOCUS `1<<14`。
不加 SHOULD_SHOW_SYSTEM_DECORATIONS（`1<<9`）：会在屏上起一套桌面/导航栏，多一个 Launcher 进程、多一层
点错的可能；空屏黑屏 + open_app 已经够用。权限：root uid 0 在 `DisplayManagerService.validatePackageName`
与 `checkCallingPermission` 均直接放行，不需要 ADD_TRUSTED_DISPLAY 落到某个包上。

## 4. 方案（已选：守护进程建屏并持有）

否决项：App 进程自建屏（拿不到 TRUSTED，不受信任的屏不接受注入、Activity 启动受限）；
继续 overlay 并硬藏悬浮窗（无系统开关，重挂与镜像都解决不了）。

### 4.1 守护进程：`DisplayHost` + 协议 v2

**`DisplayHost`**（新，`com.androiduse.daemon`，Android 依赖集中处）：
- `create(w, h, dpi): Int`：systemMain 系统 Context → `DisplayManager.createVirtualDisplay`，
  Surface 来自 `ImageReader(w, h, RGBA_8888, maxImages=3)`，flags = 0x45c9。返回 displayId。
  同一时间只允许一块屏；已有屏时 `create` 返回现有 displayId（幂等，尺寸不同则先销后建）。
- `latestFrame(): Image?`：`OnImageAvailableListener`（专用 HandlerThread）里 `acquireLatestImage`，
  换掉并关闭上一帧，始终持有最近一帧。`maxImages=3` 保证持有一帧时生产者仍有缓冲。
- `encodeJpeg(maxWidth, quality): ByteArray?`：把最近一帧按 rowStride 拷进 Bitmap、裁掉 padding、
  缩放到 maxWidth、压 JPEG。**没有帧（空屏）返回 null**，由协议层回 `{"ok":true,"empty":true}`。
- `destroy()`：`VirtualDisplay.release()` + `ImageReader.close()` + 丢弃持有帧。
- 纯逻辑部分抽成 `FrameGeometry`（rowStride/pixelStride → Bitmap 宽、裁剪、缩放尺寸计算），可单测。

**协议 v2**（`DumpCodec` 改名为 `DaemonCodec`，仍是一行 JSON 请求 / 一行 JSON 响应，`cmd` 字段分派）：

| cmd | 请求字段 | 成功响应 |
|---|---|---|
| `create_display` | `w`, `h`, `dpi` | `{"ok":true,"displayId":N,"w":..,"h":..}` |
| `destroy_display` | — | `{"ok":true}` |
| `frame` | `maxWidth`（默认 720）, `quality`（默认 80） | `{"ok":true,"jpegBase64":"..."}` 或 `{"ok":true,"empty":true}` |
| `dump` | `displayId` | 不变 |
| `lease` | — | `{"ok":true}`，之后连接**保持打开**，守护进程读到 EOF 即 `destroy()` |

错误统一 `{"ok":false,"error":"..."}`。socket 名升到 `androiduse_daemon_v2`，旧守护进程若还在跑
（空闲 60s 内）不会被误连。

**生命周期**：
- 主循环仍是 accept → 一行请求 → 一行响应 → 关闭；`lease` 是唯一例外：响应后把连接交给一个
  "租约线程"阻塞读到 EOF，然后 `DisplayHost.destroy()`。同一时间最多一份租约；第二份到来时
  先替换（旧连接关闭不触发销屏——用代际号区分）。
- 空闲自杀条件改为：**没有屏、没有租约、且 60s 无请求**。
- UiAutomation 持连接策略不变（建屏不需要它；dump 才连，随进程退出断开）。
- 进程崩溃：屏随进程消失，DESTROY_CONTENT_ON_REMOVAL 保证任务被销毁，不泄漏到物理屏。

### 4.2 App 侧：删掉整条 shell 屏链路

- `VirtualScreen(logicalDisplayId, widthPx, heightPx)`：删 `surfaceFlingerId`。
- **删除** `VirtualDisplayManager`、`DisplayParser`、`DisplayParserTest`。`launchIntentAction`
  迁到 `DaemonClient` 或 `ScreenSession`（仍是 `am start --display <id> -a android.settings.SETTINGS`，
  走 `execArgv`）。
- `DaemonClient` 扩成 v2 客户端：`createDisplay(w,h,dpi)`、`destroyDisplay()`、`frame(maxWidth, quality)`、
  `dump(displayId)`、`openLease(): Closeable`。透明拉起逻辑不变。
- `ScreenSession.ensure()`：拉起守护进程 → `openLease()` → `createDisplay()` → 起设置（过渡起点，
  与 open_app 前一致）→ 记 `VirtualScreen`。中途任一步失败：关掉已开的租约、返回 null。`destroy()`：`destroyDisplay()` + 关租约。
  MainActivity `isFinishing` 时仍调 `destroy()`；即便没调到，App 进程死 → 租约 EOF → 守护进程销屏。
- `ScreenCapture.captureAsJpegBase64(screen)` 改为 `DaemonClient.frame(...)`：`empty` 时返回一张
  **纯黑 JPEG**（同尺寸，App 侧生成一次缓存），不是 null——黑屏是空屏的真实状态，模型该看到它；
  守护进程连不上或响应错误时才返回 null（真正的截图失败）；
  `AgentLoop` 现有的"截图失败且读不到节点才中止"逻辑不受影响。`capture(): Bitmap`（设置页调试用）
  从 JPEG 解码。`/data/local/tmp`、`cacheDir` 注入、`synchronized` 全删。
- `AndroidEnvironment.observe` 调用面不变。

### 4.3 `AgentCli`

不再自己建屏：与 App 一样通过 `DaemonClient`（拉起守护进程、`openLease`、`createDisplay`、起设置），
结束时 `destroyDisplay` + 关租约。`ScreenCapture.cacheDir` 注入删除。CLI 进程退出即租约 EOF，
哪怕 finally 没跑到也会销屏。

### 4.4 失败处理

| 情形 | 行为 |
|---|---|
| 守护进程拉不起 / `create_display` 失败 | `ScreenSession.ensure()` 返回 null，主屏提示建屏失败（现有文案） |
| 任务中守护进程死亡 | `frame`/`dump` 报错 → 该步观察退化（黑帧/无节点）→ 连续 3 步失败中止；`ScreenSession` 在 `ensure()` 时探测 `frame` 失败则置空重建 |
| App 崩溃 / 被杀 | 租约 EOF → 销屏 → 任务销毁；下次启动 App 重新建屏 |
| `create_display` 时已有屏 | 返回现有屏（幂等） |
| 第二个客户端（App 与 CLI 同时） | 共享同一块屏；租约以最后一份为准。不是本轮支持的场景，只保证不崩 |

### 4.5 安全与隐私

- 截图帧只在守护进程内存与 App 内存之间走 LocalSocket（abstract namespace，仅同机进程可连），
  不再落任何文件。§8.7 的"敏感截图不留 shell 可读目录"由此从"事后删除"变为"根本不落盘"。
- LocalSocket 现无鉴权，任何本机进程都能连守护进程要帧/建屏。这在 overlay 方案下同样存在
  （dump 已可读任意屏），本轮不扩大也不收窄；记入 §8 后续项：校验对端 uid 为本 App uid。
- 守护进程从"空闲 60s 自杀"变为"持屏期间常驻"。暴露的是一个 root 进程 + 一块不可见 display；
  `AccessibilityManager.isEnabled()` 只在 UiAutomation 连接期为 true，策略未变。

## 5. 测试

**纯逻辑单测**（JVM，无 Android）：
- `DaemonCodecTest`：五种请求的编解码、`frame` 的 `empty` 响应、未知 cmd → null；`dump` 的请求/响应格式与 v1 逐字相同（v2 只加不改）。
- `FrameGeometryTest`：rowStride 有 padding / 无 padding 的 Bitmap 宽与裁剪；缩放到 maxWidth 的目标尺寸；
  不放大。
- `ScreenSessionTest`：用假 `DaemonClient` 覆盖 ensure 幂等、create 失败返回 null、destroy 后再 ensure 重建、
  `frame` 探测失败触发重建。
- `LeaseRegistryTest`（守护进程侧租约代际逻辑抽成纯函数）：新租约替换旧租约时旧连接 EOF 不销屏，
  当前租约 EOF 才销屏。
- 删除 `DisplayParserTest`；`InjectorTest`/`ActionCommandTest` 的 `VirtualScreen` 构造去掉 sfId。

**真机 E2E**（`AgentCli`）：三个任务各跑一次；每个任务前后 `dumpsys activity activities` 记 display 0
顶层与任务列表，要求不变；任务后销屏，要求虚拟屏任务消失；全程物理屏无悬浮窗。
另验：App 主屏执行一次任务后杀掉 App 进程（`am force-stop`），确认守护进程日志出现租约 EOF 销屏，
`dumpsys display` 不再有该屏。

## 6. 实施顺序

1. 协议 v2 + `FrameGeometry` + `DisplayHost`（守护进程侧），`DaemonCli` 手工验证 create/frame/destroy。
2. `DaemonClient` v2 + `ScreenSession` + `ScreenCapture` 改造，删除 overlay 链路。
3. `AgentCli` 接线，真机 E2E 三任务。
4. 设置页调试按钮适配（建屏/销屏/截图三按钮语义不变）。
5. 更新 `docs/DESIGN.md` §4.2 状态、stage1 spec §0 表格、记忆。
