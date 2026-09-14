# Skill 跨执行环境兼容性矩阵设计

日期：2026-08-24

## 1. 目标与缺口

SkillCenter 已经可以管理 Skill 版本、执行环境资产、单次质量评测、Benchmark、运行 Trace 和优化工作项，但现有质量证据一次只描述一个 Runtime/MCP/LLM 上下文。平台因此无法回答“候选 Skill 版本在一组目标执行环境上是否都满足质量门禁”，也无法把多环境验证结果作为可追溯的发布证据。

本设计新增“兼容性矩阵运行”（Compatibility Matrix Run）：管理员为一个 Skill 版本选择若干已登记的执行环境资产，平台生成受控环境组合，复用现有质量评测能力逐组合执行，并把子评测、环境版本快照、聚合结论和发布门禁关系保存为一条不可变生命周期证据。

## 2. 范围与非目标

### 2.1 本期范围

- 以 `skillId + skillVersion + suiteId` 为矩阵运行主体。
- 支持 Runtime、MCP Server、LLM Provider 三个维度；未选择的维度以空值表示，不人为创建环境资产。
- 每个维度最多选择 10 个资产，矩阵组合总数最多 100 个；ID 去重、排序后生成确定性组合。
- 创建时要求所有被选择的资产为 `ACTIVE`，并记录当时的资产版本、能力和 Provider ID 快照。
- 每个组合复用现有 `EvaluationRequest` 和 `QualityEvaluationService`，子评测继续保持 `dataSource=mock` 或未来的 Provider 数据源边界。
- 聚合每个组合的运行状态、质量门禁、分数、稳定错误码和受控评测 ID；不保存 Prompt、输入输出、工具参数、endpoint 或凭据。
- 管理员可以查询矩阵列表、详情、组合明细和取消运行；质量中心展示最新矩阵结论及失败环境。
- 矩阵可标记为 `releaseGateRequired=true`。只有标记为必需的矩阵才参与发布门禁，未标记矩阵不会改变既有审核行为。

### 2.2 非目标

- 本期不直接实现 OpenClaw、DeepEval 或 Langfuse 的真实网络适配；这些仍通过现有 Provider 端口和 `CONTRACT_ONLY` 门禁接入。
- 不允许矩阵携带业务 Prompt、输入输出正文、文件正文或任意工具参数。
- 不为矩阵引入新的 Agent 编排框架、任意代码执行沙箱或在线 Skill 编辑器。
- 不改变历史单次评测、Benchmark、运行摘要和空环境查询的兼容行为。

## 3. 核心领域模型

### 3.1 `CompatibilityMatrixRun`

矩阵运行保存到 `QualityEvidenceState`，与单次评测、快照和用例结果使用同一 JSON 原子存储和恢复校验边界。字段如下：

| 字段 | 约束与含义 |
| --- | --- |
| `matrixRunId` | UUID 格式、全局唯一、不可变 |
| `skillId` / `skillVersion` | 必填，指向候选 Skill 版本；允许在发布前验证 |
| `suiteId` / `suiteVersion` | 必填，创建时冻结套件版本 |
| `policy` | `ALL_MUST_PASS` 或 `MIN_PASS_RATE` |
| `minimumPassRate` | `MIN_PASS_RATE` 时为 0–1；`ALL_MUST_PASS` 固定为 1 |
| `releaseGateRequired` | 是否作为该版本发布门禁证据 |
| `status` | `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED` |
| `dataSource` | 当前默认为 `mock`，只允许 `mock`/`production` |
| `scenario` / `timeoutMs` | 创建时冻结的受控故障场景和超时；服务重启后恢复矩阵必须复用这两个参数 |
| `totalCases` / `completedCases` / `passedCases` | 聚合计数，不允许负数或超过总数 |
| `score` / `passRate` | 完成后的聚合质量分和通过率；未完成时使用安全空值/0 |
| `gateStatus` / `gateReasons` | `PASSED` 或 `BLOCKED` 及稳定原因码 |
| `createdBy` / `createdAt` / `completedAt` | 审计时间线；终态必须有 `completedAt` |

