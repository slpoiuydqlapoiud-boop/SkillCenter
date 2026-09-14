# 剩余编码任务收口状态

日期：2026-09-09

> 当前活动范围以 [部门级 Skill 生命周期平台任务分解](department-task-breakdown.md) 为准。本文前面的企业级云原生/生产外部接入条目是历史记录，不计入部门版完成率；部门版编码任务已收敛，当前只剩本机 MySQL 运行态 smoke 验收。

## 生产配置前置检查（2026-09-08）

- [x] 新增只读 `scripts/verify-production-config.ps1` 与可复用 `ProductionConfigCheck.psm1`，覆盖 64 项生产选择器、端点安全格式、Secret Manager 引用、外部扫描能力和跨实例运行配置。
- [x] 默认本地 JSON/Mock/local 配置会明确返回 `NOT_READY`；HTTPS、`secret://`、PostgreSQL/Redis、真实 Provider、外部扫描、JWT/JWKS、受控发布和搜索刷新配置缺失或非法时 fail-closed。
- [x] 输出只包含变量名、状态、稳定原因码和修复提示，不输出 Secret 值、Token、凭据连接串或上游正文；Pester 回归 `8/8` 通过，并拒绝 JDBC URL 嵌入式用户信息。
- [x] API `application.yml` 已将生产选择器改为环境变量可覆盖，默认值保持本地开发兼容；配置通过 Spring focused 回归和 integration profile 重启验收；临时完整配置 CLI 验收为 `64/64`、exit `0`，真实本地空配置仍按预期 exit `2`。

## CCE + CSMS 生产部署资产（2026-09-08）

- [x] 新增 `deploy/k8s/skillcenter/` Helm Chart，覆盖 API/Web Deployment、Service、Ingress、HPA、PDB、NetworkPolicy、ServiceMonitor、数据库迁移 Hook 和 CCE `SecretProviderClass`。
- [x] 新增 API/Web 多阶段 Dockerfile；API 使用 JDK/JRE 21，Web 使用 Node 构建并由非 root Nginx 运行。
- [x] CCE 密钥通过 `useWorkloadCred: "true"` + CSMS `latest` 版本读取，敏感环境变量只来自同步 Secret；生产 values 保留 `CHANGE-ME`，不包含凭据。
- [x] 迁移 Hook 增加 `skill-center.migration-only=true` 一次性退出监听器，避免全局调度器导致迁移 Job 长驻；新增 API 回归覆盖上下文关闭行为。
- [x] 部署资产 Pester `6/6`、相关生产预检/生命周期/交接回归合计 `25/25` 通过；Helm lint 与生产 values template 渲染通过。
- [ ] 真实 CCE 集群、CSMS 对象、Workload Identity/IAM 委托、SWR、Ingress/TLS 和外部 Provider 仍需目标云环境配置与验收；本地渲染不等价于生产部署完成。

## CI 质量门禁骨架（2026-09-08）

- [x] 新增 `.github/workflows/skillcenter-ci.yml`：契约、Telemetry SDK、Web、API 四类检查并行执行，API 保留 Testcontainers 集成回归，汇总 job 对任一失败统一阻断。
- [x] CI 只读代码仓库，不部署、不接触真实凭据、不把本地/测试环境结果当作生产验收；生产 readiness、外部证据、审批和 UAT 仍由目标环境门禁负责。
- [x] 设计与执行边界记录于 `docs/project/ci-pipeline.md`。

## SLO 测量工具（2026-09-08）

- [x] 新增 `scripts/slo-smoke.mjs`：对指定只读 HTTP(S) 入口执行有界并发采样，输出成功率、状态分布、P50/P95/P99 和阈值结论；网络/超时/HTTP 错误分类稳定，不读取响应正文。
- [x] 参数校验拒绝凭据 URL、非法并发和无界时长；退出码区分通过、阈值不达标和工具运行错误。
- [x] `scripts/slo-smoke.test.mjs` focused 回归通过，真实本地 integration API 冒烟得到 185 次请求、100% 成功、P95 17ms；本地结果不替代生产容量/SLO/UAT 验收。
- [x] 使用边界记录于 `docs/project/slo-evidence-runbook.md`。

## 统一生命周期验证器的生产交付 gate（2026-09-08）

- [x] `scripts/verify-lifecycle.ps1` 新增显式 `-CheckProductionHandoff` 与 `-ProductionHandoffBaseUrl`；默认本地 Web/API 回归路径不调用外部生产 gate。
- [x] 开启 gate 后复用只读 `verify-production-handoff.ps1`，底层退出码 `2`（生产未就绪）和 `1`（请求/响应校验错误）分别保留，避免 CI 将外部前置缺失误报为测试失败。
- [x] 增加 Windows PowerShell 5.1 UTF-8 BOM 解析回归；统一生命周期合约测试 `4/4` 通过，真实 `-CheckProductionHandoff -SkipBuild -SkipSmoke` 完成 Web/API 回归后以预期退出码 `2` 结束。

## FND-012 Web 键盘可访问性（2026-09-08）

- [x] 应用壳层新增仅在获得焦点时显示的“跳转到主要内容”链接，并为工作区提供稳定的 `#main-content` 焦点入口。
- [x] 顶部导航和侧栏操作项补充 `:focus-visible` 高对比焦点样式，不改变业务 API、路由或权限逻辑。
- [x] 新增无浏览器依赖的源码契约回归；Web 全量测试 `169/169` 通过，生产构建通过。

## QUA-008 Web 键盘与对比度质量门禁（2026-09-08）

- [x] 新增共享弹窗可访问性工具：打开弹窗时进入指定初始控件，Tab 在弹窗内环绕，Escape 触发关闭；已接入发布、生命周期和合集创建弹窗。
- [x] 应用壳层统一 `:focus-visible` 高对比焦点样式，上传拖拽区通过 `:focus-within` 保持可见焦点，并加入 `prefers-reduced-motion` 降级。
- [x] 新增关键控件/状态颜色对比度契约（最低 4.5:1，焦点指示器最低 3:1）和 JSDOM/React 输入事件兼容测试环境；Web 全量测试 `174/174` 通过，生产构建通过。
- [x] 本地真实浏览器验收通过：首个 Tab 聚焦 Skip Link，Enter 后焦点落到 `#main-content`；发布弹窗初始焦点进入文件控件，Tab 环绕并由 Escape 关闭。
- [ ] 仍需真实浏览器、辅助技术和目标分辨率下的人工键盘/对比度验收；自动契约不替代最终无障碍 UAT。

## FND-009 Web 系统错误状态与未知路由（2026-09-08）

- [x] 新增统一错误状态模型，将 403、404、500、503/超时和未知错误映射为安全的 forbidden、not-found、server-error、maintenance 状态；错误文案不回显 Skill ID、内部异常或敏感上下文。
- [x] 未知 hash 路由稳定归一到 `#/404`，详情加载失败进入统一错误页；403 提供返回市场，服务异常/维护提供返回市场与重试。
- [x] 新增行为级错误归一化、路由回归和 App 组件级 403/404/503 安全页与重试回归；Web 全量测试 `181/181` 通过，生产构建通过，`git diff --check` 无错误。
- [ ] 仍需在目标 API 网关/权限策略下补充真实 403/404/5xx 浏览器验收；本地 mock/集成环境不替代生产错误码联调。

## Provider 生产证据数据源分区（2026-09-08）

- [x] 新增 `SkillRunner.dataSource()` 契约，默认 Runner 保持 `mock`；OpenClaw HTTP Runner 明确标记为 `production`，避免在真实 Provider 接入后继续把质量证据归类为 Mock。
- [x] 评测排队、运行中、失败、完成、逐 Case 结果和 Quality Snapshot 统一沿用 Runner 数据源，并拒绝未知数据源；多 Case 评测不允许混合 `mock/production` 分区。
- [x] 新增生产 Runner 端到端回归，验证查询过滤和后续 Benchmark/发布门禁所依赖的证据身份可追溯；定向 API 测试通过，最终 API 全量回归 `1154` tests、0 failure、0 error、8 skips。
- [x] 新增可显式开启的 Provider 连通性定时探测器，默认关闭；定时探测覆盖全部 Provider 且不写用户审计，避免长期运行时探测证据失鲜。
- [x] 新增 Provider 探针最近状态内存缓存、可配置 TTL、管理员查询 API 和 Quality Center 展示；过期结果安全降级为 `STALE/PROBE_EXPIRED`，不回显 endpoint 或 credential-ref；Web 全量 `181/181`、生产构建通过。
- [ ] 真实 OpenClaw/DeepEval endpoint、Secret Manager、网络出口和生产 UAT 仍需目标环境联调；本地生产分区契约测试不替代真实 Provider 验收。

## 搜索探测续期与长期运行稳定性（2026-09-08）

- [x] 新增可显式开启的 `SkillSearchConnectivityProbeScheduler`：默认关闭，integration profile 每 60 秒续期一次 OpenSearch 探测；续期只刷新受控健康状态，不重复写治理审计。
- [x] 增加调度器委托与“续期不新增审计”回归；本地 8081 重启后 `/api/v1/skills` 连续 185 次请求均为 `200`，成功率 `100%`、P95 `17ms`。
- [ ] 生产仍需在目标环境配置合理探测周期、告警和多实例协调策略；本地定时续期不替代生产 HA/SLO 验收。

## API 全量回归的异步尾写稳定性（2026-09-08）

- [x] 已定位现象：`CompatibilityMatrixServiceTest.cancellingRunningMatrixCancelsTheChildEvaluation` 单独执行通过，但 API 全量首轮偶发在 JUnit `@TempDir` 清理阶段发现遗留 `quality.json`；故障发生在测试扩展关闭阶段，不是断言失败。
- [x] 已为评测与矩阵 worker 增加受控任务跟踪、有限 `awaitQuiescence` 和 Spring `@PreDestroy` 收敛；取消接口仍保持非阻塞，清理/关闭阶段等待已启动任务结束。本轮无重试全量 API 回归为 `1144` 项、0 failure、0 error，兼容性矩阵临时目录清理错误未再出现。

## TEL-022 安装事件持久化幂等收口（2026-09-08）

- [x] 安装事件不再依赖进程内 `ConcurrentHashMap` 保存去重状态；接收成功时将 SHA-256 事件指纹作为 metadata-only 字段写入既有治理审计记录，不落原始事件正文。
- [x] 服务重启后，同一 `eventId` 加同一指纹返回 duplicate；同一 `eventId` 携带不同事件内容返回 `EVENT_ID_CONFLICT`，不会重复修改安装状态；兼容没有指纹字段的历史审计记录。
- [x] 治理 revision 竞争时，安装事件写入失败会重载最新快照并重新检查持久化回执；已由其他实例成功接收的事件返回确定性 duplicate/conflict，未形成回执的真实冲突继续向上返回。
- [x] 安装事件服务、控制器和调用事件相关回归通过；API 全量回归执行 `1143` 项，0 failure、0 error，8 个 Windows 符号链接能力跳过；其中一次 Windows/JUnit 临时目录清理出现偶发 error，自动重试后通过，单测独立重跑也通过；生产多实例仍需在目标 PostgreSQL/共享治理后端完成并发压测、故障注入和恢复验收。

