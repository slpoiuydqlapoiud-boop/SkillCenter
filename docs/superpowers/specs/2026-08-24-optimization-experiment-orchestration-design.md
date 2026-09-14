# OptimizationExperiment 实验编排设计规格

日期：2026-08-24  
状态：待评审  
范围：方案 A——把优化工作项升级为可追踪、可恢复、可回写证据的评测实验

## 1. 背景与问题

当前平台已经具备以下能力：

- 以 `skillId + sourceVersion + suggestionId` 管理优化工作项；
- 为工作项固定候选 Skill 版本、数据源、Runtime/MCP/LLM 和 `suiteId + suiteVersion`；
- 异步提交质量评测并持久化 `EvaluationRun`、`QualitySnapshot`；
- 对工作项绑定 Evaluation Run、Quality Snapshot 或 Benchmark，并校验上下文一致性。

缺口是：工作项到评测之间没有一等的业务对象。用户只能先手工调用评测接口，再手工把结果绑定回工作项，无法回答“这次评测由哪个优化工作项发起、正在执行到哪一步、失败后如何恢复、是否已经回写证据”。重试也可能产生重复评测。

本迭代引入持久化的 `OptimizationExperiment`，作为“优化假设的一次候选验证尝试”。它不修改 Skill 内容、不发布版本、不自动回滚，只编排评测、记录引用、回写可验证证据。

## 2. 目标

1. 管理员可以从 `READY_FOR_EVALUATION` 工作项创建一次实验，实验上下文只从工作项读取，避免请求体重复传参导致上下文漂移。
2. 实验具有稳定 ID、明确状态、候选评测 Run 引用和失败原因，可被查询、取消、恢复和审计。
3. 创建和重试具有幂等语义：同一工作项只能存在一个非终态实验；同一实验不会因为重试产生第二个候选评测 Run。
4. 评测完成后，系统自动校验并把同上下文的 `QualitySnapshot` 作为工作项主证据；实验同时保留 `EvaluationRun` 引用。工作项仍保持 `READY_FOR_EVALUATION`，由管理员决定是否完成或放弃。
5. Benchmark 是显式动作，只有在候选质量快照和基线质量证据满足上下文要求时才创建，并把结果绑定为工作项证据。
6. 所有持久化恢复、取消、重试和证据回写都可审计，错误对外只暴露稳定错误码和安全元数据。

## 3. 非目标与边界

- 不在本迭代自动编辑 Prompt、输入输出样本、Skill 文件或配置。
- 不自动发布、灰度、回滚或替换线上版本。
- 不在 GET 查询中隐式触发评测、Benchmark 或工作项变更；推进状态使用显式 `reconcile`/`benchmark` 动作。
- 不保存业务输入、模型输出、凭据、完整 Trace 或用户内容；实验只保存资源 ID、版本、状态、分数引用和有限长度的稳定错误码。
- 不重写已有 QualityEvaluationService 的执行器、评测 Provider 或执行环境注册机制。

## 4. 领域模型

### 4.1 OptimizationExperiment

新增 `com.huawei.skillcenter.quality.OptimizationExperiment` 不可变记录，字段如下：

| 字段 | 说明 |
| --- | --- |
| `experimentId` | `experiment-` 前缀的有界标识符 |
| `workItemId` | 发起实验的优化工作项 |
| `skillId` | 从工作项复制的 Skill 标识 |
| `sourceVersion` | 基线/原始版本 |
| `candidateVersion` | 待验证候选版本 |
| `dataSource` | `mock`、`production` 或 `all` |
| `runtimeId` / `mcpServerId` / `llmProviderId` | 执行环境绑定 |
| `suiteId` / `suiteVersion` | 必须成对出现；创建时解析并固定 |
| `status` | `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED` |
| `evaluationRunId` | 候选版本评测 Run；未提交时为空 |
| `qualitySnapshotId` | 候选评测完成后生成的快照；未生成时为空 |
| `benchmarkId` | 显式 Benchmark 成功后写入；可为空 |
| `failureCode` | 稳定、无业务内容的错误码；非失败状态为空 |
| `createdBy` / `createdAt` | 创建审计字段 |
| `updatedBy` / `updatedAt` | 最近一次状态或引用变化的审计字段 |

构造约束：

