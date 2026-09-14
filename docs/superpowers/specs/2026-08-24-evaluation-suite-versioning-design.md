# 评测套件不可变版本与用例资产设计

日期：2026-08-24

## 1. 背景与问题

SkillCenter 已具备质量评测、质量快照、Benchmark、兼容性矩阵和优化工作项，但评测套件仍以 `suiteId` 单键驻留：

- `QualityEvaluationService` 以 `suite.id()` 作为内存 Map key，重复 `suiteId` 会被拒绝；
- 质量中心创建套件时将版本固定为 `${suiteId}-v1`，无法沉淀同一逻辑套件的后续版本；
- 评测请求只携带 `suiteId`，调用方无法明确重现某个历史套件版本；
- 兼容性矩阵和 Benchmark 依赖当前解析结果，多个套件版本出现后可能选择不确定的质量证据。

这会削弱 Skill 生命周期中“测试资产沉淀、版本控制、复用共享和历史可追溯”四个核心能力。

## 2. 目标

本阶段将评测套件升级为“逻辑套件 + 不可变版本”的质量资产：

1. 同一个逻辑 `suiteId` 可以拥有多个 `suiteVersion`；版本内容创建后不可覆盖。
2. 评测运行、质量快照、兼容性矩阵和 Benchmark 都能记录或选择确定的套件版本。
3. 未指定版本的旧客户端继续工作，并解析到该逻辑套件唯一的启用版本。
4. 管理员可以从已有版本复制用例资产，创建新版本；旧版本保留可查询和审计。
5. 套件与用例只保存受控元数据，不保存 Prompt、输入输出、工具参数、凭据或业务正文。

## 3. 非目标

- 不建设在线 Skill 编辑器、Prompt 编辑器或任意代码执行能力。
- 不允许修改已存在版本的用例、名称、版本号或规则内容。
- 不在本阶段建设独立的跨 Skill 用例库、标签检索或用例市场；复制版本是本阶段的最小复用能力。
- 不改变既有质量分、规则、Runner、Provider、数据源和执行环境的计算口径。
- 不要求真实 OpenClaw、DeepEval、Langfuse 或生产数据库接入。

## 4. 核心模型

### 4.1 逻辑套件与版本

继续使用 `EvaluationSuite` 作为版本记录，字段语义冻结为：