## 治理审计追加的多实例 revision 自恢复（2026-09-08）

- [x] `GovernanceStore.addAudit` 在 PostgreSQL 多实例之间发生 revision 竞争时，会重新加载最新治理快照并有限重试一次；普通业务聚合写入仍保留显式 `GOVERNANCE_STATE_CONFLICT`，不会自动覆盖用户修改。
- [x] 新增回归覆盖“其他实例先写入审计、当前实例随后追加审计”的场景，验证外部审计与当前审计均保留且 revision 连续递增。
- [x] 本地 8081 integration API 已重新加载该实现；OpenSearch/MinIO 管理探测连续返回 `200`，避免旧进程 revision 过期导致启动探测无法收敛。

## TEL-021 事件上报最小参考 SDK（2026-09-08）

- [x] 新增 `sdk/skillcenter-telemetry` 无第三方依赖参考包：安装/调用事件分别调用现有批量 API，沿用 `event-batch.schema.json` 的字段边界。
- [x] 客户端提供有界离线队列、可注入持久化存储、批量 flush、部分成功回执处理、网络/408/429/5xx 有界指数退避和 `Retry-After` 限制；传输失败不丢队列。
- [x] 入队前拒绝未知字段及 Prompt、输入输出、Token、Secret 等非遥测字段；认证头只由调用方注入，不写入队列。
- [x] SDK 增加 `index.d.ts` 类型契约与 `package.json` 发布白名单，包内容只包含运行时、声明、README 和清单，不把测试夹具发布给企业客户端。
- [x] SDK 使用调用方注入的 `now`/`randomUUID` 生成新事件，保证离线补报、测试和追踪关联 ID 的确定性；不改变服务端事件字段边界。
- [x] SDK 客户端类型边界与 Schema 对齐：`gateway` 允许调用事件但不允许安装事件，避免客户端本地入队后被服务端拒收。
- [x] SDK 回归 `8/8` 通过，并完成 `npm pack --dry-run` 验收；覆盖批量分流、跨客户端从共享存储恢复队列、传输重试、Retry-After 上限、敏感字段拒绝、篡改队列恢复隔离、注入式事件生成、Schema 客户端边界和可发布包边界。
- [x] 使用临时 TypeScript 编译器以 `--strict --noEmit` 校验 `index.d.ts` 通过；仓库仍不锁定 TypeScript 依赖，避免给无 TypeScript 的 JavaScript 客户端增加安装负担。

## A4.2 OpenSearch 搜索适配与本地联调收口（2026-09-08）

- [x] 已完成 `HttpSkillSearchIndex` 外部搜索适配器：显式 `opensearch` 选择、有限 HTTP 请求/响应、严格字段白名单、稳定错误码、探测 TTL 和 fail-closed readiness；默认 JSON/PostgreSQL 行为不变。
- [x] 已完成管理员搜索索引探测、运营中心按钮、Web API client、固定版本 OpenSearch 本地 Compose 服务和环境依赖验收脚本；不会把探测结果当作生产外部验收证据。
- [x] 当前本机真实联调通过：OpenSearch `2.17.1`、PostgreSQL `16.4`、Redis `7.4`、MinIO、Prometheus、Grafana、Alertmanager 均可达；Integration API `8081` 的搜索重建返回 `200/READY`，Skill API 返回 `200`。
- [x] A4.2 聚焦搜索回归、API 全量和 Web 全量/生产构建已通过；此前 API 全量为 `1137` tests，安装事件持久化幂等增量后为 `1139` tests，Schema 边界增量后为 `1141` tests，本轮搜索探测续期增量后为 `1143` tests；全量执行最终 exit `0`，0 failure、0 error，另有 8 个 Windows 符号链接能力跳过，Web 为 `167/167`。
- [x] 修复环境验证器对 `java -version` 标准错误输出的误判；`verify-environment.ps1 -CheckServices -CheckObservability` 当前全部检查为 `READY`。
- [x] 环境验证器新增 `-CheckRuntime`：可跨 Windows PowerShell 5.1/PowerShell 7 验证 Web、默认 API、integration API 与 readiness HTTP 入口，输出不包含响应体或敏感信息。
- [ ] 仍需生产目标环境验收：OpenSearch/Redis HA、TLS/Secret Manager、消息平台或生产 Redis 方案、多实例容量/SLO、备份恢复、真实 Provider 与 UAT；本地容器联调不替代这些证据。

## A3-2 Skill 范围与版本关系共享事实源增量（2026-08-27）

- 已新增 `SkillScopeRepository` 与 `SkillRelationRepository` 持久化端口；默认仍使用 JSON，显式配置 `skill-center.skill-scope-backend=postgresql` / `skill-center.skill-relation-backend=postgresql` 且全局 persistence 为 PostgreSQL 时切换到 JDBC 实现，不双写、不自动 JSON fallback。
- Flyway V14/V15 新增 `skill_scopes` 与 `skill_relations` 关系表；范围 revision 条件更新、关系活动业务键唯一约束、关系替换事务和 PostgreSQL advisory lock 用于保护多实例写入边界。
- 授权、关系服务和生命周期投影已改为依赖持久化端口；`PersistenceArtifactCatalog` 能区分两个 Skill 资产数据库事实源，避免将其误纳入 JSON snapshot 语义。
- 新增 `SkillAssetBackendReadinessService` 与平台 `SKILL_ASSET_STORE` 组件：JSON 明确为 `DEGRADED/SKILL_ASSET_JSON_ONLY`，PostgreSQL 必须满足全局 persistence `READY` 与 V14/V15 schema；状态未就绪时返回稳定阻断 reason code。
- 新增后端 selector、迁移、readiness 聚合和配置边界回归；API 全量回归 `1020` tests，0 failure、0 error、43 个 Docker capability skips。真实 PostgreSQL 多实例并发、迁移/回滚、备份/PITR、容量/SLO 与生产验收仍需目标环境完成。

## Benchmark 共享后端与自动评测生产门禁增量（2026-08-26）

- 新增 `BenchmarkRepository` 持久化端口；Benchmark 默认继续使用本地 JSON，显式配置 `skill-center.benchmark-backend=postgresql` 且全局 persistence 为 PostgreSQL 时切换到 JDBC JSONB 事实源，不双写、不自动回退。
- Flyway V13 新增 `skill_benchmarks`，以实验唯一索引保护多实例幂等写入，并保留 Benchmark payload 的对象结构约束；Persistence artifact catalog 和留存服务均通过端口识别该共享后端。
- 新增 `BenchmarkBackendReadinessService`，平台 Readiness 暴露 `BENCHMARK_STORE`：JSON 明确为 `DEGRADED/BENCHMARK_JSON_ONLY`，PostgreSQL 必须满足全局 PostgreSQL `READY` 和 V13 schema；自动 Benchmark/决策调度器在共享后端未就绪时 fail-closed。
- 新增后端选择、JDBC 写入边界、V13 migration、Readiness 聚合和调度门禁回归；API 全量回归 `1010` tests，0 failure、0 error、43 个 Docker capability skips。真实 PostgreSQL 多实例并发、迁移、备份/PITR、容量/SLO 与生产验收仍需目标环境完成。

## 优化实验自动评测推进增量（2026-08-26）

- 新增 `OptimizationExperimentReconciliationScheduler`：生产可显式开启后台恢复，枚举共享实验状态并对 `QUEUED/RUNNING` 实验复用幂等 `reconcile`；完成但尚未生成 Benchmark/决策的实验也可按配置继续推进，单个实验失败不会阻断其他实验。
- 调度器默认关闭；开启时要求实验主记录、优化工作项和质量证据均使用已就绪的共享 PostgreSQL，并校验全局 persistence `READY` 与质量证据 schema，条件不满足时 fail-closed。
- 新增独立的 `auto-benchmark-enabled` 与 `auto-decision-enabled` 配置（默认关闭）；开启后按 `reconcile → benchmark → decide` 顺序执行幂等动作，缺少基线/证据时保留可重试状态。自动推进不会发布、回滚或完成工作项/Skill，人工决策与发布边界保持不变；新增 focused 回归覆盖自动顺序和异常隔离；API 全量回归 `997/997`，0 failure、0 error、43 个 Docker capability skips。

## 优化实验共享后端生产 Readiness 增量（2026-08-26）

- 新增 `OptimizationExperimentBackendReadinessService`，把实验主记录、发布后观察和效果评估的后端状态纳入生产交付判断：JSON 明确为 `DEGRADED`，PostgreSQL 必须同时满足全局 PostgreSQL 控制面 `READY` 和 Flyway V12 schema。
- `PlatformReadinessService` 新增 `OPTIMIZATION_EXPERIMENT_STORE` 组件，返回稳定 reason code；后端未就绪时生产发布继续 fail-closed，不把已配置但未迁移的实验 PostgreSQL 误报为可用。
- 新增 readiness 服务、V12 schema 缺失和平台聚合测试；本轮 focused 回归已通过，完整 API 回归将在本轮收口时重新记录。

## 优化实验闭环 PostgreSQL 共享持久化增量（2026-08-26）

- `OptimizationExperiment`、发布后运行观察和效果评估统一抽出持久化端口；JSON 仍为默认本地实现，服务、发布门禁、留存治理和回归分析改为依赖端口，切换后不再绑定 JSON 具体类。
- 新增 `skill-center.optimization-experiment-backend`（默认 `json`）；显式配置 `postgresql` 且全局 persistence 为 PostgreSQL 时选择 JDBC JSONB 实现，不双写、不自动回退。
- Flyway V12 新增实验主记录、观察证据和评估证据三张表；活动实验由数据库部分唯一索引保护同一工作项只能有一个 `QUEUED/RUNNING` 实验，实验替换在事务内锁定并保持决策快照不可变。
- 新增后端选择、迁移定义和全链路端口装配测试；本轮 API 全量 `988/988`，0 failure、0 error、43 个 Docker capability skips。真实 PostgreSQL 多实例并发、迁移、备份/PITR 和容量/SLO 验收仍开放。

## 平台 Readiness 纳入运行指标存储增量（2026-08-26）

- `PlatformReadinessService` 新增 `OPERATIONS_METRICS_STORE` 组件，聚合 `OperationsMetricsService.readiness()`，使本地指标、Redis 不可用和 Redis `READY` 在生产交付准入中分别可见。
- 本地/非共享指标状态标记为 `DEGRADED`，共享 Redis 不可用或状态读取异常标记为 `NOT_READY`；响应只返回稳定 reason code 和安全摘要，不泄露路径、连接信息或异常正文。
- 保留既有构造器与本地开发兼容；新增平台 readiness focused 测试。API 全量回归结果见本轮验证。

## Operations Alert 多实例指标共享门禁增量（2026-08-26）