- 所有资源 ID、版本、环境 ID、套件 ID 采用现有有界标识规则；文本不进入实验记录。
- `suiteId` 与 `suiteVersion` 必须同时为空或同时非空。实验创建前必须通过 QualityEvaluationService 精确解析；不允许隐式使用创建之后变化的 active suite。
- `status=QUEUED` 时 `evaluationRunId` 为空；`RUNNING`、`COMPLETED` 必须已有 `evaluationRunId`。`FAILED`/`CANCELLED` 可以在提交评测前发生，此时允许 Run ID 为空；一旦存在 Run ID，必须能找到并校验它。
- `COMPLETED` 必须同时有已完成的 `evaluationRunId` 和 `qualitySnapshotId`；`FAILED` 必须有 `failureCode`；`CANCELLED` 的 `failureCode` 固定为 `CANCELLED_BY_REQUEST`。
- `benchmarkId` 只能在 `COMPLETED` 后写入，且不能改变实验的 Skill、版本、套件或执行环境上下文。

### 4.2 EvaluationRun 的实验关联

为 `EvaluationRequest` 和 `EvaluationRun` 增加可选的 `experimentId` 关联字段，并保留现有构造函数兼容旧调用方。提交评测时以 `experimentId` 作为幂等键：

- 没有关联的旧调用行为不变；
- 同一 `experimentId` 已有 Run 时，QualityEvaluationService 返回原 Run，不创建新 Run；
- 已有 Run 的上下文与本次请求不一致时，拒绝请求并返回稳定冲突错误；
- 持久化恢复时校验 `experimentId` 不重复，并允许历史终态 Run 保留关联。

这样可以覆盖“实验已持久化但进程在写回 Run ID 前崩溃”的恢复场景：恢复任务可按实验 ID 找回原 Run，而不是重复提交。

### 4.3 Benchmark 的实验关联

为 `BenchmarkRequest` 和 `BenchmarkResult` 增加可选的 `experimentId`。有实验关联的 Benchmark 以该字段做幂等键；已有同实验结果时直接复用，已有结果但上下文不同则拒绝。没有实验关联的旧 Benchmark 行为保持不变。

## 5. 状态机与编排语义

### 5.1 实验状态

```text
QUEUED --提交成功--> RUNNING --评测完成且快照存在--> COMPLETED
  |                      |  \
  |                      |   \--失败/超时--> FAILED
  |                      \--取消---------> CANCELLED
  \--提交失败-----------------------------> FAILED
```

- `QUEUED`：实验已落盘，等待评测提交或恢复重试。
- `RUNNING`：已绑定候选 `EvaluationRun`；Run 本身可以处于 `QUEUED` 或 `RUNNING`。
- `COMPLETED`：候选 Run 已完成、Quality Snapshot 已存在且上下文校验通过。此状态不表示工作项已完成。
- `FAILED`：评测失败、超时、快照缺失、上下文不一致或提交失败；记录稳定 `failureCode`。
- `CANCELLED`：管理员显式取消；对已经终态的实验重复取消返回原对象，不产生新审计状态。

终态为 `COMPLETED`、`FAILED`、`CANCELLED`。终态实验不可重启；如需再次验证，创建新的历史实验前必须先把工作项恢复到允许评测的状态，并由存储层保留历史记录。第一版不允许同一工作项同时存在两个非终态实验。

### 5.2 创建

`POST /api/v1/admin/quality/optimization-experiments` 请求只包含 `workItemId`，可选 `requestId` 仍由现有 RequestIdFilter 提供。

服务端步骤：

1. 校验管理员权限，读取工作项。
2. 要求工作项状态为 `READY_FOR_EVALUATION`，且候选版本存在。
3. 检查该工作项是否已有非终态实验；已有时返回该实验（幂等）而不是创建第二个实验。
4. 复制并校验工作项的完整执行上下文；若工作项没有固定套件，则在此刻解析当前 active suite，并把解析出的 `suiteId + suiteVersion` 固定到实验；若工作项已固定，要求精确版本仍存在且启用。
5. 先持久化 `QUEUED` 实验。
6. 使用实验 ID 调用 `QualityEvaluationService.submit`，提交候选版本评测；成功后写入 `evaluationRunId` 并转为 `RUNNING`。
7. 提交异常转为 `FAILED/SUBMISSION_FAILED` 并持久化，禁止把异常堆栈或业务文本写入实验。

