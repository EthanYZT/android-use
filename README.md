# android-use

一个跑在安卓手机上、能执行长任务的 **AI 中枢 Agent**：操作手机、调用系统工具与应用接口、加载内置 Skill、拥有记忆。

接入策略：**MCP + 系统接口 + GUI 兜底**。

- 📐 设计文档：[docs/DESIGN.md](docs/DESIGN.md)
- 状态：设计阶段（草案 v0.1）

## 核心理念

把手机从"需要人一步步操作的工具"变成"能替人办事的助手"：用户只表达目的，Agent 自主拆解、跨应用执行、直到交付结果；敏感环节（支付/登录/授权）由人接管。

## 架构一览

```
交互层 → Orchestrator(DeepSeek) → 能力路由(MCP > 系统接口 > GUI 兜底)
         ├ 记忆层   ├ Skill 层   ├ 安全网关
         → 执行层(root: injectInputEvent + VirtualDisplay) → 感知层(截图+节点树)
```

详见 [设计文档](docs/DESIGN.md)。
