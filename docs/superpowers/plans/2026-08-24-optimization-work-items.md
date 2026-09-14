# Skill 优化工作项与实验迭代闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将质量/运行优化建议沉淀为可审计、可持久化、可绑定评测证据的 Skill 优化工作项。

**Architecture:** 在 `quality` 域新增独立 JSON 工作项存储和服务，服务只依赖现有 `OptimizationSuggestionService`、`QualityEvaluationService`、`BenchmarkService` 与 `GovernanceStore`，不修改 Skill 正文或发布状态。API 只允许管理员通过状态机推进工作项；Web 质量中心复用当前 Skill、版本和环境筛选展示工作项。

**Tech Stack:** Spring Boot 3.4 / Java 21 / Jackson JSON 原子文件；React 19 / Vite / Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-24-optimization-work-items-design.md`

## Global Constraints

- 只允许管理员访问优化工作项 API；普通开发者不能读取或修改工作项。
- 不在线编辑 Skill，不执行任意代码，不自动发布、回滚或修改质量证据。
- 工作项只保存安全建议摘要、指标键值、版本和环境标识，不保存 Prompt、输入输出正文、文件内容或凭据。
- 默认存储路径为 `./data/governance/optimization-work-items.json`，写入使用临时文件和原子替换。
- `COMPLETED` 不可变；`READY_FOR_EVALUATION` 必须有候选版本；`COMPLETED` 必须有匹配完成证据和结果说明。
- 所有写操作通过统一错误 envelope、requestId、管理员权限和 Governance audit。

---

### Task 1: 工作项值对象与 JSON 存储

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItem.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemStore.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemStoreTest.java`

**Interfaces:**
- Produces `OptimizationWorkItem`, `OptimizationWorkItemStatus`, `OptimizationWorkItemStore.findAll(...)`, `find(...)`, `create(...)`, `replace(...)`。
- `OptimizationWorkItem` 字段顺序必须与设计文档一致；状态常量为 `OPEN`, `PLANNED`, `IN_PROGRESS`, `READY_FOR_EVALUATION`, `COMPLETED`, `ABANDONED`。

- [x] **Step 1: Write the failing test**：覆盖新建后 JSON 文件生成、重启恢复、重复 ID 和重复未终态业务键；非法状态/原子替换保护由值对象校验与 Store 实现覆盖。
- [x] **Step 2: Run test to verify it fails**：已先运行 focused Maven 测试并确认类型/Store 缺失导致编译失败。
- [x] **Step 3: Write minimal implementation**：实现 record 的非空/长度/标识符校验；Store 使用读写锁、ObjectMapper、临时文件和 `ATOMIC_MOVE` 回退；恢复时校验 ID 唯一与业务键唯一。
- [x] **Step 4: Run test to verify it passes**：`mvn.cmd -q -Dtest=OptimizationWorkItemStoreTest test` 通过。
- [x] **Step 5: Commit**：不提交；当前工作区包含用户既有未提交改动，等待用户明确授权后再决定集成方式。

### 增量收口：质量中心终态与证据台账

- [x] 质量中心支持 `COMPLETED`、`ABANDONED`、`OPEN` 重新打开和结果说明。
- [x] 质量中心展示已绑定证据类型、受控证据 ID 和结果说明。
- [x] 新增交互回归覆盖完成、放弃/重新打开路径及证据台账。
- [x] Web 全量回归更新为 110/110，API 全量回归保持 314/314。

### Task 2: 状态机、建议快照与证据一致性服务

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemCreateRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemStatusRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemEvidenceRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemConflictException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemInvalidStateException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemEvidenceException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemServiceTest.java`

**Interfaces:**
- `create(OptimizationWorkItemCreateRequest request, Actor actor, String requestId)` creates `OPEN` from a current suggestion and snapshots title/category/severity/evidence.
- `list(String skillId, String status, String ownerId, String sourceVersion)` returns newest-updated first.
- `find(String workItemId)` returns one item or throws not-found.
- `transition(String workItemId, OptimizationWorkItemStatusRequest request, Actor actor, String requestId)` validates allowed transitions and candidate/evidence prerequisites.
- `bindEvidence(String workItemId, OptimizationWorkItemEvidenceRequest request, Actor actor, String requestId)` validates `EVALUATION_RUN`, `QUALITY_SNAPSHOT`, or `BENCHMARK` against candidate version, data source and environment.

- [x] **Step 1: Write the failing test**：覆盖建议快照创建、完整状态流转、非法回退、Benchmark 证据上下文不匹配、权限和审计写入；服务同时实现候选版本、终态证据与重复活动键约束。
- [x] **Step 2: Run test to verify it fails**：已先运行 focused Maven 测试并确认服务/类型缺失或断言失败。
- [x] **Step 3: Write minimal implementation**：注入现有质量服务和治理仓储；创建时只复制安全字段；用显式转移表校验状态；证据校验复用评测/快照/Benchmark 查询；所有写操作要求管理员并写入 audit。
- [x] **Step 4: Run test to verify it passes**：`mvn.cmd -q -Dtest=OptimizationWorkItemServiceTest test` 通过，既有质量/Benchmark 测试未回归。
- [x] **Step 5: Commit**：不提交；当前工作区包含用户既有未提交改动，等待用户明确授权后再决定集成方式。

### Task 3: 管理 API 与稳定错误契约

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemControllerTest.java`