- 调度器启动门禁从仅校验告警状态 Redis 扩展为同时校验运行指标存储：必须为共享 Redis、连接状态 `READY` 且可读取；Memory/JSON 或 Redis 不可用均 fail-closed，避免多实例告警依据分叉。
- 新增 `OperationsMetricsReadiness` 安全投影与 `OperationsMetricsService.readiness()`，不暴露连接信息、路径或异常正文；本地指标 API 和本地默认关闭调度行为保持兼容。
- 新增指标 readiness、调度拒绝本地指标状态和 Redis ready focused 测试；API 全量 `983/983`，0 failure、0 error、43 个 Docker capability skips。

## Operations Alert 周期评估增量（2026-08-26）

- 新增 `OperationsAlertEvaluationScheduler`：生产可显式开启后台周期评估，复用现有 Operations Alert 状态仓储、激活/恢复去重和通知 sink；不会复制告警判定逻辑或改变管理员查询 API。
- 评估窗口、首次延迟和间隔均可配置；本地默认关闭，调度任务异常只返回稳定的后台失败边界，不泄露 Provider/存储异常，也不会终止后续调度。
- 调度开启时新增共享状态门禁：只有 Redis `READY` 才允许启动，Memory 或 Redis 不可用均 fail-closed，避免多实例重复告警和状态分叉。
- 新增 focused 测试覆盖窗口解析、异常隔离、非法配置和 Memory 状态拒绝；API 全量 `980/980`，0 failure、0 error、43 个 Docker capability skips。

## Skill 版本下架闭环增量（2026-08-26）

- `VersionLifecycleService` 的 `deprecate/withdraw` 已与治理快照形成单次持久化变更：版本状态、生命周期审计、分发授权撤销和用户通知不会各自独立落盘。
- `withdraw` 仅撤销目标 Skill/版本仍未消费且未撤销的授权；已消费授权保留不可变历史。撤销返回稳定 `VERSION_WITHDRAWN` 语义，并额外记录授权数量，不保存 Token 或摘要。
- 对受影响安装用户和未消费授权申请人发送确定性去重的生命周期通知；通知只包含 Skill/版本、状态、原因和替代版本，不回显 Token、凭据或上游异常。
- 新增服务回归覆盖废弃通知、下架撤销、通知去重、脱敏审计和撤销后消费拒绝；API 全量执行完成 `976` 个测试，业务断言 0 failure，43 个 Docker capability skips；唯一异常是既有 Windows JUnit 临时目录关闭竞态，失败类已按既定规则单独复跑通过。

## M2 分片上传分布式存储增量（2026-08-26）

- 已将可恢复上传抽象为 `ResumableUploadStore`：本地实现继续提供单实例开发能力，原有 `ResumablePackageUploadService` API 门面保持兼容；本地会话 TTL、活动会话数、活动字节容量和定时清理继续生效。
- 已增加 S3-compatible 原始对象边界：Redis 保存上传元数据、租约、偏移和容量计数，对象存储保存不可变分片；分片写入使用 Redis reservation → 条件 PUT → Redis CAS commit，两阶段失败不会推进可见偏移。
- 已增加分布式后端选择 `skill-center.package-upload-backend=distributed`、管理员只读 readiness `/api/v1/admin/platform/resumable-uploads/readiness`，以及 owner 校验、重复分片幂等、对象拼装和过期清理代码；默认仍为 `local`，未配置 Redis/S3 时不改变开发启动行为。
- 已补齐 Redis 写租约过期恢复、活动续租、条件 abort、幂等分片内容冲突校验；分布式 readiness 同时检查 Redis PING 和对象存储配置/凭据状态，并已纳入平台聚合 readiness，避免生产交付只看到局部健康状态。
- 新增存储边界、Redis 脚本、S3 原始对象、分布式拼装、平台 readiness 和故障回收回归测试；本轮 API 全量 `974/974`，0 failure、0 error、43 个 Docker capability skips。真实 Redis/S3 多实例联调、对象孤儿清理压测和生产迁移验收仍开放。

## M2 可恢复分片上传增量（2026-08-26）

- 新增受维护者/管理员保护的上传会话 API：创建会话、查询进度、按 `Content-Range` 顺序追加分片、完成提交；会话绑定创建者，文件名、总大小、分片大小和偏移均做边界校验。
- 分片完成后复用既有 ZIP 安全校验、SHA-256 制品存储、版本授权和审核队列，不产生新的草稿或绕过安全门；失败时清理临时会话文件。
- 上传会话新增可配置空闲 TTL、最大活动会话数、最大临时字节数和定时清理；创建、查询、分片写入会续期，取消、完成和过期会释放容量，超限返回 `UPLOAD_CAPACITY_EXCEEDED`，并通过会话锁协调清理与并发写入。
- Web API client 与发布弹窗已接入同一生命周期：创建/恢复会话、按服务端断点继续分片、实时进度、网络失败后继续上传，以及取消时清理临时会话；上传完成后仍复用既有 ZIP 安全校验、制品存储、版本授权和审核队列。本轮回归 API `957/957`、Web `163/163`，0 failure、0 error，生产构建和生命周期 verifier 通过。生产多实例仍需将会话状态迁移到共享对象存储/数据库并补容量与清理策略。

## M19 质量回归运营告警增量（2026-08-25）

- 新增质量回归只读探针：从发布后评估中按 Skill 选取最新结论，旧回归在后续健康评估后自动从活动集合移除；回归条目稳定排序并限制为最多 100 条。
- Operations Alert 新增 `QUALITY_REGRESSION`：回归数量大于 0 时产生 `QUALITY_REGRESSIONS_DETECTED/ACTIVE`，质量评估存储不可用时 fail-closed 为 `QUALITY_REGRESSION_SIGNAL_UNAVAILABLE/ACTIVE`，健康后使用固定状态键发送一次 `RESOLVED`。
- 不自动发布、回滚或修改优化工作项；复用现有状态仓库、通知去重和 Operations Center 告警展示。新增质量回归服务、状态转换和异常脱敏测试；API 全量 `948/948`，0 failure、0 error、43 个 capability skips。

## M18 质量证据引用保护增量（2026-08-25）

- 留存治理新增不可变质量证据引用索引，覆盖优化工作项、优化实验和发布门禁快照；引用的评测运行、质量快照、Benchmark 与兼容性矩阵不会因时间窗口被删除。
- 预览阶段冻结保护集合并计算 SHA-256 指纹，执行阶段重新读取并校验指纹；引用在预览后变化时返回 `RETENTION_PROTECTION_CONFLICT`，在任何删除前安全拒绝。
- 引用索引或其任一生命周期事实源不可用时 fail-closed，返回 `RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE`；API/管理台只展示保护数量和指纹，不暴露完整引用明细。
- `QualityEvidenceRepository` 与 `BenchmarkStore` 保持旧清理方法兼容，并增加保护集合重载；矩阵保护会级联保护其引用的单次评测，避免质量证据状态出现孤儿。
- 管理台预览已展示保护计数、兼容性矩阵清理数量和保护指纹，并区分保护冲突/不可用错误。API 全量 `943/943`，0 failure、0 error、43 个 capability skips；Web `159/159`，生产构建与生命周期 verifier 继续通过。

## 执行环境证据快照与可比性增量（2026-08-25）

- 新增 `ExecutionEnvironmentSnapshot`，以环境类型、ID、version、revision 和 status 绑定评测运行、`QualitySnapshot` 与 Runner 执行记录；不复制配置引用、凭据或业务正文。
- 评测/Runner 在任务提交或执行前解析一次 ACTIVE 目录并冻结快照；后续状态流转和质量快照沿用同一上下文，避免环境目录变更后历史证据失去可复现性。
- 版本效果对比纳入完整环境快照一致性判断；同一环境 ID 但 revision/version/status 不一致时返回 `EVALUATION_CONTEXT_MISMATCH`。旧 ID-only JSON 证据以 legacy snapshot 兼容恢复，不被误判为完整可复现上下文。
- Web 质量详情、Benchmark、Runner 历史和版本对比已展示完整执行环境证据（version/revision/status）；旧 ID-only 记录仍兼容按 ID 展示，不伪造版本快照。
- 本轮 API 全量 `932/932`，0 failure、0 error、43 个 capability skips；Web `159/159`，生产构建与统一生命周期 verifier 通过。当前仍未具备真实 Docker/PostgreSQL 目标环境，因此数据库集成、真实 Provider 联调和生产验收继续开放。

## A2 执行环境资产目录 PostgreSQL 持久化增量（2026-08-25）

- 执行环境目录已抽象 `ExecutionEnvironmentRepository`，默认 JSON 保持原子文件和现有 API 兼容；显式 `skill-center.execution-environment-backend=postgresql` 且全局 persistence 为 PostgreSQL 时选择 JDBC 实现，不双写、不自动回退。
- Flyway V11 新增 `skill_execution_environment`，以 `(kind, environment_id)` 唯一键保存 Agent Runtime、MCP Server、LLM Provider 的版本、能力、状态、Provider/secret 引用和 revision；状态更新要求 revision 连续递增，陈旧写入返回 `EXECUTION_ENVIRONMENT_REVISION_CONFLICT`。
- 执行环境 PostgreSQL 资产已进入 Persistence artifact catalog；数据库不可用或 migration/readiness 不通过时沿用平台 fail-closed，不把 JSON 快照伪装成数据库备份。真实 PostgreSQL 供应、容量/SLO、备份/PITR 和生产迁移验收仍开放。
- 前一轮 API 全量 `929/929`，0 failure、0 error、43 个 capability skips；执行环境、持久化 catalog、selector、V11 migration 和 API 回归均通过。本轮快照增量的最新结果见文档顶部。

## A2 治理聚合与 Skill 版本 PostgreSQL 持久化增量（2026-08-25）

- `GovernanceStore` 已改为依赖 `GovernanceStateRepository` 持久化端口；默认 JSON 实现保持原子文件格式和既有测试构造，显式 `skill-center.governance-backend=postgresql` 且全局 persistence 为 PostgreSQL 时由 JDBC/Flyway 聚合表成为治理事实源。
- Flyway V7 新增 `skill_governance_state` JSONB 单例聚合，以 revision、事务锁和条件更新保护 Skill 版本、审核、审计、授权、通知及治理配置的跨重启写入；Stale revision 收敛为 `GOVERNANCE_STATE_CONFLICT`，数据库故障保持 `GOVERNANCE_PERSISTENCE_FAILED`。
- Flyway V8 新增 `skill_governance_version` 关系表，以 `(skill_id, version)` 唯一键保存 SkillVersion 全字段和安全扫描元数据；治理聚合与版本关系行在同一事务内更新，读取时关系行优先恢复，旧聚合会回填关系表。
- Flyway V9 新增 `skill_governance_review` 关系表，以状态/风险/提交时间索引审核队列；审核聚合与关系行在同一事务内更新，读取时关系行优先恢复，旧聚合会回填关系表。
- Flyway V10 新增团队、角色绑定、分类、标签、合集和平台策略关系表；配置关系事实在同一事务内写入，读取时关系行优先恢复，旧聚合会回填配置表。
- PostgreSQL 持久化新增受边界约束的 Hikari `minimum-idle`/`maximum-pool-size` 和 `readiness-slo` 配置；数据库探测超出 SLO 返回 `PERSISTENCE_DATABASE_SLO_BREACH`，非法池边界返回 `PERSISTENCE_POOL_CONFIGURATION_INVALID`，均 fail-closed。
- Persistence artifact catalog 已把 `governance-state` 的显式 PostgreSQL 后端标记为数据库资产；A1 文件快照对该数据库资产继续保持 unsupported/blocked，不伪装为 JSON 备份。该增量完成治理聚合核心事实关系化；本地 readiness 已接入，但真实数据库供应、连接池容量/SLO 压测与验收、备份/PITR 和生产迁移验收仍开放。
- 本轮最终回归：API `922/922`，0 failure、0 error、43 个 capability skips；Web `158/158`，生产构建、生命周期 verifier 和 `git diff --check` 通过。Docker PostgreSQL 集成仍按 capability skip 处理，不替代真实数据库验收。