创建接口返回 `201 Created`。若同一工作项已有非终态实验，返回 `200 OK` 和已有实验，响应体结构保持一致。

### 5.3 reconcile

`POST /api/v1/admin/quality/optimization-experiments/{experimentId}/reconcile` 是显式、幂等的推进动作。

- `QUEUED`：按实验 ID 查找或提交候选 Run；已有 Run 则复用；无 Run 才提交。
- `RUNNING`：读取候选 Run。非终态时保持不变；失败/取消映射到实验终态。
- 候选 Run 为 `COMPLETED`：读取对应 Quality Snapshot，验证 Skill、候选版本、数据源、环境、套件完整一致；成功后把实验置为 `COMPLETED`。
- 快照不存在或上下文不一致：实验置为 `FAILED`，使用 `QUALITY_SNAPSHOT_NOT_AVAILABLE` 或 `EVIDENCE_CONTEXT_MISMATCH`。
- 终态：直接返回原实验。

完成回写顺序：先确认候选 Run 和 Quality Snapshot 的上下文，再调用 `OptimizationWorkItemService.bindEvidence` 绑定 `QUALITY_SNAPSHOT`，最后把实验更新为 `COMPLETED`；`EvaluationRun` 由实验自身的 `evaluationRunId` 保留。工作项状态不自动改变。两个 JSON Store 之间没有跨仓储事务：如果证据已成功绑定但实验状态持久化失败，下一次 reconcile 必须识别已有同 ID、同上下文证据并安全重试；如果证据绑定失败，则实验不得标记为完成。

### 5.4 cancel

`POST /api/v1/admin/quality/optimization-experiments/{experimentId}/cancel`：

- `QUEUED` 且尚未有 Run ID 时直接转为 `CANCELLED`；`QUEUED`/`RUNNING` 已有 Run ID 时调用 `QualityEvaluationService.cancel(evaluationRunId)`，然后转为 `CANCELLED`；
- 终态重复调用返回原实验；
- 取消只做合作式取消，不保证已进入执行器的外部调用立即停止；
- 工作项不自动解除、重置或完成。

### 5.5 benchmark

`POST /api/v1/admin/quality/optimization-experiments/{experimentId}/benchmark` 是显式比较动作，请求只允许携带可选 `window`，不允许覆盖实验上下文。

前置条件：实验 `COMPLETED`，候选快照存在；`BenchmarkService` 能找到同 Skill、同数据源、同 Runtime/MCP/LLM、同套件上下文的基线质量证据。服务调用现有 `BenchmarkService.run`，携带 `experimentId` 幂等键；成功后写入 `benchmarkId`，再以 `BENCHMARK` 类型绑定工作项证据。缺少基线或上下文不匹配时返回稳定错误，不改变实验终态。

Benchmark 重试必须使用稳定的 `experimentId` 作为请求幂等键或先复用已有 `benchmarkId`；不能为同一实验无界地产生重复比较记录。

## 6. 持久化与恢复

新增 `OptimizationExperimentStore`，沿用现有 JSON 原子替换和读写锁模式，默认路径：

`./data/governance/optimization-experiments.json`

存储层约束：

- ID 唯一；引用字段格式合法；时间单调；状态与字段组合满足领域约束。
- 同一工作项最多一个非终态实验；终态实验保留用于历史审计。
- 启动恢复时拒绝重复 ID、重复非终态业务键和非法状态组合。
- `evaluationRunId`、`qualitySnapshotId`、`benchmarkId` 的存在性和上下文一致性由编排服务在 reconcile/benchmark 时检查，不要求实验存储层直接依赖其他仓储实现。
- 写入采用临时文件 + 原子替换；失败抛出专用持久化异常，控制器映射为稳定错误码。

恢复策略：应用重启后，实验列表保持 `QUEUED`/`RUNNING` 原状；首次显式 reconcile 可找回同 `experimentId` 的评测 Run 并继续推进。第一版不引入后台定时器，避免在没有明确租约和重试策略时产生隐式外部调用。

## 7. API 与安全错误

新增控制器：

`/api/v1/admin/quality/optimization-experiments`

端点：

- `POST /` 创建或幂等复用实验；
- `GET /` 按 `skillId`、`workItemId`、`status` 查询；
- `GET /{experimentId}` 查询单个实验；
- `POST /{experimentId}/reconcile` 显式推进；
- `POST /{experimentId}/cancel` 取消；
- `POST /{experimentId}/benchmark` 创建或复用 Benchmark。