**Interfaces:**
- `POST /api/v1/admin/quality/optimization-work-items`
- `GET /api/v1/admin/quality/optimization-work-items?skillId=&status=&ownerId=&sourceVersion=`
- `GET /api/v1/admin/quality/optimization-work-items/{workItemId}`
- `PATCH /api/v1/admin/quality/optimization-work-items/{workItemId}/status`
- `PUT /api/v1/admin/quality/optimization-work-items/{workItemId}/evidence`

- [x] **Step 1: Write the failing test**：覆盖 admin 成功、developer 403、创建 201、查询、状态/证据更新和稳定 404 错误契约；409/422/503 映射由统一异常处理实现并由服务测试覆盖。
- [x] **Step 2: Run test to verify it fails**：已先运行 focused Maven 测试并确认路由/控制器不存在导致编译失败。
- [x] **Step 3: Write minimal implementation**：控制器从 `ActorResolver` 获取角色，把请求委托给 Service；异常处理映射为 `OPTIMIZATION_WORK_ITEM_NOT_FOUND`, `OPTIMIZATION_WORK_ITEM_CONFLICT`, `OPTIMIZATION_WORK_ITEM_INVALID_STATE`, `OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID`, `OPTIMIZATION_WORK_ITEM_PERSISTENCE_FAILED`。
- [x] **Step 4: Run test to verify it passes**：`mvn.cmd -q -Dtest=OptimizationWorkItemControllerTest test` 通过。
- [x] **Step 5: Commit**：不提交；当前工作区包含用户既有未提交改动，等待用户明确授权后再决定集成方式。

### Task 4: Web API、质量中心面板与交互测试

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/quality.js`
- Modify: `apps/web/tests/operations-metrics.test.mjs`
- Modify: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:**
- `skillApi.listOptimizationWorkItems(params)`
- `skillApi.createOptimizationWorkItem(input)`
- `skillApi.updateOptimizationWorkItemStatus(workItemId, input)`
- `skillApi.bindOptimizationWorkItemEvidence(workItemId, input)`

- [x] **Step 1: Write the failing test**：覆盖 API 路径/query/body 编码、质量中心从建议创建、列表展示、状态更新、候选版本和证据绑定、错误提示及只读角色。
- [x] **Step 2: Run test to verify it fails**：已先运行 focused Web 测试并确认缺少 API 方法和面板。
- [x] **Step 3: Write minimal implementation**：在 `skillApi.js` 添加 URL 编码接口；在质量中心新增面板，复用当前 Skill/版本/环境上下文，创建、状态更新和证据绑定后局部更新；不渲染正文或凭据。
- [x] **Step 4: Run test to verify it passes**：focused Web 测试通过。
- [x] **Step 5: Commit**：不提交；当前工作区包含用户既有未提交改动，等待用户明确授权后再决定集成方式。

### Task 5: 全量回归与生命周期文档

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/superpowers/plans/2026-08-24-optimization-work-items.md`
- Test: `apps/web` and `apps/api` existing suites

- [x] **Step 1: Run focused backend and Web tests**：新增存储、服务、控制器、API 与质量中心测试通过。
- [x] **Step 2: Run full verification**：`npm.cmd test`（109/109）、`npm.cmd run build`、`mvn.cmd -q test`（314/314）均通过。
- [x] **Step 3: Inspect artifacts and status**：Sites 构建产物生成，`git diff --check` 通过，无临时诊断文件或业务正文泄露。
- [x] **Step 4: Update status docs**：已记录工作项 API、状态机、证据绑定和验证数量，并保留真实 Provider/SSO/生产存储外部接入边界。
- [x] **Step 5: Commit**：不提交；等待用户明确授权后再决定集成方式。