## A2 生产交付证据 PostgreSQL 持久化增量（2026-08-25）

- 新增 `ProductionEvidenceRepository` 持久化端口：默认 JSON 保持原子文件语义，显式 `skill-center.production-evidence-backend=postgresql` 且全局 persistence 为 PostgreSQL 时选择 JDBC 实现；缺失配置不会静默回退 JSON。
- Flyway V6 新增 `skill_production_evidence`，以受控列保存状态、责任人、有效期、脱敏引用、摘要和 revision；事务内 `SELECT FOR UPDATE` 与 revision 条件更新保护并发台账修改，冲突和数据库故障收敛为稳定业务/持久化异常。
- Persistence artifact catalog 会将显式 PostgreSQL 证据台账标记为数据库后端，不把它纳入 JSON snapshot 语义；真实数据库供应、连接池/SLO、备份/PITR、迁移审批与 Docker 集成验证仍待目标环境验收。
- 前述基线回归为 API `901/901`；叠加本轮 SkillVersion 关系化持久化增量后，最新全量结果见本节顶部：API `912/912`，0 failure、0 error、39 个 capability skips。

## M13 生产交付证据运营告警增量（2026-08-25）

- Operations Alert 新增 `PRODUCTION_HANDOFF_EVIDENCE`：生产证据台账为 `NOT_READY`、证据缺失/非接受态、过期或存储不可用时产生稳定告警，全部有效证据恢复为 `READY` 后发出 `RESOLVED`。
- 告警只读取 `ProductionEvidenceService` 的安全聚合结果，返回阻断数量、稳定 reason code 和状态，不读取或回显报告正文、凭据、URL；不改变生产发布门禁、证据台账或人工审批边界。
- Operations Center 已展示该告警，并与现有生命周期投影、优化工作项滞留告警共享状态迁移、通知去重和安全降级语义。

## M12 持续优化闭环运营化增量（2026-08-25）

- 优化工作项新增只读滞留健康服务与管理员 API：以 `updatedAt` 和可配置 7 天默认阈值识别活动工作项滞留，终态排除，返回活跃/滞留数量、状态/Owner/严重度分布和有界脱敏摘要。
- 运营告警新增 `OPTIMIZATION_WORK_ITEM_STALENESS`，复用现有状态迁移、去重通知和 Redis/JSON 状态端口；健康仓储异常 fail-closed 为稳定原因码，不自动推进或修改工作项。
- Operations Center 已展示优化闭环健康与质量管理入口；本轮 API 全量 `893/893`、Web `157/157`，0 failure、0 error，33 个 Docker capability skips，Web 构建通过。

## A2 持续优化工作项 PostgreSQL 持久化增量（2026-08-25）

- 已新增 `OptimizationWorkItemRepository` 持久化端口：默认 JSON 实现保持原子文件语义，显式 `skill-center.optimization-work-item-backend=postgresql` 时使用 JDBC/PostgreSQL JSONB 实现；PostgreSQL 选择必须同时显式启用全局 `skill-center.persistence.backend=postgresql`，缺失配置不会静默回退 JSON。
- Flyway V5 新增 `skill_optimization_work_items`，以受控列支持 Skill/版本/建议/状态查询，以 JSONB 保留完整工作项；非终态 `(skill_id, source_version, suggestion_id)` 部分唯一索引保护多实例并发创建，重复键和数据库故障分别映射为稳定业务冲突与持久化 503。
- 平台 readiness 已新增 `OPTIMIZATION_WORK_ITEM_STORE` 组件：JSON 后端明确报告 `DEGRADED/OPTIMIZATION_WORK_ITEM_JSON_ONLY`；PostgreSQL 后端必须同时通过全局持久化 readiness 和 Flyway V5 schema 检查，缺失时返回稳定阻塞原因，不静默回退。
- 优化工作项状态机、证据绑定、审计和人工发布/回滚边界保持不变；真实 PostgreSQL 供应、连接池/容量/SLO、备份/PITR、多实例故障注入和生产迁移审批仍待目标环境验收。
- 本轮变更后 API 全量回归 `887/887`，0 failure、0 error、33 个 Docker capability skips；该本地证据不替代真实 PostgreSQL 生产验收。

## A1 持久化控制面 Task 5（2026-08-25）

- 已交付 Spring lifecycle startup gate：共享 `PersistenceControlService` 状态计算检查 JSON backend、配置根、资产完整性和 migration journal；关键缺失/损坏、迁移不受支持、非法路径/backend、符号链接逃逸和控制面异常为 `FAIL_CLOSED`，仅非关键 `OPTIONAL_MISSING` 为 `DEGRADED`，干净资产为 `READY`。
- 已验证启动检查和普通 GET 只读：不创建业务目录、不修复文件、不写 migration journal、不提供在线 restore；保留三参数构造 seam 供非 Spring focused tests 使用，默认本地 Spring 行为继续允许可选资产缺失并报告降级。
- 已新增 `scripts/verify-persistence-control.ps1`，串行运行 persistence focused tests、API `-DforkCount=0` 全量测试、依赖可用时的 Web test/build 和 `git diff --check`，任何失败均非零退出。
- 已新增 [A1-persistence-control-plane-status.md](A1-persistence-control-plane-status.md)，记录配置、状态/错误码、retention、metadata-only API、offline restore-preflight/no online restore、symlink/path safety 和 A2 PostgreSQL handoff。
- 快照 retention 同时作用于 `snapshots.json` metadata 与完整 snapshot directory/data copies；淘汰前验证 `COMPLETE` snapshot directory 后删除该目录。API 仍只返回 metadata，恢复仍是 offline restore-preflight/no online restore，不替代外部备份保留与演练责任。
- A1 仍是本地 JSON 控制面：PostgreSQL/Flyway、生产数据库/对象存储、SSO/JWT、真实 Provider、生产密钥管理、Redis HA、监控告警、备份恢复演练、性能/UAT 和上线审批均未完成，不能以本地 Mock/JSON 回归替代外部验收。

## A2 Phase 1 PostgreSQL/Flyway 质量证据适配（2026-08-25）

