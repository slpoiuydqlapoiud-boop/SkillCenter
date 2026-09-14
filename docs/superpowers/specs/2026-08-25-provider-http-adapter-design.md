# Provider HTTP Adapter 设计

日期：2026-08-25  
状态：方案 A，待进入实现阶段  
适用范围：OpenClaw Runner、DeepEval EvaluationProvider、Langfuse ObservabilityProvider

## 1. 目标

将当前 `CONTRACT_ONLY` Provider 适配器推进为可显式启用、可替换、可测试的 HTTP 适配层，使 SkillCenter 能够在不改变质量、运行运营和优化闭环领域模型的前提下连接 Agent Runtime、评测服务和观测服务。

本阶段交付的是平台侧安全适配能力和 contract-v1 HTTP wire contract，不宣称任何真实外部 Provider 已完成生产验收。默认配置继续使用 Mock，未明确启用 HTTP 时不得发生网络调用。

## 2. 设计决策

### 2.1 适配边界

- 核心域继续只依赖 `SkillRunner`、`EvaluationProvider` 和 `ObservabilityProvider`。
- 外部调用统一经过 `ProviderHttpTransport`；适配器不得直接创建或使用第三方 SDK。
- `ProviderAdapterConfig` 增加显式 `mode`：`contract` 或 `http`。缺省及非法值均按启动校验失败处理；默认配置为 `contract`，Spring 仍以 Mock 为默认 Provider。
- `http` 模式必须同时满足 `enabled=true`、安全 HTTP(S) endpoint 和 `secret://` credential reference；否则 fail-closed。

### 2.2 凭据

- 新增 `ProviderCredentialResolver`，按引用在请求发送前解析凭据；凭据值只存在内存中，不进入配置快照、日志、审计、异常、运行摘要或响应。
- 提供可替换的环境变量 resolver 作为本地/部署基线，仅接受 `secret://env/<SAFE_ENV_NAME>` 引用；生产环境可替换为企业 Secret Manager 实现。
- HTTP transport 只把解析出的值放入短生命周期 `Authorization: Bearer` 请求头，不记录请求头和 body。

### 2.3 安全 wire contract

所有请求 body 只允许下列脱敏字段：Skill/版本 ID、运行/套件/用例 ID、受控场景、超时时间、状态、耗时、错误码、输出哈希、执行环境 ID 和事件时间。禁止发送 Prompt、输入输出正文、工具参数、文件内容、路径和凭据。

- Runner 请求发送 `skillId`、`skillVersion`、`evaluationRunId`、`suiteId`、`caseId`、`timeoutMs`、`scenario`、`runtimeId`、`mcpServerId`、`llmProviderId`。
- Evaluation 请求发送 `caseId`、`caseName`、Runner 状态、Provider 版本、数据源、耗时、输出哈希和错误码；不发送业务输入。
- Observability 请求发送 `RunnerExecutionSummary` 的允许元数据。

响应也只接受白名单字段：Runner 的 `status/providerVersion/durationMs/outputHash/errorCode`，Evaluation 的 `passed/score/reason`，以及可选的观测确认状态。未知字段由 Jackson 严格模式拒绝，字符串长度和枚举值必须受界限校验。

## 3. 运行时行为

### 3.1 Provider 选择

- `skill-center.providers.*=mock`：选择现有 Mock，不创建 HTTP adapter。
- `skill-center.providers.*=openclaw|deepeval|langfuse` 且 `mode=contract`：保留现有契约适配器，调用返回 `EXTERNAL_ADAPTER_NOT_ENABLED`。
- `skill-center.providers.*=openclaw|deepeval|langfuse` 且 `mode=http`：选择 HTTP adapter；配置不完整或 Secret 缺失时返回稳定 `EXTERNAL_ADAPTER_NOT_CONFIGURED`，不回退 Mock。
- 生产 readiness 仍需外部证据台账和连接探测；HTTP adapter 被选中不等于目标 Provider 已完成生产验收。

### 3.2 超时、取消和错误

- 每次请求使用平台传入的超时上限，transport 不得超过该上限；超时映射为 `UPSTREAM_TIMEOUT`。
- 当前端口没有通用取消方法，因此 Runner 的取消由上层任务取消语义负责；HTTP transport 至少取消本地 future，不把取消伪装成成功。
- HTTP 429 映射为 `RATE_LIMITED`，5xx/连接失败映射为 `TEMPORARY_UNAVAILABLE`，4xx 业务拒绝映射为 `UPSTREAM_REJECTED`，无法解析的成功响应映射为 `UPSTREAM_INVALID_RESPONSE`。
- HTTP adapter 不自行重试；评测服务继续复用既有 `ProviderRetryPolicy`，避免重复提交非幂等请求。
- 所有外部异常统一包装为 `ProviderUnavailableException` 或稳定 Provider 错误，不包含 endpoint、响应 body、credential ref 或 cause 文本。

### 3.3 健康状态

配置为 `http` 且安全配置完整时，Provider descriptor 标记为 `UP`，表示适配器已启用；实际网络可达性仍以管理员 connectivity probe 和平台外部证据为准。`contract` 模式继续标记 `CONTRACT_ONLY`。

## 4. 代码边界

- `ProviderHttpTransport` / `ProviderHttpRequest` / `ProviderHttpResponse`：纯平台 HTTP 端口和不可变数据结构。
- `JavaHttpProviderTransport`：基于 JDK `HttpClient` 的默认实现，负责超时、Authorization header、状态码归类和不记录敏感内容。
- `ProviderCredentialResolver` / `EnvironmentProviderCredentialResolver`：Secret 引用解析端口及部署基线实现。
- `OpenClawRunnerAdapter`、`DeepEvalEvaluationAdapter`、`LangfuseObservabilityAdapter`：只负责 DTO 映射、严格响应解析和领域结果转换。
- `ProviderAdapterConfig` / `ProviderAdapterConfiguration`：模式校验、bean 选择和默认 Mock 保持兼容。

## 5. 验收标准

1. 默认 Spring 上下文只创建 Mock Provider，测试中确认没有 HTTP transport 请求。
2. `http` 模式下三类适配器能通过 fake transport 完成成功映射，并能核验请求只含允许字段。
3. Inline secret、非 HTTP endpoint、缺失 Secret、超时、429、5xx、无效 JSON 和未知响应字段均有稳定错误，且错误不含敏感内容。
4. Runner、Evaluation 和 Observability 的 HTTP adapter 单元测试先 RED 后 GREEN；现有全量 API/Web 回归保持通过。
5. M11 runbook 明确 HTTP adapter 的配置样例、Secret Manager 替换点、探测和生产验收边界。
6. 未提供真实外部 endpoint、Secret、网络出口和 UAT 证据时，项目状态仍保持 `NOT_READY`，不得更新为生产就绪。

## 6. 非目标

- 本阶段不引入 OpenClaw、DeepEval 或 Langfuse SDK。
- 本阶段不定义各厂商私有 API 的生产兼容性，不伪造厂商联调结果。
- 本阶段不把 Prompt、业务输入输出或工具调用内容纳入平台 HTTP contract。
- 本阶段不改变发布、质量门禁、运行摘要、Trace 查询或优化实验状态机。
