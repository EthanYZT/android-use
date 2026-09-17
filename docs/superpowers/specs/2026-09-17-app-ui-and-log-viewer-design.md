# 子项目 B：App UI 重做 + 日志查看与导出

日期：2026-09-17。依赖子项目 A（Transcript harness，已落地）。

## 目标

- 主屏只做一件事：输入任务、执行、实时看每步的笔记与动作。虚拟屏的建立/销毁自动处理。
- 有一个查看历史任务日志的入口，逐步查看笔记、动作、执行结果、截图。
- 单个任务可导出为 Markdown（系统分享面板发出），方便贴给调试者。
- 调试按钮（检查 Root / 建屏并打开设置 / 销屏 / 截图预览）收进设置页。

## 屏幕

| Activity | 内容 | 关键控件 id |
|---|---|---|
| `MainActivity` | 工具栏（历史、设置入口）、虚拟屏状态、任务输入、执行/停止、实时步骤列表、结果 | `toolbar` `tvScreenStatus` `tilTask` `etTask` `btnRun` `btnStop` `progress` `rvSteps` `tvResult` |
| `HistoryActivity` | 任务列表（时间、任务、步数、结果） | `toolbar` `rvTasks` `tvEmpty` |
| `TranscriptActivity` | 单任务：头信息 + 步骤列表（含截图缩略图）；菜单"导出" | `toolbar` `tvTask` `tvMeta` `tvOutcome` `rvSteps` |
| `SettingsActivity` | 配置信息（模型/端点）、调试按钮、截图预览、调试输出 | `toolbar` `tvConfig` `btnCheckRoot` `btnCreateScreen` `btnDestroyScreen` `btnCapture` `ivPreview` `tvDebugLog` |

列表项：`item_step.xml`（`tvStepIndex` `tvNote` `tvAction` `tvResult` `tvMeta` `ivShot`），`item_task.xml`（`tvTask` `tvMeta` `tvOutcome`）。

## 数据

- 数据源：`TranscriptStore` 目录下每任务的 `transcript.jsonl` + `step-N.jpg`。
- 读回：`MiniJson`（手写递归下降解析，JVM 可测）→ `StoredTranscript(header, steps)`；`TranscriptStore.list()/load(taskId)`。
- 任务结果（finished/summary）在 A 里没落盘；本次在任务结束时追加一行 `{"type":"outcome",...}`。
- 导出：`MarkdownExporter.render(stored)` → 写到 `cacheDir/exports/<taskId>.md`，`FileProvider` + `ACTION_SEND`。

## 虚拟屏生命周期

`ScreenSession` 单例持有当前 `VirtualScreen`。主屏"执行"时若无屏则建屏并打开设置（open_app 落地前的过渡），
任务结束保留屏以便连续任务；Activity 真正结束时销毁（沿用现有 F-2 逻辑）。设置页的建/销按钮操作同一单例。

## 不做

- 不做任务中途编辑、不做多任务并发、不做日志搜索。
- 不引入 Compose / ConstraintLayout / 新依赖；沿用 XML + ViewBinding + Material 3。

## 实施结果（2026-09-17）

- 真机验证：主屏输入任务 → 自动建屏 → 实时步骤卡片（笔记/动作/结果/元信息/截图缩略图）→ 结果横幅；
  历史列表 → 日志详情 → 导出 .md 走系统分享面板（文件内容核对正确）；设置页调试按钮可用，销屏正常。
- Android 15+ 强制 edge-to-edge：每个页面根 CoordinatorLayout 加 `fitsSystemWindows="true"`，否则工具栏顶进状态栏。
- 单测 142 绿（新增 MiniJson / JSONL 读回 / Markdown 导出 / store 列表与结束行）。
- 已知：空虚拟屏镜像物理屏的悬浮窗仍会盖住主屏左上角（1e 真 headless 屏解决）。