- 已交付保持 JSON 默认的 `QualityEvidenceRepository` PostgreSQL/Flyway 适配器：必须同时显式启用全局 persistence 和 quality-evidence PostgreSQL selector；缺失/非法配置、连接或迁移异常 fail-closed，不自动回退或双写。
- Flyway V1 管理 `skill_quality_evidence_state` JSONB 单例聚合；Repository 写入为事务性 revision 写入。Docker 不可用时，真实 PostgreSQL Testcontainers 测试只能作为命名 capability skip 报告，不能掩盖编译、单元或完整 API 失败。
- A1 文件快照和 restore-preflight 不支持 PostgreSQL 质量证据；它不会伪装成 JSON 文件副本或数据库备份。验证入口为 `scripts/verify-postgres-quality-evidence.ps1`，它严格要求 Web 依赖、Surefire 集成报告和零 failure/error。
- 仍待生产交付：数据库供应、连接池/容量/SLO 验证、数据库一致性快照/PITR、治理与发布域迁移、Skill 元数据关系投影的生产写入迁移、SSO/JWT、对象存储以及备份/恢复演练。当地测试通过或 capability skip 均不构成生产数据库准备就绪证据。
- 已新增 ReleaseRecord 关系型持久化端口：JSON 默认实现与显式 PostgreSQL/Flyway JDBC 实现共享审批、晋级、失败和回滚语义；数据库唯一约束保护 `release_id`、幂等键和非终态 Skill/版本/环境业务键，发布门禁快照使用 JSONB；不双写、不自动回退，JSON 快照对该后端保持 unsupported。真实数据库供应、连接池/SLO、备份/PITR 和生产迁移审批仍未完成。
- ReleaseRecord 增量已完成本地验证：API 全量 `721/721`，失败/错误 `0/0`，跳过 `33`（其中 5 个为 Docker 不可用导致的命名 PostgreSQL capability skip）；Web `148/148`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。该证据不替代真实 PostgreSQL 供应、容量/SLO、备份/PITR、生产迁移审批与 UAT。
- 已将 M11 Provider 契约占位推进为可显式启用的安全 HTTP adapter：OpenClaw/DeepEval/Langfuse 共用 JDK HTTP transport、Secret resolver、严格 contract-v1 JSON、超时/429/5xx/无效响应稳定错误和脱敏 allowlist；默认 Mock/contract 保持不变，缺少 Secret 为 `NOT_CONFIGURED`，不会回退 Mock。真实 Provider endpoint、Secret Manager、网络出口、数据保留评审和 UAT 仍未完成。
- 运行运营 Trace 查询已接入外部观测适配边界：显式 Langfuse HTTP 模式选择 `LangfuseTraceProviderAdapter`，按窗口/Skill/版本/状态/数据源及 Runtime/MCP/LLM 环境发送安全查询并只接受脱敏 Trace 元数据；默认继续使用 `MockTraceProvider`，外部响应包含未知或敏感字段时 fail-closed。真实 Trace endpoint、Secret、网络和 UAT 仍未完成。
- Trace 适配器最终回归已通过：API `735/735`（0 failure、0 error、33 capability skips），Web `148/148`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；Docker 不可用导致的 PostgreSQL 能力跳过已单独报告，不构成真实 Provider/数据库生产验收。
- 身份边界已增加可配置的 JWT 模式：`ActorResolver` 在 JWT 模式只接受 RS256 Bearer token，校验 `sub`/`exp`/可选 `iss`/`aud`/`nbf` 和白名单角色，禁止 `X-User-Id`/`X-User-Role` fallback；local 模式保持开发兼容。企业 SSO/JWKS、密钥轮换、组织目录 claim 映射和 UAT 仍待目标环境接入。
- 身份边界已增加平台侧 JWKS resolver：JWT 可在静态 PEM 与服务端配置的 JWKS URL 之间显式二选一；只接受 HTTPS（loopback 测试允许 HTTP）、RS256/RSA/`kid`，并以有界超时、响应大小、TTL 缓存和未知 key 单次刷新保护密钥轮换。过期缓存刷新失败、非法 key、重复 key、未知/缺失 `kid` 均 fail-closed，不回显 Token、JWKS、密钥或上游异常。真实 SSO endpoint、网络白名单、Secret Manager、组织 claim、撤销和 UAT 仍待目标环境验收。
- JWT 组织声明平台适配已补齐：服务端配置的 `team-claim` 支持单个字符串或有界字符串数组，缺失可选 claim 形成空且权威集合；TEAM 可见性必须同时命中本地 active TeamDefinition 和签名团队 ID，不回退本地成员表，管理/发布仍保留本地 maintainer binding。真实组织目录字段约定、撤销传播和 UAT 仍待目标环境验收。
- JWKS 身份边界增量已通过最终本地回归：API `785/785`，0 failure、0 error、33 Docker capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。新增测试覆盖静态/JWKS 二选一、合法/轮换 `kid`、TTL、非法/超大响应、过期 fail-closed、重复未知 `kid` 刷新退避和 Header 防伪；真实企业 SSO/JWKS、网络、Secret Manager、组织 claim、撤销策略和 UAT 仍开放。
- JWT 组织/团队声明增量已通过最终本地回归：API `793/793`，0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。`team-claim` 已实现 bounded 单值/数组解析、空且权威集合、active TeamDefinition 命中和本地 maintainer binding 保留；真实 SSO/JWKS endpoint、组织目录字段/撤销传播、网络白名单、Secret Manager、UAT 仍开放。
- 组织目录快照同步平台侧已落地：`organization-directory.v1` bounded schema、确定性排序与 SHA-256 内容指纹、原子 JSON 快照恢复、revision 冲突/过期 fail-closed、受控 HTTP Bearer 拉取、管理员显式同步/状态接口、脱敏审计、HTTP 模式下目录成员与 JWT team claim 双重授权，以及平台 readiness 组件均已接入；默认 local 模式保持本地开发兼容。真实企业目录 endpoint、Secret Manager、撤销传播、跨团队审核映射和 UAT 仍待目标环境验收。
- 组织目录快照专项最终回归已通过：API `820/820`，0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。Docker skip 仅表示本地未运行 PostgreSQL 能力套件，不替代真实企业目录、Secret Manager、撤销 SLA、跨团队审核和生产 UAT。
- Skill 制品一致性已收紧：已绑定 `artifactPath` 的版本在 Manifest 和下载前统一校验普通 ZIP、非符号链接、可读归档和记录 SHA-256；缺失/篡改时 fail-closed，不生成替代 ZIP、不消耗下载授权。无绑定制品的历史种子仍保留本地演示生成路径；真实对象存储、签名 URL、复制/保留、备份和容量/SLO 仍待目标环境接入。
- Skill 制品存储已增加统一 `ArtifactStorage` 端口：本地上传按 SHA-256 内容寻址、原子发布并对相同摘要幂等复用，新制品只保存 `local://` opaque 引用；目录、Manifest 和下载均通过存储边界读取，旧绝对/相对路径继续兼容。真实 S3/OBS、签名 URL、复制/保留、备份和容量/SLO 仍待目标环境适配与验收。
- 已增加 S3-compatible HTTP 制品适配器：显式 `object-storage/http` 模式使用 SigV4 风格签名、内容寻址 PUT、HEAD/GET 完整性校验和 secret 引用；默认 `contract` 模式及 Local fallback 边界保持不变。真实 S3/OBS 网络、复制/保留、签名 URL、备份和容量/SLO 仍待目标环境联调与验收。
- 制品存储抽象最终回归已通过：API `754/754`，0 failure、0 error、33 capability skips；Web `148/148`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。跳过项仅为 Docker 不可用的命名 PostgreSQL capability suites，不替代真实对象存储、数据库或生产上线验收。
- 制品后端选择已显式化：`skill-center.artifact-storage-backend` 默认 `local`；选择 `object-storage` 时只装配 contract-only fail-closed 适配器，绝不静默回退本地文件，并将 `ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED` 纳入平台生产 readiness。Local 后端报告 `ARTIFACT_STORAGE_LOCAL_ONLY` 降级；真实 S3/OBS 适配器、签名 URL、复制/保留、备份和容量/SLO 仍待目标环境实现与验收。
- 本轮新增后端选择/readiness/error-contract 测试已通过；API 全量回归最终 `759/759`、0 failure、0 error、33 capability skips，Web `148/148`，生命周期 verifier `6/6`，`git diff --check` 通过。中间出现的 1 个 Windows JUnit 临时目录清理竞态已通过隔离重跑复核，未发现断言失败。
- S3-compatible 制品适配器增量最终回归已通过：API `767/767`、0 failure、0 error、33 capability skips；Web `148/148`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。中间出现的 Windows JUnit 临时目录清理竞态已按规则隔离重跑通过；Docker capability skip 不替代真实对象存储或生产 PostgreSQL 验收。
- 已补齐制品存储控制面连通性探测：管理员可调用 `POST /api/v1/admin/platform/artifact-storage/probe`，探测只访问对象存储 bucket 控制面，不读取制品内容；结果仅返回 backend/status/reasonCode/httpStatus/latency/checkedAt，并写入脱敏审计。最近一次且未超过 `probe-ttl-seconds`（默认 300 秒）的 `REACHABLE` 探测才会将 HTTP 对象存储 readiness 提升为 `READY`；过期、未来时间戳、未配置、不可达或 HTTP 错误保持 fail-closed。Local/contract-only 后端分别返回 `SKIPPED`，不会伪造云存储连通性。Web API client 已同步暴露该契约。
- 制品存储探测增量最终回归已通过：API `772/772`、0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。真实 S3/OBS bucket 连通性、复制/保留、签名 URL、备份和容量/SLO 仍需目标环境验收。
- 制品存储探测新鲜度增量最终回归已通过：API `773/773`、0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。默认探测证据 TTL 为 300 秒，过期证据不会继续维持远端 readiness。
- 制品存储探测证据已支持安全恢复：服务重启后从治理域中已有的脱敏探测审计恢复最近有效记录，再次经过 backend、稳定 `adapterId`、TTL、状态和数值字段校验；S3 HTTP、contract-only、local 证据不可交叉复用。不读取 endpoint、凭据、对象键或正文，非法/不完整/旧格式审计不会提升 readiness。该恢复仍是本地 GovernanceStore 语义，不能替代多实例共享状态或生产观测系统。
- 制品存储探测恢复增量最终回归已通过：API `775/775`、0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。真实多实例共享存储、S3/OBS 联通、复制/保留、备份和容量/SLO 仍需目标环境验收。
- [x] 新增可显式开启的 Artifact Storage 定时探测器，默认关闭；integration profile 每 60 秒刷新一次对象存储连通性证据，定时续期不写入用户审计。
- [x] 本地 8081 重启后已验证对象存储探测自动刷新，平台 readiness 中 `ARTIFACT_STORAGE` 从 `PROBE_EXPIRED` 收敛为 `READY`；生产多实例协调、告警和 SLO 仍需目标环境验收。

## A3-1 Skill 生命周期关系投影（2026-08-25）

- [x] 完成本地生命周期关系投影 MVP：从 JSON Skill/版本/发布/范围/关系事实源生成稳定 hash 的离线 snapshot，支持 preflight、显式管理员 import、PostgreSQL V3 事务投影、幂等 revision 和受界限的 skills/impact 查询。
- [x] 保持 JSON 默认事实源；PostgreSQL lifecycle projection 必须显式同时 opt-in 全局 persistence 与 lifecycle selector，并通过 A2 readiness/schema gate；不运行时双写、不在线切换、不自动 JSON fallback。
- [x] 完成 admin control-plane API、授权和敏感响应边界：actor 只来自服务端解析，requestId 只来自请求过滤器；不得返回 Prompt、Trace、Token、凭据、路径或原始异常。
- [x] 增加投影一致性与新鲜度观测：管理员只读 reconciliation API 返回 JSON live-source、hash 漂移、五类计数差异、PostgreSQL imported_at 年龄和稳定 reason code；不自动修复、不双写、不改变事实源。
- [x] 将 reconciliation 接入管理员运行运营中心：以只读健康卡片展示 backend、状态、revision、投影年龄、五类计数差异和稳定 reason code；缺少接口时兼容旧前端 mock，不改变导入或修复语义。
- [x] 将生命周期投影健康状态接入现有 Operations Alert：`DRIFTED`、`STALE`、`NOT_IMPORTED`、`NOT_READY` 生成稳定 reason code 告警，恢复为 `HEALTHY`/`LIVE_SOURCE` 时发出 RESOLVED；复用既有状态迁移与通知 sink，不执行自动修复。
- [x] 完成可重复 verifier 和 Pester contract tests：覆盖 lifecycle/API/authorization/A2 focused Maven、Web 依赖/test/build、配置/迁移边界、fresh Surefire、命名 Docker capability skip 和 `git diff --check`。
- [ ] 生产交付仍开放：真实 PostgreSQL 供应、连接池/容量/SLO、数据库备份/PITR 与恢复演练、治理/发布域全量写入迁移、SSO/JWT/组织目录、对象存储、UAT、上线审批和回滚演练。

## Skill 包安全扫描增量（2026-08-25）

