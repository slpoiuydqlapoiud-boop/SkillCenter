# 优化工作项绑定评测套件上下文规格

## 1. 背景与问题

Skill 生命周期平台已经支持评测套件的逻辑标识与不可变版本（`suiteId + suiteVersion`）。评测运行、质量快照、Benchmark 结果都能记录套件版本，但 `OptimizationWorkItem` 目前只绑定 Skill 候选版本、数据源和 Runtime/MCP/LLM 上下文。

因此，同一个 Skill 候选版本可能把来自不同评测套件版本的证据绑定到同一个优化工作项。这样会破坏“运行数据/评测结果 → 优化工作项 → 新候选版本验证”的证据链，造成跨套件串线，后续质量评分与优化闭环也无法解释。

本规格只处理优化工作项的评测口径绑定，不自动触发评测、不修改 Skill 包内容、不引入实验编排。

## 2. 目标与非目标

### 2.1 目标

- 优化工作项能够记录创建时所依据的评测套件及其不可变版本。
- 新工作项绑定评测运行、质量快照或 Benchmark 时，证据必须与工作项的评测上下文精确一致。
- 已存在的旧工作项继续可读、可查询、可沿用现有流程，不要求猜测或回填历史套件版本。
- 评测上下文在工作项创建、状态流转、证据绑定和持久化重载过程中保持不变。
- Quality Center 创建工作项时传递当前选中的套件版本，并在列表中展示该上下文。
- 错误继续使用现有优化工作项证据错误契约，避免为本次增量扩大 API 错误面。

### 2.2 非目标

- 不改变评测套件启用/禁用规则、版本发布规则或 Benchmark 生成规则。
- 不自动创建或启动 `EvaluationRun`、质量快照、Benchmark。
- 不要求旧工作项补录套件上下文；不从证据反向推断套件并写回。
- 不把评测输入、Prompt、模型输出、业务文本、凭据等内容复制到优化工作项或审计元数据。
- 不在本阶段实现“自动生成候选 Skill 版本”或自动实验编排。

## 3. 领域模型变更

### 3.1 `OptimizationWorkItem`

在现有 `llmProviderId` 之后增加两个字段：

```text
String suiteId
String suiteVersion
```

这两个字段是评测上下文元数据，序列化字段名保持为 `suiteId`、`suiteVersion`。它们必须满足以下不变量：

| 状态 | `suiteId` | `suiteVersion` | 语义 |
| --- | --- | --- | --- |
| 旧工作项 | 空 | 空 | 未锁定评测套件；保留兼容行为 |
| 新工作项 | 非空 | 非空 | 锁定一个逻辑套件的不可变版本 |
| 非法数据 | 空/非空不一致 | 空/非空不一致 | 拒绝构造、写入或加载 |

