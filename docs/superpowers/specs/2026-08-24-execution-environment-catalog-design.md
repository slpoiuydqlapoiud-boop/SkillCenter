# 执行环境资产目录设计

日期：2026-08-24

## 1. 背景与目标

当前 Skill 质量评测、受控 Runner、Benchmark 和运行运营已经能够保存 `runtimeId`、`mcpServerId`、`llmProviderId`，但这些字段仍是自由输入的受控字符串。平台无法回答“这个 ID 对应哪个版本、具备什么能力、是否允许新任务使用、由哪个适配器承载”。本阶段建立统一的执行环境资产目录，使 Agent Runtime、MCP Server 和 LLM Provider 成为可发现、可审计、可复用的环境资产。

## 2. 范围与非目标

本阶段包含：

- 统一 `ExecutionEnvironment` 资产模型，区分 `AGENT_RUNTIME`、`MCP_SERVER`、`LLM_PROVIDER`；
- 环境版本、能力、生命周期状态、适配器绑定和非敏感配置引用；
- JSON 原子持久化、启动恢复、管理员注册/查询/状态变更、重复资产冲突和审计；
- 质量中心从目录选择环境，并继续把所选环境快照传入评测、Runner、Benchmark 和优化工作项；
- 新的评测/Runner 请求在提供环境 ID 时校验目录中存在且状态可用；空环境 ID 保持向后兼容，历史只读查询不因旧数据缺少目录记录而失败。

本阶段不包含：

- 任意代码执行、在线编辑 Skill 或通用 Agent 框架；
- 在目录中保存 endpoint、Token、Prompt、输入输出正文或工具参数；
- 把目录状态或连接探测结果直接等同于 OpenClaw/DeepEval/Langfuse 的真实生产就绪；
- 真实外部协议请求映射，仍由独立 Provider 适配阶段完成。

## 3. 资产模型

`ExecutionEnvironment` 的稳定业务键为 `kind + environmentId`。`environmentId` 不可为空，只允许 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`。`version` 是环境契约版本，更新时写入审计；`capabilities` 是非敏感能力标签；`adapterProviderId` 绑定平台 Provider ID，例如 `openclaw-runner`、`deepeval-evaluation` 或 `langfuse-observability`；`configReference` 只允许空值或 `secret://...` 引用，不返回引用原文之外的密钥内容。

生命周期状态：

| 状态 | 新评测/Runner | 查询与历史证据 | 说明 |
| --- | --- | --- | --- |
| `ACTIVE` | 允许 | 允许 | 已登记并允许作为新任务上下文 |
| `DEGRADED` | 拒绝 | 允许 | 保留历史追溯，等待探测或适配器修复 |
| `DISABLED` | 拒绝 | 允许 | 管理员主动停用，不删除历史资产 |

Provider `CONTRACT_ONLY` 与环境资产状态独立：环境可以被登记用于上下文治理，但真实执行 readiness 仍由 Provider readiness 和后续适配器门禁决定。

首次创建目录时只写入三条非敏感上下文种子资产：`openclaw`（`AGENT_RUNTIME`）、`mcp-network`（`MCP_SERVER`）和 `llm-gateway`（`LLM_PROVIDER`）。三条资产版本均为 `context-v1`、状态为 `ACTIVE`、能力均为 `context-only`；它们只是默认上下文目录项，不宣称对应外部系统已经连通。

## 4. API 契约

管理员 API：

- `GET /api/v1/admin/execution-environments?kind=&status=`：按稳定 `kind/environmentId` 顺序返回脱敏目录；
- `POST /api/v1/admin/execution-environments`：注册环境，默认状态为 `ACTIVE`；重复 `kind + environmentId` 返回冲突；
- `PATCH /api/v1/admin/execution-environments/{kind}/{environmentId}/status`：只变更生命周期状态并写审计；

响应只返回环境资产字段和 `requestId`，不得返回 endpoint、Token、Prompt、输入输出正文或 Provider 异常正文。非管理员统一返回 `403 FORBIDDEN`。

## 5. 数据流与兼容策略

质量中心加载目录后按 Kind 渲染下拉选择；目录缺失或旧 API 不支持目录时保留安全的文本输入回退，不影响已有 Mock 测试。新的有环境上下文的评测和 Runner 请求通过 `ExecutionEnvironmentService.requireActive(kind, id)` 校验；Benchmark、运行聚合、Trace 和历史查询继续允许旧数据中的未登记 ID，以保证历史可读性。优化工作项只接受已通过上下文校验的候选证据。

## 6. 测试与验收

- 模型校验：非法 ID、空版本、原始凭据、过长能力列表和非法状态被拒绝；
- Store：原子写入、重启恢复、重复键冲突、状态变更和损坏快照 fail-closed；
- API：管理员注册/查询/停用、开发者 403、错误契约、审计和敏感字段脱敏；
- 质量/Runner：ACTIVE 可提交，DEGRADED/DISABLED 被稳定错误码拒绝，空上下文兼容；
- Web：目录加载、按类型选择、状态展示、API 缺失回退和上下文参数透传；
- 全量 Web/API 测试、构建和生命周期回归通过。