- [x] 在现有上传校验链路增加本地确定性安全门：扫描 ZIP 中的禁止可执行载荷和文本凭据模式，返回 `PASSED`/`BLOCKED`/`NOT_SCANNED`、稳定 finding code 和脱敏路径；扫描失败 fail-closed，不保存或回显凭据值。
- [x] 上传成功响应返回安全扫描状态与 findings 摘要，Web 上传提示展示安全扫描结论；既有包结构、Schema、风险等级、版本和审核流程保持兼容。
- [x] 安全扫描证据在审核提交时写入 `SkillVersion` 与 `ReviewTask`，并在普通审核/安全复核/通过/驳回状态流转及 GovernanceStore 重启恢复后保持；历史记录兼容归一为 `NOT_SCANNED`。
- [x] 管理员审核 API/Web 仅展示安全状态、扫描器 provenance 和 findings 数量，不持久化或回显 finding 原因、路径细节或命中内容。
- [x] 增加可替换的外部扫描 contract、required fail-closed gate、contract-only 默认适配器和 admin readiness；外部 scanner provenance 会沿上传校验、审核证据和生命周期投影继续传递，contract-only 不联网且不会伪报 READY。
- [x] 将外部扫描准入细化为四类覆盖契约：`MALWARE`、`SENSITIVE_INFORMATION`、`DEPENDENCY_VULNERABILITY`、`LICENSE`；required 模式和 readiness 对缺失覆盖能力 fail-closed/显式降级。
- [ ] 外部恶意文件引擎、敏感信息服务、依赖漏洞数据库、许可证规则、真实凭据接入和生产扫描 SLA 仍待目标环境接入；本地确定性扫描与 contract-only 门禁不等同于生产安全扫描验收。
- 外部包安全扫描 HTTP 适配器已完成平台侧实现：显式 `required` 模式使用有界 ZIP 请求、SHA-256 请求头、Bearer secret 引用、严格 `package-security-scan.v1` 响应 allowlist、四类能力校验和安全错误收敛；默认仍为 `disabled`/`CONTRACT_ONLY`。真实扫描服务、Secret Manager、规则样本、故障注入、保留策略和生产 SLA 仍待目标环境验收。
- 外部包安全扫描适配增量最终回归已通过：API `831/831`，0 failure、0 error、33 capability skips；Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。Docker skip 仅影响命名 PostgreSQL capability suites，不替代真实扫描服务、Secret Manager、规则库和生产 SLA 验收。
- Provider 契约合规矩阵增量已完成平台侧回归：本地覆盖 Runner/Evaluation/Observability 的隐私边界、未知响应拒绝、有界响应、稳定错误码、重试/幂等/故障注入与关联 ID，并通过 900 次本地适配调用的 2 秒序列化/解析基线；真实 Provider 故障注入、网络容量和生产 SLA 仍需目标环境验收。
- Provider 契约与 Quality Center 兼容性增量最终回归已通过：API `837/837`，0 failure、0 error、33 capability skips；Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。Quality Center 在旧 API 缺少评测状态查询方法时安全降级，不再产生异步运行时异常。
- Provider HTTP 传输安全增量已完成平台侧实现：JDK transport 使用 InputStream 有界读取，固定长度和 chunked 响应超过 256000 字节均返回 `UPSTREAM_RESPONSE_TOO_LARGE`，避免先构造无界响应字符串；真实 Provider 网络容量、故障注入和生产 SLA 仍待目标环境验收。
- Provider HTTP 传输安全增量最终回归已通过：API `839/839`，0 failure、0 error、33 capability skips；Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。
- ReleaseTarget 生态集成平台侧增量已完成：新增显式 `mock/http` 目标选择和 metadata-only `release-target.v1` HTTP adapter，复用有界 JDK transport/Secret resolver，严格拒绝未知响应字段并将超时、429、5xx、超大响应和未配置收敛为稳定失败码；默认 Mock 不变且不会静默回退。真实 Runtime/MCP/LLM/CD endpoint、Secret Manager、认证、故障注入、容量和生产 UAT 仍待目标环境验收。
- ReleaseTarget 生态集成最终回归已通过：API `846/846`，0 failure、0 error、33 capability skips；Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过。
- ReleaseTarget 连通性证据平台侧已补齐：管理员可显式触发带 Bearer 的 metadata-only 状态探测；探测审计按安全配置指纹恢复并受默认 300 秒 TTL 约束，`REACHABLE` 才进入平台 `RELEASE_TARGET` readiness，其余状态 fail-closed 阻断 PRODUCTION 发布；真实目标连通、Secret Manager、网络和故障注入仍待目标环境验收。
- 运行运营中心已提供“探测发布目标”管理员操作入口，并只展示状态、稳定原因码、HTTP 状态和耗时；前端不会显示 endpoint、credential-ref 或上游正文，缺少新 API 时保持兼容降级。
- ReleaseTarget 探测历史已接入治理审计查询：管理员可按有界 limit 查询当前配置指纹匹配的最近记录，运营中心展示失败/超时数量和脱敏历史；历史查询不触发网络、不改变 readiness，真实多实例共享审计存储仍待生产环境验收。
- 本轮 ReleaseTarget 历史查询增量最终回归已通过：API `860/860`、Web `155/155`、生产构建和生命周期 verifier `6/6` 均通过；Docker capability skips 仍为 33 个 API 测试及统一校验中的 PostgreSQL 能力套件，不替代真实环境验收。
- [x] 新增可显式开启的 Release Target 定时探测器，默认关闭；Integration profile 每 60 秒续期发布目标连通性证据，定时探测不新增治理审计，Mock/http 仍保持原有 fail-closed 语义。
- [x] 生命周期关系投影/哈希已纳入安全状态、scanner provenance 和稳定 finding 摘要；新增 V3 schema 与 finding 子表，旧记录兼容为 `NOT_SCANNED`，显式 import 后以新 hash 完成对账。
- [x] Quality Center 已接入管理员安全 readiness 卡片，展示外部扫描模式、状态、scanner provenance、四类已覆盖/缺失能力；readiness 接口不可用时安全降级为空态，不阻断既有质量工作台。

## Skill 生命周期治理增量（2026-08-25）

- 已落地 IAM-007/IAM-008 Skill 范围治理：独立 `SkillScopeStore` 原子持久化 `PUBLIC/TEAM/RESTRICTED`、维护者、owner team、revision 与审计身份；历史无范围 Skill 继续按 PUBLIC 兼容回退。
- 已落地统一 `SkillAuthorizationService`，并接入目录/内容、首版提交与审核、生命周期/发布、关系、安装授权、Manifest、制品下载、个人中心、统计和合集路径；隐藏资源不泄露 owner/team/下游关系 ID，分发授权在范围检查前不发放 Token。
- 已落地管理员详情范围面板：GET 只读加载、显式保存才 PUT、revision 冲突保留草稿、历史 PUBLIC/revision 0 安全空态；请求体不携带审计身份或业务正文。
- 回归证据：Web 全量 134/134；API Surefire 汇总 510/510，0 failure、0 error、0 skipped；Web 生产构建通过。Windows 全量执行使用 `-DforkCount=0` 消除 JUnit 临时目录关闭竞态，单独失败测试均已复核通过。

## 本轮已完成

