# M5.2 受控导出与审计治理基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. 每完成一个任务都要运行该任务的聚焦验证；本项目无 Git 仓库，不执行 commit、reset 或 branch 命令。

**目标：** 在 M5.1 治理配置基础上交付受控导出、审计查询保护、调用/安装数据保留策略和管理端工作台，同时保持现有技能市场、分发、生命周期、个人中心与分析接口兼容。

**架构：** 扩展 `GovernanceSnapshot`/`GovernanceStore` 保存导出任务、保留策略、调用事件和审计完整性元数据；通过 `InvocationEventStore` 抽象替换调用事件内存 Map。导出由有界线程池异步执行，所有数据集经过后端白名单投影器后生成 CSV/JSON 产物，下载使用一次性短期令牌。`AuditController`、导出服务与保留策略服务共享角色范围、脱敏投影和审计链追加逻辑。React 管理端新增导出与保留策略工作台。

**技术栈：** Java 21、Spring Boot 3、Jackson、JUnit/MockMvc、现有本地 JSON 存储；React、Vite、Node 内置测试、现有 API client 和样式系统。

## 全局约束

- Skill 仍然只能从本地 ZIP 上传；本阶段不增加在线创建、编辑、重打包或草稿状态。
- 角色沿用 `viewer`、`maintainer`、`reviewer`、`admin`；`reviewer` 是本阶段审计操作角色，`admin` 才能改变保留策略、查看他人导出任务或执行重试。
- 导出只允许 `AUDIT_SUMMARY`、`INVOCATION_SUMMARY`、`INSTALLATION_SUMMARY`，只输出设计规格中的白名单字段；绝不输出 Prompt、Skill 正文、响应正文、token、模型名、sessionId、设备标识、原始请求头或完整 metadata。
- 所有导出、下载、保留策略读写和清理动作写入审计事件；新审计事件追加 SHA-256 链节点，历史 `AuditEvent` JSON 结构保持兼容。
- 审计事件在 M5.2 只标记归档资格，不物理删除；调用和安装明细才执行窗口清理，清理前必须预览并带确认版本。
- 下载产物写入 `./data/governance/exports` 受控目录；快照只保存相对路径、哈希和令牌哈希，禁止路径穿越和明文凭证持久化。
- 导出执行器最大并发 2、队列 20、单任务默认超时 60 秒；服务重启时 `RUNNING` 标记失败，`QUEUED` 最多重新排队一次。
- 保留策略默认审计 365 天、调用 90 天、安装 90 天；安全下限分别为 365、30、30 天，更新使用 `policyVersion` 乐观锁。
- 接口继续使用 `ApiResponse<T>` 和 `RequestIdFilter` 的 requestId；错误码必须稳定且不能泄露堆栈、绝对路径或内部异常文本。
- 标准验证命令：`mvn -B -q -f apps/api/pom.xml test`、`npm.cmd test --prefix apps/web`、`npm.cmd run build --prefix apps/web`。

---

## Task 1：扩展治理快照、导出任务与保留策略模型

**文件：**

- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/ExportJob.java`
- 新建 `ExportDataset.java`、`ExportFormat.java`、`ExportJobStatus.java`、`ExportFilters.java`
- 新建 `RetentionPolicy.java`、`AuditIntegrityEntry.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/M52SnapshotCompatibilityTest.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/ExportJobModelTest.java`

**接口与数据约束：**

- `GovernanceSnapshot` 增加 `invocationEvents`、`exportJobs`、`retentionPolicy`、`auditIntegrity` 字段，旧 JSON 缺失字段按空列表/默认策略加载。
- `ExportJob` 覆盖 `jobId`、数据集、格式、过滤器、请求人/角色/requestId、状态时间、产物相对路径、行数、SHA-256、脱敏字段、下载令牌哈希/过期/消费时间、失败码/安全提示。
- 所有 record 使用不可变副本和 null-safe compact constructor；状态迁移只允许规格定义的方向。
- `RetentionPolicy` 校验版本、下限和时间字段；`AuditIntegrityEntry` 固定 SHA-256 算法字段。
- 为 `GovernanceSnapshot` 保留现有 4/5/6/7 参数兼容构造器，所有 `GovernanceStore` mutation 必须把新字段原样带入，避免破坏 M4.1–M5.1 数据。

- [x] **Step 1: 编写迁移与模型失败测试**

  用临时 JSON 写入不含 M5.2 字段的旧快照，断言加载成功、调用/任务/链为空且保留策略为 365/90/90；覆盖非法下限、非法状态迁移、空 ID、负行数和过期时间早于创建时间。

- [x] **Step 2: 运行聚焦测试并确认失败**

  运行 `mvn -B -q -f apps/api/pom.xml -Dtest=M52SnapshotCompatibilityTest,ExportJobModelTest test`，在模型尚未实现时确认编译/断言失败。

- [x] **Step 3: 实现模型和快照兼容构造**

  添加枚举、过滤器和值对象，完成不可变校验；为 `GovernanceSnapshot` 增加字段及兼容构造器；不改动现有 `AuditEvent` 字段。

- [x] **Step 4: 增加存储操作接口**

  在 `GovernanceStore` 增加导出任务创建/状态更新、保留策略更新、调用事件替换/清理、完整性元数据读取与原子写入方法；所有 mutation 通过现有写锁和原子文件替换完成。

- [x] **Step 5: 验证快照迁移与字段保留**

  运行聚焦测试，并补充断言确保 versions、reviews、installations、authorizations、favorites、configuration 在每个 M5.2 mutation 后保持不变。

## Task 2：调用事件持久化与保留窗口基础

**文件：**

- 新建 `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventStore.java`
- 新建 `GovernanceInvocationEventStore.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventService.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsService.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventStoreTest.java`
- 修改 `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventServiceTest.java`

**接口：**

```java
Collection<InvocationEvent> events();
InvocationEventService.IngestResult putIfAbsent(InvocationEvent event);
int deleteBefore(Instant cutoff);
long countBefore(Instant cutoff);
```

- `GovernanceInvocationEventStore` 读写 `GovernanceSnapshot.invocationEvents`，对 eventId 做幂等去重，对批量摄入使用单次 store mutation。
- `InvocationEventService` 保留现有摄入校验、重复/冲突语义和 ingestion receipts，但不再以内存 Map 作为唯一事实源。
- `AnalyticsService`、个人中心和版本生命周期检查改用 store 事件集合，现有接口响应字段不变。

- [x] **Step 1: 写持久化、幂等和清理失败测试**

  覆盖首次摄入、相同 eventId 重复、不同内容冲突、服务重启恢复、按 cutoff 清理和旧快照兼容；断言分析和个人历史只读取保留后的事件。

- [x] **Step 2: 运行聚焦测试并确认失败**

  运行 `mvn -B -q -f apps/api/pom.xml -Dtest=InvocationEventStoreTest,InvocationEventServiceTest,AnalyticsServiceTest,PersonalCenterServiceTest test`，确认缺少 store 接口或注入时失败。

- [x] **Step 3: 实现 store 适配器并替换服务依赖**

  将现有 map 逻辑迁移到 `GovernanceInvocationEventStore`，保持校验错误码与批量响应不变；对旧 state.json 的缺失字段做空列表归一化。

- [x] **Step 4: 接入统计与个人中心读取**

  让 `AnalyticsService`、`PersonalCenterService`、`VersionLifecycleService` 通过 `InvocationEventStore.events()` 读取，保持 actor 可见范围过滤；清理方法只删除窗口外明细，不触碰安装/审计记录。

- [x] **Step 5: 验证回归**

  运行聚焦测试和现有 invocation/analytics/personal 全部测试，确认服务重启后事件仍可用于摘要聚合。

## Task 3：白名单投影、审计完整性链与错误契约

**文件：**

- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/AuditProjectionService.java`
- 新建 `ExportRowProjector.java`、`AuditIntegrityService.java`、`AuditSummaryRow.java`、`InvocationSummaryRow.java`、`InstallationSummaryRow.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/AuditController.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/AuditProjectionServiceTest.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/AuditIntegrityServiceTest.java`

**接口：**

```java
List<AuditSummaryRow> auditSummary(ExportFilters filters, Actor actor);
List<InvocationSummaryRow> invocationSummary(ExportFilters filters, Actor actor);
List<InstallationSummaryRow> installationSummary(ExportFilters filters, Actor actor);
AuditIntegrityStatus verify();
```