所有端点只允许 admin。错误响应复用现有 `ApiResponse`/全局异常映射，至少覆盖：

- `OPTIMIZATION_EXPERIMENT_NOT_FOUND`
- `OPTIMIZATION_EXPERIMENT_INVALID_STATE`
- `OPTIMIZATION_EXPERIMENT_CONFLICT`
- `OPTIMIZATION_EXPERIMENT_CONTEXT_MISMATCH`
- `OPTIMIZATION_EXPERIMENT_EVALUATION_FAILED`
- `OPTIMIZATION_EXPERIMENT_SNAPSHOT_NOT_FOUND`
- `OPTIMIZATION_EXPERIMENT_PERSISTENCE_FAILED`

错误消息不包含 Prompt、样例、模型输出、凭据或异常堆栈；审计 metadata 只放资源 ID、状态、版本和稳定错误码。

## 8. 审计与可观测性

新增审计动作：

- `OPTIMIZATION_EXPERIMENT_CREATED`
- `OPTIMIZATION_EXPERIMENT_SUBMITTED`
- `OPTIMIZATION_EXPERIMENT_RECONCILED`
- `OPTIMIZATION_EXPERIMENT_COMPLETED`
- `OPTIMIZATION_EXPERIMENT_FAILED`
- `OPTIMIZATION_EXPERIMENT_CANCELLED`
- `OPTIMIZATION_EXPERIMENT_BENCHMARKED`
- `OPTIMIZATION_EXPERIMENT_EVIDENCE_BOUND`

每次动作写入 `experimentId`、`workItemId`、旧/新状态、Run/Snapshot/Benchmark ID 和 `requestId`。不记录业务内容。

## 9. Web 最小闭环

在 Quality Center 的优化工作项区域：

- `READY_FOR_EVALUATION` 且无活动实验时显示“启动实验”；
- 显示实验状态、Run ID、Snapshot ID、Benchmark ID 和最近失败码；
- 对 `QUEUED`/`RUNNING` 显示“刷新实验”和“取消实验”；
- `COMPLETED` 显示“生成 Benchmark”；
- 工作项状态仍由现有状态动作控制，前端不得根据实验完成自动发送完成请求；
- 所有按钮只发送资源 ID 和有限的窗口参数，不在浏览器保存业务评测内容。

## 10. 测试策略与验收标准

API 单元/集成测试至少覆盖：

1. 实验领域模型的状态字段约束和 suite 成对约束。
2. 仅 `READY_FOR_EVALUATION` 可创建；候选版本和环境校验失败时不产生实验。
3. 同工作项重复创建幂等返回；不同实验 ID 不会复用错误 Run。
4. `experimentId` 关联的 EvaluationRun 提交、恢复查找和上下文冲突。
5. `QUEUED`、`RUNNING`、完成、失败、取消和重复 reconcile 的状态转移。
6. 快照上下文校验、工作项证据自动回写、回写失败不虚报完成。
7. Benchmark 只能在完成后显式触发，基线上下文不匹配时拒绝且不污染实验。
8. JSON 存储重启恢复、非法状态拒绝、原子持久化失败映射。
9. 控制器 admin 权限、稳定错误码、requestId 审计和不泄露业务内容。

Web 测试覆盖启动、刷新、取消、Benchmark 按钮请求以及状态展示；现有 API/Web 全量回归不得下降。

验收条件：

- 一个 READY 工作项可以创建并追踪候选评测；
- 进程重启或重复请求不会产生重复活动实验/重复候选 Run；
- 评测完成后工作项能看到同上下文 Quality Snapshot；
- 质量证据与工作项上下文不一致时无法回写；
- 平台不会因为实验完成而自动发布或完成 Skill 优化。

## 11. 实施顺序

1. 先补 `experimentId` 关联与 EvaluationRun 幂等查询，保持旧构造函数和旧 API 兼容。
2. 实现领域记录、状态、异常和 JSON Store。
3. 实现编排 Service：创建、reconcile、cancel、benchmark、证据回写和审计。
4. 增加 Admin Controller 与异常映射。
5. 以 API 测试驱动状态机和恢复边界，再接入 Quality Center 最小 UI。
6. 执行 API/Web 全量测试、Web 构建、`git diff --check`，更新项目状态文档。