- 公开角色契约统一为 `developer`（普通开发者）和 `admin`（平台管理员）；API 保留 `viewer`、`maintainer`、`reviewer` 作为存量数据/测试的兼容迁移别名，前端不再暴露这些角色。
- Skill 市场由 API 完成关键字、分类、状态、风险、排序和分页，返回 `items/page/pageSize/total`；前端按服务端页渲染，不再对截断数据二次分页。
- 技能合集由 API 完成关键字、排序和分页，前端与市场保持同一套搜索、排序、卡片/列表、分页交互；后端无合集时保留两个演示合集。
- 通知中心改为治理快照持久化，支持按用户读取、单条已读、全部已读、所有权隔离；上传提交审核、审核通过和驳回会产生站内通知。
- 前端通知图标已接入真实 API，包含空状态、失败安全回退和已读操作反馈。
- Skill 生命周期增量已完成本地 MVP：Mock Runner、质量评测/快照/门禁、Benchmark 与版本对比、运行运营聚合、Trace 脱敏失败定位、优化建议处置、Provider 契约和执行环境上下文。
- 质量与运行证据已具备 JSON 原子持久化、启动/写入语义校验、主键/外键完整性检查和历史无套件目录兼容策略。
- 新增 `scripts/verify-lifecycle.ps1` 统一执行 Web/API 测试、构建和运行态冒烟。
- 新增 `docs/project/M11-external-integration-runbook.md`，供目标环境执行 Provider/Redis 联调、门禁和回滚。
- 运行摘要事件新增 5 分钟未来时钟偏差校验：保留离线补报能力，拒绝明显超前事件，降低新 UUID 重放污染运营指标的风险。
- 运行摘要单条与批量接入纳入统一客户端限流（`RUNTIME_SUMMARY_INGEST`），默认每分钟 120 次，返回标准限流响应头和 429 错误。
- 运行摘要 Redis 后端已加固：事件 Hash 与时间索引 ZSet 通过单个 Lua 脚本原子写入，避免多实例写入中间崩溃导致索引缺失；Redis PING 健康状态以 `RUNTIME_SUMMARY_REDIS_READY`/`RUNTIME_SUMMARY_REDIS_UNAVAILABLE` 暴露，并接入平台生产 Readiness，Redis 故障不回退 JSON。默认 JSON 继续标记为 `RUNTIME_SUMMARY_JSON_ONLY` 降级。
- 本轮 Redis 运行摘要与 Operations Alert 状态增量最终回归已通过：API `873/873`、Web `155/155`、生产构建通过；统一生命周期 verifier 已通过，真实 Redis HA、容量、故障转移、备份恢复和多实例演练仍属于目标环境验收。
- Operations Alert 状态已从 `OperationsAlertService` 进程内 Map 迁移到 `OperationsAlertStateRepository`：默认 memory 明确降级；显式 Redis 通过单 Lua 脚本原子完成状态迁移，重启恢复和多实例通知去重；Redis 故障不回退 memory，并通过 `OPERATIONS_ALERT_STATE` readiness 暴露。
- 质量详情与版本对比按 `dataSource` 过滤质量快照，避免将 Mock 评测结果与 production 运行指标混合展示。
- Benchmark 历史列表与详情查询同样按 `dataSource` 过滤，并由前端管理员 API 透传数据源与执行环境筛选。
- Skill 质量详情新增按数据源/执行环境筛选的评测快照历史，返回最近评测趋势数据，前端展示最近 8 条质量分记录。
- 质量管理中心新增管理员受控 Runner 执行面板，可对已发布版本注入成功/失败/超时/取消场景，并展示最近执行历史及 Runtime/MCP/LLM 环境；执行结果复用运行摘要进入运营指标。
- 保留策略纳入 Runner 执行证据，预览、删除、估算空间和幂等执行结果均返回 `runnerExecution` 清理数量，避免受控执行记录无限增长。
- Runner 执行证据存储补齐恢复校验和主键冲突保护：相同内容幂等复用，不同内容返回稳定冲突错误，拒绝重复或非法状态记录静默污染历史。
- Runner 执行历史查询支持 `dataSource`、Runtime、MCP Server 和 LLM Provider 过滤，质量中心透传当前执行环境，保证执行证据与运营/质量口径一致。
- 质量中心 Benchmark 历史列表同步透传当前 Runtime/MCP/LLM 环境，避免不同执行环境的效果证据混合展示。
- 质量中心评测历史列表同步按 dataSource、Runtime/MCP/LLM 环境过滤，并在切换 Skill 或执行环境后自动刷新，避免评测任务与质量快照口径不一致。
- 评测历史列表显示每条任务的 Runtime、MCP Server 和 LLM Provider 上下文，管理员可直接追溯当前筛选下的证据来源。
- 质量中心 Mock Runner 查询显式限定 `dataSource=mock`（评测、Benchmark、Runner 历史、优化建议及处置），为后续 production Provider 接入保留清晰数据边界。
- Skill 详情质量指标和版本对比增加数据来源筛选（全部来源、Mock 受控评测、生产调用），并将选择透传到快照、运行聚合、Benchmark、建议和版本对比查询。
- 版本对比结果明确展示数据来源与 Runtime/MCP/LLM 证据口径，便于管理员判断“同口径可比”结论的适用范围。
- Skill 详情质量指标新增“查看运行运营”入口，透传当前 Skill、版本、数据来源和 Runtime/MCP/LLM 筛选，支持从质量证据直接进入运行排障。
- 质量评测历史每条快照记录补充 Runtime、MCP Server 和 LLM Provider 上下文，保证跨环境历史趋势可追溯。
- 质量中心切换目标 Skill 版本时会重新加载版本范围内的优化建议，避免沿用上一版本的旧建议。
- 优化建议已可转化为持久化的优化工作项：保存建议快照、负责人、假设、候选版本和执行环境，并通过 `OPEN -> PLANNED -> IN_PROGRESS -> READY_FOR_EVALUATION -> COMPLETED` 状态机推进；支持放弃/重新打开、重复活动工作项冲突和审计记录。
- 优化工作项支持绑定 `EVALUATION_RUN`、`QUALITY_SNAPSHOT` 或 `BENCHMARK` 证据，校验 Skill/候选版本、数据源及 Runtime/MCP/LLM 上下文，质量中心已接入创建、状态推进和证据绑定入口。
- 质量中心工作项面板已补齐完成、放弃、重新打开和证据台账展示，优化结果说明与受控证据 ID可持续回溯，形成从建议到验证结论的可操作闭环。
- Provider 生态接入补充管理员连接探测：`POST /api/v1/admin/quality/provider-readiness/probe` 按 Provider 或全量返回可达性、HTTP 错误、超时和未配置状态；探测传输可替换、无业务正文/凭据回显，并保持 `CONTRACT_ONLY` 不变。
- 新增统一执行环境资产目录：管理员可登记、查询和切换 Agent Runtime、MCP Server、LLM Provider 的版本、能力和 ACTIVE/DEGRADED/DISABLED 状态；目录使用 JSON 原子持久化并写入脱敏审计。
- 新评测和 Runner 任务在携带 Runtime/MCP/LLM ID 时必须引用 ACTIVE 目录资产；历史空环境查询继续兼容，质量中心优先使用目录选择器，目录 API 不可用时回退到受控 ID 输入。
- 兼容性矩阵质量证据已接入：管理员可对 Runtime/MCP/LLM 资产生成最多 100 个确定性组合，复用受控子评测并持久化环境版本快照、聚合分数、稳定失败码和发布门禁关系；旧单次评测与无矩阵发布保持兼容。
- 兼容性矩阵支持服务重启恢复、取消、管理员查询和质量中心工作台；显式 `releaseGateRequired` 的未完成矩阵以 `COMPATIBILITY_MATRIX_INCOMPLETE` 阻断发布，完成但未通过以 `COMPATIBILITY_MATRIX_BLOCKED` 阻断。
- 兼容性矩阵用例新增脱敏的环境能力与 Adapter Provider 快照，便于回溯“当时实际使用的执行契约”；取消矩阵会级联请求取消仍在运行的子评测，并防止异步编排覆盖已取消状态。
- 质量中心矩阵工作台支持 Runtime/MCP/LLM 多选、前端 100 组合预检、运行中取消和执行环境目录为空时的明确不可用提示，避免把无目录或超限请求提交到后端。
- 质量证据保留预览/执行已单独统计 `compatibilityMatrixEligibleCount` 与 `compatibilityMatrixDeleted`，删除矩阵时级联用例但不删除其引用的单次评测；`dataSource=mock` 仍不等同于真实外部 Provider 联调。
- 执行环境目录状态仅表示平台资产可用性，不等价于 OpenClaw/DeepEval/Langfuse 的真实 Provider readiness；真实生产适配仍保持 `CONTRACT_ONLY` 门禁。
- 新增 Provider 契约一致性校验：比较外部契约目录与当前注册适配器的版本、能力和状态，返回 `MATCHED`/`MISMATCH`/`NOT_REGISTERED` 及稳定差异；匹配不改变 readiness 或执行能力，质量中心和联调手册均已接入。
- 评测套件已升级为“逻辑套件 + 不可变版本”：同一 `suiteId` 可保留多版本，启用版本以原子指针切换；评测、兼容性矩阵、Benchmark 和版本对比均可显式锁定 `suiteVersion`，历史停用版本仍可追溯。
- 评测套件版本新增安全元数据边界、重复用例/重复版本校验、启动恢复复合键校验和稳定 API 错误码（`QUALITY_SUITE_VERSION_CONFLICT`、`QUALITY_SUITE_VERSION_NOT_FOUND`、`QUALITY_SUITE_NOT_ENABLED`）；旧请求未携带版本时继续解析当前启用版本。
- 质量中心支持套件版本选择、复制用例创建新版本，并在评测、矩阵、Benchmark 历史和详情版本对比中展示/透传套件版本上下文。
- 优化工作项新增成对的 `suiteId + suiteVersion` 评测上下文：Quality Center 创建时锁定当前套件版本，Evaluation Run、Quality Snapshot 和 Benchmark 证据按套件版本精确匹配；旧工作项缺失套件字段时保持兼容并在界面标识为未锁定。
- 新增 `OptimizationExperiment` 实验编排：READY_FOR_EVALUATION 工作项可幂等启动候选评测，支持 QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED、显式 reconcile/取消/Benchmark、JSON 恢复和 Quality Snapshot 证据回写；实验完成不会自动完成或发布工作项/Skill。
- 优化实验完成 Benchmark 后可生成同记录持久化的决策快照：按质量门禁与同口径 Benchmark 输出 PROMOTE_CANDIDATE、ITERATE、REJECT_CANDIDATE 或 NOT_COMPARABLE，保存证据上下文、原因和推荐动作；决策幂等、可恢复，且不自动发布、回滚或完成工作项。
- 优化实验决策已接入发布门禁：候选版本存在活动、失败/取消、缺少决策或非 PROMOTE_CANDIDATE 实验结论时以稳定原因阻断发布；PROMOTE_CANDIDATE 仍需继续通过兼容性矩阵、质量快照和人工 Review，且无优化实验的历史版本保持兼容。
- 发布审核被优化实验门禁阻断时，Web 客户端按稳定原因码展示实验状态、质量管理中心入口和下一步处理指引，同时保留原始错误码与详情供审计定位。
- 发布审核被质量门禁阻断时，服务端持久化 `PACKAGE_APPROVAL_BLOCKED` 审计事件，记录 Skill/版本、审核人、requestId 和稳定门禁原因；审核任务与版本继续保持 `pending_review`，并通过重启恢复测试验证。
- 审计与导出工作台新增管理员生命周期事件查询，可按动作和资源类型筛选发布阻断、质量证据与优化闭环事件；前端仅展示白名单元数据，过滤 Prompt、输入输出和凭据。
- 优化实验新增发布后运行观察：仅允许已发布且 `PROMOTE_CANDIDATE` 的候选版本采集指定窗口的脱敏 Runtime 聚合，观察记录追加持久化并写入审计；质量中心支持手动采集和查看，不自动回滚或改变发布状态。
- 优化实验新增发布后效果评估：按相同窗口、数据源和 Runtime/MCP/LLM 上下文重建源版本基线，输出 `HEALTHY`、`REGRESSION`、`INSUFFICIENT_TRAFFIC` 或 `INCONCLUSIVE` 结论及人工 `KEEP`/`CREATE_FOLLOW_UP`/`ROLLBACK_REVIEW` 处置；评估证据携带不可变套件上下文，可绑定优化工作项并纳入统一留存清理，服务端不自动回滚。
- 发布后评估在管理员显式选择 `CREATE_FOLLOW_UP` 时，会幂等创建带评估/观察证据上下文的后续优化工作项，并将工作项 ID 写入审计元数据；不会自动发布、回滚或完成工作项，Quality Center 会刷新并展示 `postReleaseAssessmentId` 等来源证据。
- 后续工作项幂等创建已补齐并发竞态兜底：存储层活动业务键/确定性 ID 冲突只返回既有记录，只有首次成功写入才产生创建审计；并发重试不会产生重复工作项。
- 新增受控发布控制面：ReleaseRecord 独立记录 STAGING/PRODUCTION 发布批次、质量门禁快照、审批、晋级、失败和回滚复核状态，不改变既有 SkillVersion 发布语义。
- 发布服务具备幂等键、版本/环境活动业务键冲突保护、原子 JSON 持久化和重启时执行中状态 fail-closed；生产环境拒绝 `NO_EVIDENCE`，Mock Target 只返回稳定状态/错误码/外部引用。
- 管理员 API 已提供发布请求、查询、审批、拒绝、晋级、回滚复核和回滚接口；Quality Center 只读加载发布批次，所有状态变更均由显式按钮/表单触发。
- 审核、发布和分发准入已统一：最终审核通过会以冻结的质量门禁快照幂等登记 STAGING `REQUESTED` 批次；安装清单与 ZIP 下载共用 `ReleaseAdmissionService`，在发放授权/消耗令牌前校验生产 `PROMOTED` 批次及 SHA-256。
- 发布准入支持 `LEGACY_COMPATIBLE`（默认，兼容没有生产批次的历史版本）和 `CONTROLLED`（所有分发版本必须匹配生产晋级）两种模式；管理员 Quality Center 可只读查看模式、稳定原因码和命中的发布批次。
- Skill 版本关系资产已落地：按具体版本声明 `DEPENDS_ON`、`COMPOSES`、`REPLACES` 关系，支持原子持久化、重复/自环/环依赖校验、撤销留痕和管理员影响分析；影响结果关联生命周期状态、生产晋级事实和活跃安装数，但本阶段不自动改变发布准入。

## 平台级生产交付准入 follow-up（2026-08-25）

- [x] 新增管理员只读 `GET /api/v1/admin/platform/readiness`，聚合持久化控制面、Provider 图、外部包安全门和生产交付证据，统一返回 `READY`/`DEGRADED`/`NOT_READY`、组件状态和稳定阻塞码。
- [x] 聚合逻辑 fail-closed：下游 readiness 异常只转换为稳定不可用码；响应不包含 endpoint、凭据、路径、原始异常或业务正文。
- [x] Operations Center 新增“生产交付准入”卡片，展示组件状态与阻塞项；旧 API/mock 缺少该方法时安全退化为空态，不影响既有运行指标、生命周期观测和 Trace 查询。
- [x] 当前结果仍应为 `NOT_READY`：真实 PostgreSQL/对象存储/SSO-JWT/Redis HA、外部 Provider 与安全引擎、备份恢复/PITR、SLO/UAT、上线审批和回滚演练尚未形成目标环境证据；该接口用于显式暴露缺口，不替代外部验收。
- [x] 生产发布请求与晋级已接入该 gate：只有平台整体 `READY` 才创建 PRODUCTION release，且晋级前再次检查；`DEGRADED`/`NOT_READY`/异常均以稳定 gate reason 阻断，且不会创建发布记录、改变已审批批次状态或调用 ReleaseTarget；STAGING 保持兼容。
- [x] 新增生产外部证据台账：固定九类证据、JSON 原子 upsert、revision 冲突保护、敏感元数据拒绝、管理员审计和 `/api/v1/admin/platform/evidence` 查询/更新 API；只有全部有效 `ACCEPTED` 且未过期才解除外部证据阻断。
- [x] Operations Center 展示证据台账并要求显式保存；旧 API/mock 缺少台账接口时安全退化为空态，不自动写入或伪造 READY。
- [x] 本轮平台 readiness/API/Web 证据已完成最终回归：API 711/711（0 failure、0 error、28 capability skips），Web 148/148，生产构建通过，生命周期统一校验 6/6；生产 readiness 仍需真实目标环境证据，不替代外部验收。

## 生产交付证据验收工具（2026-09-08）