非空值使用与 `EvaluationSuite` 一致的有界标识约束：去除首尾空白，长度不超过 128，匹配 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`。字段只允许标识符，不允许控制字符、Prompt 或业务文本。

为保持已有 Java 调用方和测试构造函数兼容，保留现有 23 参数构造函数，并令其委托到新增的完整构造函数，默认 `suiteId`、`suiteVersion` 为空。Jackson 反序列化缺失字段时也按空字符串处理。

### 3.2 `OptimizationWorkItemCreateRequest`

增加可选字段：

```text
String suiteId
String suiteVersion
```

请求进入服务层后应用同一对字段不变量。两者都为空表示兼容模式；只提供其中一个必须被拒绝。新 Quality Center 请求必须提供当前选择的套件 ID 和版本。

工作项创建时，`suiteId`、`suiteVersion` 原样作为规范化后的上下文写入工作项；创建后不可被状态接口或证据接口修改。

## 4. 评测证据匹配规则

### 4.1 通用上下文

现有匹配条件继续保留：

- `skillId` 必须一致；
- 证据对应的 Skill 版本必须等于 `candidateVersion`；
- `dataSource` 遵循现有规则，工作项为 `all` 时允许匹配任一数据源，否则必须相等；
- `runtimeId`、`mcpServerId`、`llmProviderId` 必须一致。

新增规则：

- 工作项同时具有 `suiteId` 和 `suiteVersion` 时，证据的两个字段必须分别精确相等；
- 旧工作项两个字段都为空时，不增加套件过滤，保持原有兼容行为；
- 证据只有一个套件字段、或套件字段不一致时，视为证据上下文不匹配；
- 不允许把工作项的空套件上下文与一个已锁定套件上下文“部分匹配”。

### 4.2 三类证据

| 证据类型 | 当前校验 | 新增校验 |
| --- | --- | --- |
| `EVALUATION_RUN` | 运行存在且为 `COMPLETED`，Skill/候选版本/数据源/Runtime/MCP/LLM 一致 | 工作项已锁定时，`run.suiteId` 与 `run.suiteVersion` 必须精确一致 |
| `QUALITY_SNAPSHOT` | 快照存在，Skill/候选版本/数据源/Runtime/MCP/LLM 一致 | 工作项已锁定时，`snapshot.suiteId` 与 `snapshot.suiteVersion` 必须精确一致 |
| `BENCHMARK` | 在相同运行上下文中找到 Benchmark，且候选版本一致 | 使用带套件参数的 `BenchmarkService.list` 查询；工作项已锁定时按 `suiteId + suiteVersion` 精确过滤 |

证据绑定失败继续抛出 `OptimizationWorkItemEvidenceException`，由现有全局异常处理映射为 HTTP 422 和 `OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID`。对外消息应继续使用安全、非内容型描述，例如“evaluation evidence does not match candidate context”，不得回显 Prompt、输入输出或敏感上下文。

### 4.3 状态流转与复制

所有状态流转和证据绑定都通过现有 `copy` 逻辑创建新记录时，必须完整保留 `suiteId`、`suiteVersion`。以下操作不得清空或替换套件上下文：

- `OPEN → PLANNED → IN_PROGRESS → READY_FOR_EVALUATION`；
- `READY_FOR_EVALUATION → COMPLETED`；
- 任意允许的 `→ ABANDONED` 及 `ABANDONED → OPEN`；
- 证据绑定或重新绑定。

重新打开旧工作项仍保持空套件上下文；重新打开新工作项仍保持原套件版本。

## 5. API 契约

### 5.1 创建工作项

现有端点保持不变：

```text
POST /api/v1/admin/quality/optimization-work-items
```

请求体新增：

```json
{
  "suiteId": "release",
  "suiteVersion": "release-v2"
}
```

响应中的 `OptimizationWorkItem` 新增同名字段。旧客户端不传两个字段时仍可创建兼容型工作项；Quality Center 不走该兼容路径，而是传递当前选择。

### 5.2 查询、状态和证据接口

查询接口自动返回两个字段。状态更新和证据绑定请求不新增套件字段，服务端以工作项创建时锁定的上下文为准，防止调用方在生命周期中篡改评测口径。

不新增端点，不改变现有权限：创建、查询、状态流转和证据绑定仍要求管理员角色。

## 6. 持久化与兼容迁移

- 旧 JSON 记录缺少 `suiteId`、`suiteVersion` 时，反序列化为两个空字符串。
- 旧记录继续保留原有证据匹配语义，不做隐式回填。
- 新写入记录必须满足“两个字段同时为空或同时非空”。
- 加载阶段构造 `OptimizationWorkItem` 时执行相同校验；若发现只有一个字段存在，存储启动失败并沿用现有持久化读取失败路径，避免平台静默接受损坏数据。
- 不修改现有工作项的业务去重键。套件上下文是证据口径，不改变同一 Skill/源版本/建议项只能有一个活动工作项的规则。
- 审计事件可增加 `suiteId`、`suiteVersion` 元数据（仅在非空时写入），不得增加原始评测内容。

## 7. Quality Center 交互

### 7.1 创建

创建表单使用页面当前选中的套件版本：

- 请求增加 `suiteId` 和 `suiteVersion`；
- 创建按钮只有在当前选中的 `suiteId`、`suiteVersion` 都非空且对应一个已加载的套件版本时可用；没有可用套件版本时显示不可创建提示，不伪造套件上下文；
- API 仍保留两个字段都为空的兼容创建路径，供旧客户端使用，但 Quality Center 不走该路径；测试显式提供套件数据，避免依赖硬编码默认值。

### 7.2 展示

工作项列表显示：

- 已锁定：`套件 release · 版本 release-v2`；
- 旧工作项：`未锁定套件版本`。

展示只使用 ID/版本等安全元数据，不展示或拼接证据原文。

## 8. 测试规格

### 8.1 API 单元/集成测试

1. **模型约束**
   - 两个套件字段同时为空可构造；
   - 两个字段同时有效可构造并规范化空白；
   - 仅提供一个字段、超长值、非法标识符均拒绝；
   - 旧 23 参数构造函数默认空上下文；
   - 旧 JSON 缺失字段可加载。

2. **创建与持久化**
   - 带套件上下文的创建请求将两个字段写入工作项；
   - 创建后的查询返回同一上下文；
   - 状态流转、证据绑定、放弃和重新打开都保留上下文；
   - 存储加载到单字段上下文时拒绝启动/读取。

3. **证据一致性**
   - 锁定 `suite-a/suite-v1` 的工作项可绑定同套件同版本的已完成 Evaluation Run；
   - 套件 ID 不同或版本不同的 Evaluation Run 均返回 `OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID`；
   - Quality Snapshot 对上述两类不一致同样拒绝；
   - Benchmark 对上述两类不一致拒绝，同套件版本允许绑定；
   - 旧空上下文工作项保留既有匹配行为；
   - 证据本身套件字段不成对时拒绝，不允许部分匹配。

4. **权限与契约**
   - 非管理员仍被拒绝；
   - 状态/证据请求不能覆盖工作项已锁定的套件上下文；
   - 错误 HTTP 状态和错误码保持稳定。

### 8.2 Web 测试

- 创建请求包含当前选中的 `suiteId`、`suiteVersion`；
- 已锁定工作项展示套件 ID 与版本；
- 旧工作项展示“未锁定套件版本”；
- 现有状态流转和证据绑定交互不回归。

## 9. 验收标准

本增量满足以下条件后可视为完成：

- 新建的 Quality Center 优化工作项始终记录所选评测套件版本；
- 新工作项不能绑定来自其他套件或其他套件版本的 Evaluation Run、Quality Snapshot、Benchmark；
- 旧工作项无需迁移即可继续查询和使用；
- 工作项生命周期中评测上下文不可变；
- API 与 Web 测试覆盖上述正反例，且现有全量回归通过；
- 变更不引入 Prompt、输入输出、凭据或业务文本的持久化。

## 10. 后续演进

在本规格落地后，可基于稳定的 `OptimizationWorkItem + suiteId + suiteVersion` 继续建设实验编排：为一个工作项生成候选版本、自动执行同一套件、比较基线与候选、写回质量评分和 Trace 证据。该阶段应复用本规格的精确上下文匹配，不再引入第二套口径绑定逻辑。