- 投影器从字段白名单构造新 row record，禁止先序列化完整对象再删除字段。
- 审计 metadata 只允许平台生成键；actorId 默认不导出，管理员追责视图只输出短哈希。
- 调用摘要按天/Skill/版本/状态/客户端类型/版本聚合，输出 count、平均耗时、p95、错误数；不带用户、session、模型或 token。
- 安装摘要按天/Skill/版本/状态/客户端聚合，不带设备、路径、凭证或请求头。
- `GovernanceStore.addAudit` 及所有带 audit 的 mutation 统一调用完整性服务；旧审计快照首次加载时建立 genesis 链，新事件追加 `previousHash/hash` 元数据。
- 新增稳定错误码：`EXPORT_NOT_FOUND`、`EXPORT_FORBIDDEN`、`EXPORT_NOT_READY`、`EXPORT_EXPIRED`、`EXPORT_FILTER_INVALID`、`EXPORT_INTERRUPTED`、`EXPORT_QUEUE_FULL`、`EXPORT_FAILED`、`RETENTION_POLICY_INVALID`、`RETENTION_POLICY_CONFLICT`、`RETENTION_PREVIEW_EXPIRED`、`RETENTION_EXECUTION_CONFLICT`。

- [x] **Step 1: 编写敏感字段和 hash chain 失败测试**

  用包含 Prompt、sessionId、token、设备 ID、原始 metadata 的事件样本断言三类 row 永远不含敏感字段；篡改事件、断链、重复序号时断言 `verify()` 返回异常状态且不自动修复。

- [x] **Step 2: 运行聚焦测试并确认失败**

  运行 `mvn -B -q -f apps/api/pom.xml -Dtest=AuditProjectionServiceTest,AuditIntegrityServiceTest,ApiErrorContractTest test`，确认投影器、链服务和错误映射尚未完成。

- [x] **Step 3: 实现投影器和聚合器**

  复用 `AnalyticsAggregator` 的时间、可见范围和 p95 算法；将汇总 row 序列化逻辑集中在一个 projector，CSV/JSON 只消费 row。

- [x] **Step 4: 实现链初始化、追加和校验**

  以现有 audits 顺序创建 genesis 链；通过 store mutation 原子追加审计与链节点；对旧节点缺失链信息只在迁移时补链，不覆盖事件内容。

- [x] **Step 5: 收紧 `/api/v1/audit` 查询**

  reviewer 只能查询其可见 Skill/team 范围的脱敏摘要，admin 可查全量；分页、筛选和未授权资源不泄露存在性；所有查询动作追加审计。

## Task 4：异步导出服务、任务状态和下载安全

**文件：**

- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/ExportService.java`
- 新建 `ExportWorker.java`、`ExportArtifactService.java`、`ExportTokenService.java`
- 新建 `ExportRequest.java`、`ExportJobView.java`、`DownloadUrlResponse.java`
- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/AdminExportController.java`
- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/ExportException.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/ExportServiceTest.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/AdminExportControllerTest.java`

**接口：**

```java
ExportJobView create(ExportRequest request, Actor actor, String requestId);
PageResponse<ExportJobView> list(ExportQuery query, Actor actor);
ExportJobView detail(UUID jobId, Actor actor);
DownloadUrlResponse issueDownloadUrl(UUID jobId, Actor actor, String requestId);
Resource download(UUID jobId, String token, Actor actor, String requestId);
ExportJobView retry(UUID jobId, Actor actor, String requestId);
```

路由：`/api/v1/admin/exports` 下的 POST/GET、`/{jobId}`、`/{jobId}/download-url`、`/{jobId}/download`、`/{jobId}/retry`。

- 创建任务先验证数据集、格式、时间范围、Skill/team/client/status 过滤器和 actor scope，再写入 `QUEUED` 任务并追加 `EXPORT_CREATED` 审计。
- 使用有界 `ThreadPoolExecutor`；队列满返回 `EXPORT_QUEUE_FULL` 且不落半成品任务；任务状态更新与产物元数据原子保存。
- CSV 使用 UTF-8、固定列顺序和安全转义；JSON 为 row 数组，不输出完整领域对象。
- 产物写入规范化 `data/governance/exports` 子目录，计算 SHA-256；文件名只含固定前缀、数据集和 UUID。
- `POST download-url` 生成随机令牌，快照只保存哈希/过期/消费时间；`GET download` 校验 jobId、actor、过期和一次性消费，并对成功/拒绝下载都写审计。
- 过期扫描标记任务 `EXPIRED` 并隔离/删除产物；下载只返回安全错误，不返回本地路径或堆栈。
- 启动恢复：`RUNNING` -> `FAILED/EXPORT_INTERRUPTED`，`QUEUED` 只重排一次。

- [x] **Step 1: 编写服务和 MockMvc 失败测试**

  覆盖角色矩阵、过滤器校验、状态迁移、队列满、重启恢复、路径穿越、CSV/JSON 白名单、下载令牌过期/重复使用、SHA-256 和审计行为。

- [x] **Step 2: 运行聚焦测试并确认失败**

  运行 `mvn -B -q -f apps/api/pom.xml -Dtest=ExportServiceTest,AdminExportControllerTest test`，确认服务、worker、控制器尚未实现。

- [x] **Step 3: 实现任务服务和执行器**

  增加有界线程池、状态迁移守卫、任务持久化和启动恢复；通过 `ExportRowProjector` 生成三类数据集，统一写入 ArtifactService。

- [x] **Step 4: 实现下载令牌与文件安全**

  使用加密随机 token、SHA-256 存储、15 分钟 TTL、一次性消费；校验路径规范化、响应头和 `nosniff`，成功与拒绝都追加审计。

- [x] **Step 5: 实现控制器和错误映射**

  所有响应使用 `ApiResponse<T>`，列表支持分页与状态/数据集筛选，reviewer 只看本人任务，admin 可看全量并重试失败任务。

- [x] **Step 6: 验证导出链路**

  运行聚焦测试，随后手工创建三类导出，轮询至完成，下载并核验 SHA-256、固定列和脱敏字段。

## Task 5：保留策略服务、预览和执行

**文件：**

- 新建 `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionService.java`
- 新建 `RetentionPreview.java`、`RetentionExecutionRequest.java`、`RetentionExecutionResult.java`
- 新建 `AdminRetentionController.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/RetentionServiceTest.java`
- 新建 `apps/api/src/test/java/com/huawei/skillcenter/governance/AdminRetentionControllerTest.java`

**接口与规则：**

```java
RetentionPolicy get(Actor actor);
RetentionPolicy update(RetentionPolicyMutation request, Actor actor, String requestId);
RetentionPreview preview(Actor actor, String requestId);
RetentionExecutionResult execute(RetentionExecutionRequest request, Actor actor, String requestId);
```

路由：`/api/v1/admin/retention` 的 GET/PUT、`/preview`、`/execute`。

- reviewer/admin 可读；只有 admin 可更新、预览和执行。
- 更新拒绝低于安全下限的值，并要求 `policyVersion`；成功追加 `RETENTION_POLICY_UPDATED` 审计。
- 预览返回调用/安装可清理数量、审计归档资格数量、时间边界、估算空间和 `previewVersion`，不修改数据；预览令牌默认 10 分钟有效。
- 执行必须提交同一策略版本和未过期预览确认；按 cutoff 删除 invocation events 和 installations，保留审计、任务元数据和链引用。
- 执行采用幂等批次 ID；重复提交返回原执行结果，版本变化或预览过期分别返回 `RETENTION_EXECUTION_CONFLICT` / `RETENTION_PREVIEW_EXPIRED`。
- 执行后追加清理摘要审计，不记录被删除的用户、设备或原始事件内容。

- [x] **Step 1: 编写保留策略失败测试**

  覆盖默认策略、最小值、乐观锁、reviewer 只读、预览幂等、预览过期、执行确认、调用/安装删除、审计保留和审计链追加。

- [x] **Step 2: 运行聚焦测试并确认失败**

  运行 `mvn -B -q -f apps/api/pom.xml -Dtest=RetentionServiceTest,AdminRetentionControllerTest test`，确认缺少服务、DTO 和路由。

- [x] **Step 3: 实现读取、更新和预览**

  使用 `GovernanceStore` 快照计算窗口计数与估算值；生成不含敏感明细的 preview token，并记录 requestId。

- [x] **Step 4: 实现原子执行和幂等批次**

  在单次 mutation 中清理调用/安装明细、更新批次结果并追加审计/完整性节点；保留审计事件及导出关联引用。

- [x] **Step 5: 实现控制器和错误契约**

  映射权限、版本冲突、预览过期和非法策略到统一 `ApiResponse` 错误 envelope，响应不包含内部异常。

- [x] **Step 6: 验证清理后的统计行为**

  运行聚焦测试，确认清理后统计只包含保留窗口，审计查询与历史导出任务仍可读。

## Task 6：前端导出与保留策略工作台

**文件：**

- 修改 `apps/web/src/api/skillApi.js`
- 新建 `apps/web/src/AuditExportView.jsx`
- 新建 `apps/web/src/exportGovernance.js`
- 修改 `apps/web/src/App.jsx`、`apps/web/src/styles.css`
- 修改 `apps/web/tests/api-client.test.mjs`
- 新建 `apps/web/tests/export-governance.test.mjs`

**API client：**

- `createExport`、`listExports`、`getExport`、`issueExportDownloadUrl`、`retryExport`
- `getRetentionPolicy`、`updateRetentionPolicy`、`previewRetention`、`executeRetention`
- 所有方法沿用 actor headers、requestId 和统一错误解析；下载 URL 只使用后端签发结果。

**UI：**

- `AuditExportView` 包含“导出任务”和“保留策略”两个区块。
- 任务区支持数据集/格式/时间范围/Skill/team/client/status 筛选、创建、状态轮询、失败原因、脱敏字段、SHA-256、签发下载 URL 和重试。
- 策略区展示当前版本、三个保留天数、下限提示、预览结果、预览过期时间和二次确认执行。
- reviewer 可创建并查看本人范围内的任务；viewer/maintainer 隐藏导出与策略入口；所有错误映射为中文安全提示。
- 状态轮询使用退避并在 `COMPLETED`、`FAILED`、`EXPIRED` 或组件卸载时停止；下载按钮不拼接本地路径。

- [x] **Step 1: 编写 API client 与纯函数失败测试**

  断言 URL 编码、HTTP 方法、requestId/actor headers、状态标签、权限可见性、错误码提示、轮询停止和策略表单校验。

- [x] **Step 2: 运行前端聚焦测试并确认失败**

  运行 `npm.cmd test --prefix apps/web -- export-governance.test.mjs`，确认新 client/helpers/UI 尚未完成。

- [x] **Step 3: 实现 client 和纯函数**

  复用现有 `queryString`、`requestJson` 和 API envelope 解析；新增 `formatExportStatus`、`formatExportError`、`canViewExportWorkbench`、`validateRetentionForm`。

- [x] **Step 4: 实现工作台和权限状态**

  使用现有页面布局、卡片、表格和 toast 样式；处理 loading/empty/error/forbidden/expired 状态；保留现有市场、审核、安装和分析导航。

- [x] **Step 5: 验证前端行为和构建**

  运行 `npm.cmd test --prefix apps/web` 与 `npm.cmd run build --prefix apps/web`，确认 Vite 与 Sites bundle 均成功。

## Task 7：端到端联调、文档与 M5.2 状态收口

**文件：**

- 新建 `docs/project/M5.2-controlled-export-audit-status.md`
- 更新 `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md` 中已完成的 M5.2 条目
- 如存在运行说明文档，补充本地导出目录、令牌 TTL 和清理边界

- [x] **Step 1: 运行完整自动化验证**

  运行 `mvn -B -q -f apps/api/pom.xml test`、`npm.cmd test --prefix apps/web`、`npm.cmd run build --prefix apps/web`；记录测试数量、构建结果和失败原因。

- [x] **Step 2: 启动真实 API 与前端联调**

  使用 admin 创建审计/调用/安装三类导出；使用 reviewer 创建并查看本人任务；使用 viewer/maintainer 验证入口隐藏和接口拒绝；轮询、签发 URL、下载并核验文件哈希、固定列和无敏感字段。

- [x] **Step 3: 验证保留策略与重启**

  写入跨窗口调用/安装/审计样本，完成策略预览与执行；确认调用/安装明细清理、审计仍在、链校验通过；重启 API 后确认导出任务、策略、调用事件和完整性元数据恢复，`RUNNING`/`QUEUED` 按规则处理。

- [x] **Step 4: 扫描兼容性与敏感字段**

  使用 `rg` 扫描导出 DTO、CSV/JSON 序列化和前端展示，确认没有 Prompt、Skill 正文、token、sessionId、设备 ID、绝对路径或堆栈泄露；扫描文档中的未完成标记与临时文本。

- [x] **Step 5: 编写状态报告并更新路线图**

  记录已交付接口、权限矩阵、默认策略、任务状态、审计动作、验证证据、已知本地 JSON 限制和 M5.3 后续边界；只勾选实际完成的 M5.2 子项，不提前勾选限流、防重放、对象存储或运维监控。

**完成标准：** Task 1–6 的聚焦测试通过；全量后端测试、前端测试和生产构建通过；真实联调证明三类导出、下载审计、权限隔离、保留清理、链校验和重启恢复有效；M5.2 状态报告准确区分已交付能力与后续阶段。