- [x] 新增只读 `scripts/verify-production-handoff.ps1`：从 readiness 与生产证据台账读取状态，固定覆盖 9 类证据，输出不含 owner、引用、摘要或凭据的安全摘要。
- [x] 验收工具支持 `-Json`、`-BaseUrl` 和 `-FailOnNotReady`；退出码固定为 `0=完成且通过/非阻断检查`、`1=请求或响应校验错误`、`2=请求完成但生产交付未就绪`，不会写入平台状态或伪造 READY。
- [x] Pester 合约测试 6/6 通过；修复 PowerShell 人类可读表格输出对缺失证据行的空白显示，本地集成 API 真实验收清晰列出 9 项 `MISSING`，仍返回 `NOT_READY`、`0/9` accepted，符合 fail-closed 预期。

## A4.1 权限感知 Skill 搜索投影（2026-08-28）

- [x] 已完成可替换 `SkillSearchIndex`/`SkillSearchDocumentSource` 契约、默认 JSON 倒排投影、确定性命中字段/相关度、治理版本身份校验和授权后分页。
- [x] 已完成发布、废弃、下架、范围保存成功后的有界刷新事件，以及管理员状态/重建控制 API：`GET /api/v1/admin/search/index/status`、`POST /api/v1/admin/search/index/rebuild`。
- [x] 控制 API 仅返回 backend/status/revision/documentCount/indexedAt/reasonCode；请求体只允许 `expectedSourceHash`，错误返回稳定码，不回显 source hash、正文、Prompt、凭据或异常文本。
- [x] A4.1 聚焦搜索/目录回归 35/35；API 全量 1061/1061，0 failure、0 error、43 capability skips；Web 163/163；Web 生产构建通过。
- [x] 已完成显式 `search-index-backend=json|postgresql` 选择、V16 搜索索引 schema、JDBC 事务投影、advisory lock 全量替换、刷新失效后重建和平台 readiness；默认 JSON 行为保持兼容。
- [x] 本轮新增搜索后端/平台 readiness 聚焦回归通过；API 全量报告汇总 1073 项，0 failure、0 error、43 capability skips。
- [x] 已补齐可选 PostgreSQL V17/V18 刷新日志与持久化消费位点：发布/废弃/下架/范围变更事件以 metadata-only 幂等 key 落库，轮询器按序至少一次投递，消费失败不推进位点，协调器对重复事件做有界去重；默认 JSON/事件关闭行为保持兼容。
- [x] 已补齐跨实例链路 focused 回归与 V17/V18/V20 migration/readiness/configuration contract；PostgreSQL 治理聚合和 Skill 范围写入已将 metadata-only refresh outbox 绑定到各自主事务，事件日志失败会回滚对应主写入，平台 readiness 在事件开关开启且 V18/V20 未就绪时返回稳定阻断码；V20 增加 consumer ACTIVE/RETIRED 生命周期、heartbeat 和管理员 activate/retire 控制。
- [x] 已完成本地 OpenSearch + Redis Streams 刷新总线联调；消息总线按 `consumerId` 派生独立 consumer group，确保多实例各自收到完整刷新事件，ACK 路由到对应 group。
- [x] 本轮重新验收事务 outbox 与投递链路：搜索/治理/JDBC/Redis focused 套件共 130 项通过，Testcontainers 实际执行 PostgreSQL + Flyway V20；本地 Redis Streams 集成能力在 Docker 可用时按独立 consumer group 验证，Docker 不可用时保持显式 capability skip。
- [ ] 仍需生产外部验收与后续增强：OpenSearch/Redis HA、TLS/Secret Manager、生产消息平台或 Redis 方案、跨聚合统一事务、容量/性能压测和真实多实例故障演练仍需目标环境单独完成；V17/V18/V20 retention 仍由管理员显式退休 stale consumer，避免自动误删未消费事件。

## 验证证据

- `apps/web`：最新 `npm.cmd test`，181/181 通过；`npm.cmd run build` 通过。
- `apps/web`：`npm.cmd run build`，Vite 构建和 Sites 产物生成通过。
- `apps/api`：最新 `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 test`，Surefire 汇总 `1154` tests、0 failure、0 error、8 个 Windows 符号链接能力跳过；Docker/Testcontainers 已实际连接并执行 PostgreSQL/OpenSearch migration 集成能力。此前的 Windows JUnit 临时目录 teardown 竞态在本次全量复跑中未重现。
- `scripts/verify-production-handoff.Tests.ps1`：6/6 通过；真实本地 8081 验收保持 `NOT_READY`、`0/9` accepted，并清晰列出 9 项缺失证据，不暴露证据元数据正文。
- 本地 HTTP：Web `127.0.0.1:5173` 与 API `127.0.0.1:8080` 返回 200；已验证 Skill 第 2 页、合集接口元数据和通知单条/全部已读。
- 浏览器回归：技能市场标题显示真实 Skill 总数，通知面板可展开，合集页具备搜索/排序/视图/分页/创建入口。
- 生命周期门禁：统一脚本已验证 Web/API 测试、Web 生产构建、首页和 Provider readiness 冒烟均通过。
- 生产证据台账：统一校验 6/6 通过；仅 `PostgresSkillLifecycleProjectionStoreTest`（13 项）和 `PostgresQualityEvidenceIntegrationTest`（7 项）因 Docker 不可用而 capability skip，未掩盖编译、单元或完整回归失败。
- 本轮新增发布领域、持久化、服务、控制器和 Quality Center 聚焦回归；全量回归若遇 Windows JUnit 临时目录清理竞态，需按 M11 规则单独重跑失败类并记录结果。
- 本轮新增审核后 STAGING 登记、准入策略、安装/下载边界和 Quality Center 准入摘要聚焦回归；`RELEASE_STAGING_ENROLLMENT_FAILED` 仅记录 Skill/版本/稳定原因码，不回显内部异常正文。

## 仍需外部接入的事项

本轮没有伪造生产能力：企业 SSO/JWT、生产数据库/对象存储/安全扫描、Redis 多实例共享、Prometheus/Grafana/Alertmanager、部门消息渠道、备份恢复演练、渗透/性能/UAT 和 M6 上线门禁仍需部署环境、凭据、责任人和验收数据后执行。
## 部门版 Windows + MySQL 范围调整（2026-09-09）

- [x] 目标从企业级云原生平台收敛为部门级单机平台：Windows、≤100 用户、MySQL 8.0+、本地文件、基础鉴权。
- [x] 新增部门版 ADR、设计文档和实施计划；旧 Kubernetes/CCE、PostgreSQL、Redis、OpenSearch、MinIO 和 SSO/JWKS 资产降为历史参考。
- [x] 默认本地环境模板改为 MySQL-only，`start-local.ps1` 默认转发到无 Docker 的部门版启动脚本。
- [x] 新增 `verify-department-local.ps1`，只检查 Java、Maven、Node.js、MySQL、API 和 Web。
- [x] 新增 MySQL 驱动、Flyway MySQL 支持、MySQL 连接池/迁移/readiness 后端，以及部门治理聚合的 MySQL JSON 存储。
- [x] 部门治理、质量证据、Benchmark、发布记录、执行环境、Skill Scope/Relation、优化 Work Item/Experiment/Observation/Assessment、生产验收证据均已迁入 MySQL JSON 文档表；搜索投影保持进程内有界实现，制品仍保留本地文件存储，符合部门单机约束。
- [x] 部门本地鉴权已接入：PBKDF2 密码哈希、本地登录/guest 会话、短期内存 Bearer token、ADMIN/MEMBER/VIEWER 角色映射；部门配置拒绝仅伪造 `X-User-*` 请求头。
- [x] Web API client 已支持登录、guest 会话、Bearer 自动注入和退出登录；启动/验收脚本会使用 guest 会话验证受保护的 Skill API。
- [ ] 尚未完成真实 MySQL smoke：当前机器 `127.0.0.1:3306` 未监听；需要安装/启动 MySQL 并创建 `skillcenter` 数据库后继续验证迁移和核心生命周期写读。

### 部门版本轮实现验收（2026-09-09）

- [x] MySQL JSON 文档适配器契约测试通过：质量证据、Benchmark、发布记录、执行环境、Scope/Relation、优化 Work Item/Experiment/Observation/Assessment、生产验收证据均选择 MySQL-safe 事务写读路径。
- [x] 新增 MySQL Testcontainers 集成回归通过：真实 MySQL 8.4 容器完成 Flyway V1/V2 迁移、质量证据 JSON 写读和 revision 更新；该依赖仅限 test scope，不进入部门运行时。
- [x] 新增完整部门配置上下文 Testcontainers 回归：真实 MySQL 8.4 下 Flyway 先于仓储初始化，全部 MySQL 后端 Bean、Memory 运行摘要、JSON 搜索和本地鉴权组合成功启动；修复 MySQL 迁移 Bean 顺序和执行环境 store 构造器装配缺口。
- [x] 新增 `MemoryRuntimeSummaryStore`：部门单实例使用进程内运行摘要，复用既有校验/幂等逻辑并明确返回 `DEGRADED/RUNTIME_SUMMARY_MEMORY_ONLY`，不引入 Redis 运行前置。
- [x] API 全量回归：1177 tests，0 failure、0 error、8 个能力跳过；Web：182 tests，0 failure；Vite 生产构建通过。
- [x] 平台 readiness 已补齐部门 MySQL 分支：Benchmark、优化 Work Item/Experiment、Skill scope/relation 均按全局 MySQL、MySQL JSON 文档 schema V2 和实际 backend 状态进行 fail-closed 检查，不再误报 PostgreSQL-only 配置错误。
- [x] 优化实验自动协调调度器已同时接受 PostgreSQL/MySQL 就绪后端；部门版的实验 → Benchmark → 决策持续优化闭环不再被 PostgreSQL-only 构造门禁误阻断，并新增 MySQL 正向回归。
- [x] 已核对剩余 PostgreSQL 专用实现：生命周期关系型投影、PostgreSQL 搜索目录和搜索事件总线均由显式 PostgreSQL 选择器/事件开关保护，部门配置使用 JSON 搜索/生命周期投影且关闭事件，不进入部门启动路径。
- [x] 部门启动脚本已校验 8080 上的现有实例必须通过 guest 登录和基础 Skill API；旧 API 占用端口时 fail-closed 并给出明确冲突提示，避免错误复用。
- [x] 新增显式 `bootstrap-department-mysql.ps1` 初始化助手：仅使用本机 `mysql.exe`，以管理员交互密码创建 `skillcenter` 和最小应用账号权限，不把密码放入命令行或写回 `.env`；支持安全 dry-run。
- [x] 新增 `smoke-department-local.ps1`：默认只读验证 guest 登录、Skill 目录/详情/内容/质量视图；显式 `-IncludeEvaluation` 才提交 smoke 评测并验证质量证据重读，失败不回显 HTTP 正文。
- [x] PowerShell 启动/环境契约及部门 smoke/初始化助手：17/17 通过；部门脚本在跳过现有服务的 dry-run 场景通过，并已验证占用 8080 的旧 API 会被明确拒绝；所有新增脚本解析无错误，`git diff --check` 通过。
- [ ] 本机运行态仍未就绪：MySQL 3306 未监听、`mysql.exe` 不可用、`.env` 未设置本地管理员 PBKDF2 hash；5173 Web 当前可访问，但 8080 部门版 API 未就绪，不能计入完整部门版运行验收。