同一 Skill 版本只能存在一个处于 `QUEUED` 或 `RUNNING` 的 `releaseGateRequired` 矩阵，避免并行发布证据互相覆盖。历史已完成矩阵不可修改；重新验证必须创建新矩阵。

### 3.2 `CompatibilityMatrixCase`

每个组合保存为一条矩阵用例：

- `caseId`、`matrixRunId`、`evaluationRunId`：唯一关联键；`evaluationRunId` 必须引用已存在的单次评测。
- `runtimeId`、`mcpServerId`、`llmProviderId`：受控 ID，可为空；组合键按空值归一化后唯一。
- `runtimeVersion`、`mcpServerVersion`、`llmProviderVersion`：创建时从目录资产复制的版本快照，可为空。
- `runtimeStatus`、`mcpServerStatus`、`llmProviderStatus`：创建时的状态快照；仅用于历史证据解释，不改变之后的目录状态。
- `status`：`QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`TIMED_OUT`、`CANCELLED`。
- `score`、`gateStatus`、`gateReasons`、`errorCode`、`startedAt`、`completedAt`：只保存质量聚合和稳定错误信息。

矩阵用例不能脱离矩阵运行存在；矩阵完成后每个用例必须处于终态，并且 `evaluationRunId` 的 Skill、版本、套件、数据源和执行环境必须与用例一致。

## 4. 状态与执行流程

```text
创建请求
  -> 校验 Skill/套件/环境资产
  -> 生成确定性组合并写入 QUEUED 矩阵
  -> 后台逐组合提交单次评测
  -> 轮询/接收子评测终态并更新矩阵用例
  -> 聚合 score/passRate/gateReasons
  -> COMPLETED + PASSED/BLOCKED
