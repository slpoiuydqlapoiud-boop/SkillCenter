# SkillCenter 外部 Provider 适配契约（M11）

版本：contract-v1  
适用范围：OpenClaw Runner、DeepEval EvaluationProvider、Langfuse ObservabilityProvider/TraceProvider
状态：平台侧 contract-v1 与安全 HTTP 适配器已实现；真实 Provider 生产连接仍待目标环境验收

## 1. 设计边界

SkillCenter 核心域只依赖 `SkillRunner`、`EvaluationProvider`、`ObservabilityProvider` 和 `TraceProvider` 四个端口，不引入 Agent 框架或第三方 SDK。默认继续使用确定性的 Mock Provider；显式 `mode=http` 时，适配器通过平台 `ProviderHttpTransport` 发起受控、脱敏的 contract-v1 请求。

外部 Provider 只能通过适配层接入，不能改变 `SkillVersion`、`EvaluationRun`、`QualitySnapshot`、运行摘要和既有分发接口的语义。

平台的受控执行入口为 `/api/v1/admin/runner/executions`。该入口只负责把已发布 Skill 版本映射到 `SkillRunner` 标准请求，不接收 Prompt、业务输入、工具参数或凭据；执行结果统一转为脱敏运行摘要，未来 OpenClaw 或其他 Agent Runtime 通过适配器替换 Runner 实现。

## 2. Provider 清单

| Provider | 类型 | 契约版本 | 默认状态 | 能力 |
| --- | --- | --- | --- | --- |
| `openclaw-runner` | Runner | `contract-v1` | `CONTRACT_ONLY` | execute、timeout、cancel |
| `deepeval-evaluation` | Evaluation | `contract-v1` | `CONTRACT_ONLY` | evaluate、score、compare |
| `langfuse-observability` | Observability/Trace | `contract-v1` | `CONTRACT_ONLY` | trace-reference、trace-metadata、metrics、errors |

能力和健康状态由 Provider 契约声明；现有 `/api/v1/admin/quality/providers` 在不改变已有 Provider ID 的前提下补充 `capabilities` 与 `healthReason` 字段。

外部契约通过管理员只读接口 `/api/v1/admin/quality/provider-contracts` 和质量管理中心展示。`CONTRACT_ONLY` 条目不会参与评测执行；只有显式 `mode=http`、安全凭据可解析且 Provider 被选中时才允许网络调用。

## 3. 配置与密钥

适配器配置使用 `ProviderAdapterConfig`：

- `enabled`：是否允许该适配器参与运行；单独设置该字段不会发起网络调用，必须同时选择 `mode=http` 并通过 Secret/endpoint 校验。
- `endpoint`：外部服务地址，不包含凭据。
- `credentialRef`：只允许 `secret://...` 形式的密钥引用。
- `mode`：`contract` 或 `http`；缺省为 `contract`，非法值启动前拒绝。

`mode=http` 使用 `ProviderCredentialResolver` 按引用解析 Secret。平台基线支持 `secret://env/<SAFE_ENV_NAME>`，生产环境可替换为企业 Secret Manager resolver；Secret 值不进入配置、日志、审计、错误、运行摘要或质量证据。

禁止把 Bearer、Basic、`sk-*`、token、password 或 secret 的原文写入配置、日志、事件、质量快照和运行摘要。未配置或未批准的 Provider 必须 fail closed，返回 `EXTERNAL_ADAPTER_NOT_CONFIGURED`，不得降级为 Mock 或静默丢弃。

## 4. 标准输入输出

### 4.1 OpenClaw Runner

输入沿用 `RunnerExecutionRequest`：Skill/版本、评测运行 ID、套件/用例、超时、受控场景，以及可选的 `runtimeId`、`mcpServerId`、`llmProviderId` 环境标识。三个环境字段只能是受控标识符，不承载 Prompt、业务输入或凭据。评测任务和生成的 `QualitySnapshot` 会原样保留这些标识，便于按执行环境复现质量结论。输出沿用 `RunnerExecutionResult`：状态、Provider 版本、`dataSource`、耗时、输出哈希和错误码。

生产适配器必须支持：

1. 超时上限由平台传入并强制执行；
2. 取消请求能关联原始运行 ID；
3. 不返回输入/输出正文，只返回允许的哈希或引用；
4. 外部错误映射为标准状态 `FAILED`、`TIMED_OUT` 或 `CANCELLED`。

### 4.2 DeepEval EvaluationProvider

输入为脱敏 `EvaluationCase` 与标准 Runner 结果；输出为 `EvaluationResult`，至少包含通过标志、分数和非敏感原因。套件版本、规则版本和 Provider 版本由平台质量任务记录，适配器不得自行覆盖。

### 4.3 Langfuse ObservabilityProvider

输入仅为 `RunnerExecutionSummary`，允许字段为运行 ID、Skill/版本、状态、耗时、错误码、数据来源和发生时间。适配器可返回外部 Trace 引用，但不得接收提示词、输出正文、文件正文、工具参数或凭据。

### 4.4 TraceProvider

Trace 查询仅返回 `traceId`、`spanId`、Skill/版本、操作、状态、耗时、错误码、数据来源和时间。查询支持窗口、Skill、版本、Trace、状态、来源和 Runtime/MCP/LLM 环境过滤；不得返回 Prompt、输入输出、工具参数或凭据。独立的 `skill-center.providers.trace=langfuse` selector 且配置 `mode=http` 与 `trace-endpoint` 时由 `LangfuseTraceProviderAdapter` 查询外部元数据；默认仍使用本地 Mock，不改变运行摘要和质量快照语义。

## 5. 健康与错误

健康状态：

- `UP`：HTTP adapter 已显式启用且 Secret 可解析，或 Mock Provider 正常运行；不等价于网络探测或生产验收通过。
- `NOT_CONFIGURED`：没有安全配置，原因 `EXTERNAL_ADAPTER_NOT_CONFIGURED`。
- `CONTRACT_ONLY`：已有契约配置但仍为 `mode=contract`，原因 `EXTERNAL_ADAPTER_NOT_ENABLED`。

适配器不可用时抛出 `ProviderUnavailableException`。HTTP 层统一返回 503，错误码保留标准原因，消息不包含业务正文或凭据。

## 5.1 重试策略

平台使用 `ProviderRetryPolicy` 统一控制评测执行重试：

- 默认最多 2 次，仅重试 `UPSTREAM_TIMEOUT`、`RATE_LIMITED`、`TEMPORARY_UNAVAILABLE`；
- 业务校验失败、取消和未知错误不重试，不把永久失败伪装成成功；
- 退避时间有上限（最多 5 次尝试、最大退避 120 秒），配置非法时启动前拒绝；
- 重试仍沿用原始运行 ID、Skill 版本和权限上下文，最终结果才生成质量快照。

## 6. 接入前置门禁

真实接入 OpenClaw、DeepEval 或 Langfuse 前，必须完成：

- Provider 契约测试、健康检查、超时/失败/取消和故障注入；
- 权限、幂等、重试、事件关联和数据来源标记测试；
- 敏感信息拒绝/脱敏测试；
- 生产 endpoint、密钥引用、网络出口和数据保留策略评审；
- 资产管理、质量管理、运行运营和分发主链路全量回归。

在上述门禁完成前，部署默认保持 `CONTRACT_ONLY`；即使目标环境选择 `mode=http`，没有外部 endpoint、Secret、网络出口、数据保留评审、UAT 和平台生产证据时，也不得宣称 Provider 或平台生产就绪。