| 字段 | 约束 |
| --- | --- |
| `id` | 逻辑套件标识；`[A-Za-z0-9][A-Za-z0-9._:-]{0,63}` |
| `name` | 展示名称；非空，最多 120 个字符，不含控制字符 |
| `version` | 版本标识；`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，同一 `id` 下唯一 |
| `enabled` | 新请求默认解析的启用指针；同一 `id` 最多一个启用版本 |
| `cases` | 1–100 个不可变 `EvaluationCase`，按请求顺序保存 |

逻辑主键为 `suiteId + "\\u0000" + suiteVersion`。`enabled` 是可变的激活指针，不代表版本内容可变：激活新版本时仅切换同一逻辑套件的指针，旧版本的 `name/version/cases` 不得修改。

每个 `EvaluationCase` 的 `id` 在套件版本内唯一，长度最多 64；`name` 非空且最多 160 个字符。执行结果只保存安全 case ID 和平台生成的 `case-{id}` 展示名，不保存原始业务内容。

### 4.2 版本解析规则

服务提供 `resolveSuite(suiteId, requestedVersion)`：

- `requestedVersion` 非空：只解析精确的 `suiteId + suiteVersion`；不存在返回稳定的 `QUALITY_SUITE_VERSION_NOT_FOUND`。
- `requestedVersion` 为空：解析同一 `suiteId` 的 `enabled=true` 版本；无启用版本返回 `QUALITY_SUITE_NOT_ENABLED`。
- 显式指定的版本也必须 `enabled=true` 才允许发起新评测；历史已停用版本仍可被历史运行、快照、Benchmark 和矩阵查询引用。
- 创建一个 `enabled=true` 的新版本时，服务原子地将同一逻辑套件的旧启用版本切换为 `false`，再写入新版本。
- 同一 `suiteId + suiteVersion` 重复创建返回 `QUALITY_SUITE_VERSION_CONFLICT`，不得覆盖原记录。

旧 JSON 快照已经包含 `version` 字段但每个 `id` 只有一条记录，启动时按复合主键恢复，不需要数据迁移；缺失新请求字段时按“未指定版本”兼容解析。

## 5. API 契约

### 5.1 套件资产 API

保留管理员路径 `/api/v1/admin/quality/suites`：

- `GET /suites`：返回全部逻辑套件版本，按 `id` 升序、`version` 降序稳定排序；响应保留 `id/name/version/enabled/cases`。
- `POST /suites`：创建一个不可变版本。请求字段为 `id/name/version/enabled/cases`；创建成功返回 `201`。
- 不提供更新和删除接口；启用指针只能由创建新启用版本的原子操作切换。

重复版本、非法 ID/版本、空用例、重复 case ID、超出数量/长度上限均返回现有错误 envelope 和稳定错误码。

### 5.2 评测请求

`EvaluationRequest` 增加可选 `suiteVersion`：

```json
{
  "skillId": "eox-query",
  "skillVersion": "1.3.0",
  "suiteId": "smoke",
  "suiteVersion": "smoke-v2",
  "scenario": "success",
  "timeoutMs": 1000
}
```

旧请求不带 `suiteVersion` 时仍解析启用版本。`EvaluationRun.suiteVersion` 必须保存实际解析版本，不能保存空值。

### 5.3 兼容性矩阵

`CompatibilityMatrixCreateRequest` 增加可选 `suiteVersion`。矩阵创建时解析并保存准确版本；每个子评测提交时携带同一版本；重启恢复继续使用 `CompatibilityMatrixRun.suiteVersion`，不重新选择当前启用版本。

### 5.4 Benchmark 与版本对比

`BenchmarkRequest` 增加可选 `suiteId`、`suiteVersion`；`BenchmarkResult` 返回实际使用的套件上下文。未指定时解析当前启用版本。`QualityComparisonService` 的快照选择必须按 `suiteId + suiteVersion` 精确过滤，避免同一 Skill/版本存在多个套件版本时选到不确定证据。

版本对比查询增加同样的可选套件筛选参数；未指定时使用当前启用版本。没有两个版本都具备同一套件版本的完成快照时，返回 `NO_COMPARABLE_SNAPSHOT` 或现有上下文不一致原因，不强行比较。

## 6. 持久化与一致性

`QualityEvidenceStore.validateRestored` 改为以复合主键索引套件，并执行以下检查：

- `suiteId + suiteVersion` 唯一；套件内部 case ID 唯一且满足边界；
- 每个 `EvaluationRun` 引用存在且版本完全匹配的套件；
- 每个 `QualitySnapshot` 引用的套件 ID/版本与所属运行完全一致；
- 每个逐用例结果引用所属运行的同一套件版本和存在的 case ID；
- 兼容性矩阵及其子评测引用的套件 ID/版本完全一致；
- 同一逻辑套件最多一个 `enabled=true` 版本；
- 任何校验失败都以 `QUALITY_EVIDENCE_PERSISTENCE_FAILED` 包装，不静默修复或覆盖数据。

创建新版本和切换启用指针使用一次 `QualityEvidenceRepository.update`，保证读取、冲突检查、切换和写入在同一原子快照内完成。评测提交先解析并固定套件版本，再异步执行；异步任务不读取“当前启用版本”替代已固定版本。

## 7. 质量中心交互

- 套件列表展示逻辑 ID、版本、用例数和启用状态；多个版本按版本行展示，不合并丢失历史。
- 创建表单增加版本输入和受控用例列表；默认值保持现有 `id-v1` 体验。
- 每个套件版本提供“复制为新版本”，将 ID、名称和用例复制到表单，管理员只需填写新版本并确认创建；复制不会修改来源版本。
- 评测和兼容性矩阵选择当前套件版本，并在提交请求中携带 `suiteVersion`；Benchmark/版本对比复用同一选择。
- 版本冲突、版本不存在、没有启用版本和空套件目录均展示明确错误/空态；提交按钮在加载期间禁用。
- 不渲染 Prompt、输入输出、工具参数、凭据或外部 Provider 响应正文。

## 8. 权限、审计与错误

- 套件列表、创建和复制仅管理员可用；普通开发者继续不能访问管理员质量 API。
- 创建新版本写入脱敏审计，至少包含 `suiteId`、`suiteVersion`、`previousEnabledVersion`、`caseCount` 和请求主体；不写入 case 名称正文。
- 版本冲突使用 `QUALITY_SUITE_VERSION_CONFLICT`；精确版本不存在使用 `QUALITY_SUITE_VERSION_NOT_FOUND`；无启用版本使用 `QUALITY_SUITE_NOT_ENABLED`。
- 错误响应沿用现有 `ApiResponse`/requestId/envelope，不暴露文件、Prompt、内部异常或存储路径。

## 9. 测试验收

后端必须覆盖：

1. 同一逻辑套件创建 `v1/v2` 成功，重复 `id+version` 冲突且 `v1` 内容不变。
2. 创建启用 `v2` 后，未指定版本解析 `v2`，`v1` 历史仍可读取但不能作为新评测版本。
3. 显式版本评测、矩阵子评测和重启恢复均保存同一 `suiteVersion`。
4. Benchmark/版本对比在多个套件版本存在时只选择显式或当前启用版本，跨套件版本返回不可比。
5. 旧构造器/旧 JSON 请求缺失 `suiteVersion` 仍通过；损坏复合引用被拒绝；开发者和管理员权限边界稳定。
6. case ID 重复、数量/长度越界、非法版本和空启用指针均返回稳定错误。

前端必须覆盖：

1. 多版本列表、当前版本选择和提交请求携带 `suiteVersion`。
2. 复制版本后能预填用例与新版本，创建后来源版本不变。
3. 重复版本、加载失败、无启用版本和空态文案可见；旧 API mock 缺少新字段时安全降级。

## 10. 迁移与回滚边界

本阶段不改变现有 JSON 字段名称，不需要一次性迁移脚本；旧套件记录天然是一个复合版本。新增请求字段均可选，先部署后旧客户端仍可调用。若新版本逻辑需要回滚，停止创建新版本即可；已有评测、快照和矩阵继续引用不可变历史版本，不执行数据重写。