```

- 创建请求只接受管理员身份；不存在的 Skill、套件或环境返回稳定 404/400/409 错误，不创建半成品矩阵。
- 环境校验和组合生成在同一创建事务中完成；目录资产随后变为 `DEGRADED` 或 `DISABLED` 不会篡改已创建的环境快照，但尚未开始的矩阵用例必须在提交子评测前再次执行 `requireActive`，失败则该用例以稳定状态码结束。
- 子评测失败、超时或取消只影响对应矩阵用例；矩阵仍继续处理其它组合，最终根据策略聚合为 `BLOCKED`，除非发生无法恢复的矩阵存储/编排错误。
- `ALL_MUST_PASS` 要求所有组合完成且每个子快照门禁为 `PASSED`。
- `MIN_PASS_RATE` 要求所有组合完成且通过率不低于 `minimumPassRate`；任何缺少质量快照的组合都视为未通过。
- 管理员取消矩阵时，未开始用例和可取消的子评测进入 `CANCELLED`；矩阵终态为 `CANCELLED` 且不可作为发布证据。
- 服务启动时恢复 `QUEUED/RUNNING` 矩阵：使用持久化的 `scenario/timeoutMs` 和环境快照继续未完成组合；无法安全恢复的矩阵以 `FAILED` 和稳定 `COMPATIBILITY_MATRIX_RECOVERY_FAILED` 终止，不静默丢弃。
- 质量分采用组合子快照分数的算术平均，保留整数；不对不同套件或不同数据源混合聚合。

## 5. API 契约

新增管理员路由：

```text
POST /api/v1/admin/quality/compatibility-matrices
GET  /api/v1/admin/quality/compatibility-matrices?skillId=&skillVersion=&status=&dataSource=
GET  /api/v1/admin/quality/compatibility-matrices/{matrixRunId}
GET  /api/v1/admin/quality/compatibility-matrices/{matrixRunId}/cases
POST /api/v1/admin/quality/compatibility-matrices/{matrixRunId}/cancel
```

创建请求：

```json
{
  "skillId": "eox-query",
  "skillVersion": "1.3.0",
  "suiteId": "smoke",
  "runtimeIds": ["openclaw"],
  "mcpServerIds": ["mcp-network"],
  "llmProviderIds": ["llm-gateway"],
  "policy": "ALL_MUST_PASS",
  "minimumPassRate": 1,
  "releaseGateRequired": true,
  "scenario": "success",
  "timeoutMs": 1000
}
```

请求中不接受 `environmentVersion`、endpoint、credential、Prompt 或任意 Provider 私有字段；版本只能由服务端从目录读取。列表和详情默认只允许管理员查询，并返回 `requestId` 和稳定错误码。

## 6. 发布门禁联动

`QualityEvaluationReleaseGate` 增加兼容性矩阵检查，但保持向后兼容：

- 目标版本没有 `releaseGateRequired` 矩阵时，继续执行现有质量门禁逻辑。
- 存在必需矩阵但状态不是 `COMPLETED`，返回 `COMPATIBILITY_MATRIX_INCOMPLETE`。
- 矩阵完成但聚合门禁为 `BLOCKED`，返回 `COMPATIBILITY_MATRIX_BLOCKED` 并只暴露稳定原因码和失败组合数量。
- 矩阵完成且通过时，审计写入矩阵 ID、策略、组合数和质量分；不写入业务正文。
- 旧版本和无矩阵证据的历史发布不被追溯性阻断。

矩阵门禁检查必须在审核状态变更前执行；阻断时审核任务保持原状态，沿用现有 `QUALITY_GATE_BLOCKED` 错误包络，并把兼容性原因作为门禁原因返回。

## 7. 持久化、保留与恢复

- 扩展 `QualityEvidenceState` 时保留旧版五字段构造函数；缺少矩阵字段的旧 JSON 按空列表恢复。
- `QualityEvidenceRepository` 提供原子 read-modify-write 更新入口；单次评测、矩阵编排和保留治理都必须通过该入口更新共享 JSON，避免并发写入互相覆盖矩阵或质量证据。
- Store 启动和写入时验证矩阵/用例 ID 唯一性、父子引用、状态时间线、组合键唯一性、环境快照字段、子评测上下文和聚合计数。
- 矩阵及其用例纳入质量证据保留期：删除矩阵时级联删除矩阵用例，但不删除被引用的单次评测、快照或套件。
- 清理预览和执行结果分别返回 `compatibilityMatrixEligibleCount`、`compatibilityMatrixDeleted`，重复执行保持幂等。
- 所有矩阵创建、取消、门禁阻断和删除均写入脱敏审计；审计元数据只包含 ID、状态、策略、数量和稳定原因码。

## 8. Web 质量中心

质量中心新增“兼容性矩阵”区：

- 复用当前 Skill/版本和执行环境目录选项；允许按每个环境类型选择多个 `ACTIVE` 资产。
- 显示组合数量预估，超过 100 个时阻止提交并说明上限。
- 展示矩阵状态、整体质量分、通过率、发布门禁状态、失败原因和最近更新时间。
- 展开后按组合显示环境 ID/版本、子评测 ID、状态、质量分和稳定错误码；不显示业务正文。
- 旧 API 或目录 API 不可用时保留现有单环境质量表单；矩阵入口显示明确的不可用空态，不伪造结果。

## 9. 测试验收

后端必须覆盖：

- 组合去重、排序、数量上限和空维度生成；非 ACTIVE 环境、未知环境、非法策略和非法阈值被拒绝。
- 创建、重复活动矩阵冲突、管理员权限、取消、子评测失败/超时/取消和聚合策略。
- 目录版本快照、父子引用、跨 Skill/版本/环境/数据源错配、重复 ID 和损坏 JSON 恢复拒绝。
- 发布门禁对无矩阵、未完成、阻断和通过矩阵的四种行为；旧发布链路保持兼容。
- 保留期级联删除、幂等执行、审计脱敏和稳定错误码。

Web 必须覆盖：

- API client 路径/查询参数、目录多选、组合上限提示、状态/失败组合展示和旧 API 回退。
- 质量中心创建矩阵、刷新详情、取消入口、环境版本展示和不渲染敏感正文。

验证命令：

```text
apps/web: npm.cmd test && npm.cmd run build
apps/api: mvn.cmd -q test
根目录: git diff --check
根目录: pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipBuild -SkipSmoke
```

## 10. 明确边界

兼容性矩阵提供的是平台质量证据，不等同于真实外部系统联调。只有实际 Provider 完成认证、协议、脱敏、故障注入、容量和上线验收后，矩阵结果才能被解释为生产能力；在此之前，`dataSource=mock` 和 `CONTRACT_ONLY` 语义必须继续保持可见且不可混淆。
