# M11 增量回归与上线评审状态

日期：2026-08-25

## 2026-08-25 质量回归运营告警增量

- 发布后评估新增只读质量回归信号，按 Skill 去重并只关注最新评估；回归条目限制 100 条且稳定排序，不保存业务正文。
- Operations Alert 新增 `QUALITY_REGRESSION`，复用固定状态键、持久化状态转换、通知去重和恢复通知；质量评估存储异常统一为 `QUALITY_REGRESSION_SIGNAL_UNAVAILABLE`，不静默报健康。
- 新增测试覆盖旧回归被健康评估清除、多个 Skill 去重、回归激活/健康恢复和探针异常；API `948/948`，0 failure、0 error、43 个 Docker capability skips。Web 侧无新增接口，沿用既有通用告警展示。

## 2026-08-25 质量证据留存保护增量

- 留存预览/执行已接入生命周期引用索引，保护优化工作项、实验和发布门禁仍引用的评测运行、质量快照、Benchmark 与兼容性矩阵。
- 预览冻结保护指纹，执行复核引用变化；`RETENTION_PROTECTION_CONFLICT` 与 `RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE` 均在删除前 fail-closed。
- 新增测试覆盖稳定指纹、引用去重、工作项/实验/发布索引、质量证据/矩阵级联、Benchmark 保护、冲突、不可用和旧构造器兼容。
- 本轮 API 全量 `943/943`，0 failure、0 error、43 个 Docker capability skips；Web `159/159`，生产构建与统一生命周期 verifier 通过。Docker skip 仍不替代真实 PostgreSQL/Provider/生产 UAT。

## 2026-08-25 执行环境证据快照与版本可比性增量

- `ExecutionEnvironmentSnapshot` 只保存环境类型、ID、version、revision、status；评测运行、QualitySnapshot 和 Runner 执行记录在任务开始边界冻结同一份快照，不写入配置引用、凭据或业务正文。
- 评测状态流转、质量快照和 Runner 记录沿用已捕获快照；环境目录后续变更不会改写历史证据。旧的 ID-only JSON 记录恢复为兼容的 legacy snapshot。
- `QualityComparisonCalculator` 在完整快照不一致时返回 `EVALUATION_CONTEXT_MISMATCH`；同一环境 ID 的不同 revision 已有回归覆盖，避免跨环境或跨目录版本错误比较。
- Web 质量详情、Benchmark、Runner 历史和版本对比已展示 version/revision/status；旧 ID-only 证据保持兼容，不被前端误标为完整快照。
- 本轮回归：API `932/932`，0 failure、0 error、43 个 capability skips；Web `159/159`，生产构建和生命周期 verifier 通过。Docker/PostgreSQL 能力测试仍因目标环境不可用而跳过，不替代真实数据库/Provider 验收。

## 2026-08-25 执行环境资产目录 PostgreSQL 持久化增量

- 执行环境资产目录新增 `ExecutionEnvironmentRepository` 持久化端口，默认 JSON 兼容，PostgreSQL 仅在执行环境 selector 与全局 persistence 同时显式启用时装配，不自动回退或双写。
- Flyway V11 新增 `skill_execution_environment`；版本、能力、状态和脱敏配置引用以 JSONB payload 与受控列保存，revision 乐观并发冲突返回 `EXECUTION_ENVIRONMENT_REVISION_CONFLICT`，数据库故障统一为 `EXECUTION_ENVIRONMENT_PERSISTENCE_FAILED`。
- 资产已纳入 persistence artifact catalog/readiness；本地 API 全量 `929/929`，0 failure、0 error、43 个 capability skips。Docker 不可用的 PostgreSQL 能力测试仍单列，真实数据库供应、容量/SLO、备份/PITR 和生产迁移验收未被本地回归替代。

## 2026-08-25 治理聚合与 Skill 版本 PostgreSQL 持久化增量

- 治理事实源新增 `GovernanceStateRepository` 端口；JSON 默认保持兼容，PostgreSQL 必须同时显式选择治理后端和全局 persistence，不自动回退或双写。
- V7 Flyway schema 以受控 JSONB 聚合保存版本/审核/审计等治理状态，JDBC 实现通过事务锁、revision 条件更新和稳定冲突错误保护多实例写入；生命周期离线投影不再被误当作在线治理事实源。
- V8 新增 `skill_governance_version` 关系表，SkillVersion 与治理聚合在同一事务内写入，重启读取以关系行恢复版本和安全扫描证据，旧聚合可回填关系表。
- V9 新增 `skill_governance_review` 关系表，ReviewTask 与治理聚合在同一事务内写入，审核队列可按状态/风险/提交时间索引，旧聚合可回填关系表。
- V10 新增团队、角色绑定、分类、标签、合集和平台策略关系表，治理配置与聚合在同一事务内写入，旧聚合可回填关系表。
- PostgreSQL Hikari 连接池边界和数据库 readiness SLO 已接入；非法池配置与探测超时均 fail-closed，返回稳定 reason code。
- 全量回归已通过：API `922/922`，0 failure、0 error、43 个 capability skips；Web `158/158`，生产构建、生命周期 verifier 和 `git diff --check` 通过。Docker 不可用导致的 PostgreSQL 集成测试按命名 skip 单列。
- 仍开放：真实生产数据库供应、连接池容量/SLO 压测与验收、备份/PITR、生产迁移审批、容量压测、UAT 和上线/回滚演练。

## 2026-08-25 生产交付证据 PostgreSQL 持久化增量

- 生产证据台账新增 `ProductionEvidenceRepository` 端口和显式 JSON/PostgreSQL selector；默认 JSON 兼容旧环境，PostgreSQL 选择要求全局 persistence 同步 opt-in，不自动回退。
- V6 Flyway schema 保存受控安全元数据；JDBC 实现通过事务锁和 revision 条件更新保护并发写入，未知证据 ID、revision 冲突、非法行和数据库故障均保持 fail-closed。
- PostgreSQL Testcontainers 集成测试已加入；当前 Docker 不可用，相关测试仅报告命名 capability skip，不能替代真实数据库供应和生产迁移验收。
- 前述基线回归为 API `901/901`；本轮最新结果为 API `912/912`，0 failure、0 error、39 个 capability skips，Web `158/158`，生产构建和生命周期统一验证通过。

## 2026-08-25 生产交付证据运营告警增量

- 新增 `PRODUCTION_HANDOFF_EVIDENCE` 运营告警：证据台账未就绪或存储不可用时 ACTIVE，全部必需证据有效时 RESOLVED；告警使用稳定 reason code 和未满足证据数量。
- Spring 接入保持可选依赖，旧构造与旧 API/mock 继续兼容；异常 fail-closed，不输出报告正文、凭据、URL 或内部异常。
- Web Operations Center 已将该规则显示为“生产交付证据”，保留状态、阈值、数量和 reason code。

## 2026-08-25 优化工作项滞留运营化增量

- 优化工作项新增只读健康查询：按 `updatedAt` 与可配置 `skill-center.operations.alerts.optimization-work-item-stale-seconds`（默认 7 天）识别 `OPEN/PLANNED/IN_PROGRESS/READY_FOR_EVALUATION` 滞留项，终态不计入；返回活跃数、滞留数、状态/Owner/严重度分布和最多 100 条脱敏摘要。
- `OperationsAlertService` 新增 `OPTIMIZATION_WORK_ITEM_STALENESS` 状态告警，激活/恢复沿用现有 JSON/Redis 状态仓储和通知 sink；仓储异常只返回稳定 `OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE`，不暴露内部异常，也不自动改变工作项状态。
- Operations Center 新增“优化闭环健康”卡片，展示阈值和滞留分布，并提供质量管理入口；缺少新 API 的旧 mock 安全降级。
- 本轮最终本地回归：API 893/893、Web 157/157、Web 生产构建通过；仍不替代真实多实例 PostgreSQL/Redis、告警渠道、容量/SLO 和生产 UAT 验收。

## 2026-08-25 持续优化工作项 PostgreSQL 持久化增量

- `OptimizationWorkItem` 已具备显式 JSON/PostgreSQL 持久化选择：JSON 默认；PostgreSQL 必须同时启用全局 PostgreSQL，条件装配失败不会静默回退。Flyway V5 JSONB 表及非终态活动业务键唯一索引覆盖多实例并发创建，重复键与数据库故障保持不同错误语义。
- 平台 readiness 已投影 `OPTIMIZATION_WORK_ITEM_STORE`：本地 JSON 明确标记为降级，多实例 PostgreSQL 必须满足全局持久化 `READY` 且 schema 至少为 V5；运营中心复用现有 readiness 组件列表展示该状态。
- API 全量回归：887/887 通过，0 failure、0 error、33 个 Docker capability skips；新增优化工作项 readiness、JDBC 仓储异常边界测试验证数据库不可用不会被误报为活动工作项冲突。
- 该回归只证明平台侧适配器、配置门禁和错误契约；真实 PostgreSQL 供应、连接池/容量/SLO、备份/PITR、多实例故障注入和生产迁移审批仍开放。

## 2026-08-25 ReleaseTarget 连通性证据增量

- API 全量回归：887/887 通过，0 failure、0 error、33 个 Docker capability skips；新增 ReleaseTarget 状态探测、审计恢复、TTL、Bearer HTTP transport、控制器、历史查询、Redis 运行摘要原子写入、Operations Alert 状态迁移/Redis 去重、平台 readiness 接入、发布后评估后续工作项幂等创建和优化工作项 PostgreSQL 持久化边界均已覆盖。
- Web 全量测试：156/156 通过；生产构建通过；Quality Center 已覆盖显式发布后评估动作后的后续工作项刷新与来源证据展示。
- 生命周期统一校验：6/6 通过；其中仅 PostgreSQL Testcontainers 能力套件按规则跳过（13 + 7 项，Docker 不可用）。
- `git diff --check` 通过；工作区既有 LF/CRLF 提示为 Git 换行告警，不是 whitespace error。
- 生命周期统一校验通过（PowerShell 7，跳过重复 build/smoke）：Web 156/156、API 887/887；单独执行 Web 生产构建也通过。Windows PowerShell 5 直接解析该脚本会因无 BOM 的 UTF-8 中文字符串报语法错误，属于解释器编码兼容问题。
- 以上本地证据不代表真实发布目标、Secret Manager、网络出口、故障注入、容量和 UAT 已完成；目标环境验收仍保持开放。
- ReleaseTarget 历史查询继续只投影当前配置指纹匹配的审计安全字段，默认 20、最大 100 条；不把历史查询当作新的连通性证据，也不在读取时触发外部网络。

## 本地回归证据

- Web 自动化测试：134/134 通过。
- API 自动化测试：510/510 通过；范围治理新增全量授权/边界回归。Windows 全量执行使用 `-DforkCount=0`，Surefire 汇总为 0 failure、0 error、0 skipped；首次失败的首版提交兼容测试、范围元数据越权读取和顺序依赖下载 fixture 已修复并单独复核。
- Web 生产构建：`npm.cmd run build` 通过，并生成 Sites 构建产物。
- 运行冒烟：前台首页、Skill 目录接口、带 Trace 状态筛选的 Trace 接口均返回 HTTP 200。
- 质量/运营回归覆盖：Mock Runner、质量快照、Benchmark、版本对比、Runtime/MCP/LLM 环境筛选、Trace ID/状态/operation 展示、脱敏和管理员权限。

## 已具备的 M11 基线

- OpenClaw Runner、DeepEval 评测、Langfuse 观测已提供版本化适配契约和测试桩，核心域不依赖第三方 SDK。
- 外部 Provider 默认 fail-closed，密钥只接受受控引用；错误收敛为稳定错误码，不保存 Provider 异常正文。
- Provider readiness 同时输出稳定原因码和管理员可读的中文原因说明，便于运行态诊断且不暴露凭据。
- 运行摘要 JSON 恢复会校验记录语义；损坏证据不会进入聚合，服务以稳定持久化错误失败启动。
- 质量证据 JSON 恢复会校验套件、评测运行、快照和用例结果语义，避免损坏快照影响质量门禁。
- 质量证据写入同样执行语义校验，非法状态不会落盘。
- 运行事件、评测运行和质量快照恢复会检查主键唯一性，避免重复聚合或静默覆盖。
- Mock Runner 摘要事件 ID 仅由稳定 `runId` 生成，不受 Provider 返回顺序变化影响，保证 Trace/运营查询的跨重启可追溯性。
- Trace 聚合使用 `traceId + spanId` 复合键，避免不同 Trace 的同名 Span 互相覆盖，同时保持 Provider 与运行摘要双入口去重。
- Mock Runner 缺少发生时间时使用服务注入时钟，保证窗口聚合和重放测试结果确定。
- 逐用例质量结果按“运行 ID + 用例 ID”检查唯一性，避免通过率和失败定位被重复记录污染。
- 质量套件及套件内用例恢复检查唯一性，避免评测选择被静默覆盖。
- 逐用例结果恢复校验所属评测运行存在，避免质量详情出现不可追溯的孤立结果。
- 质量快照恢复校验所属评测运行存在，保证质量详情与发布门禁使用同一条证据链。
- 质量快照恢复同时校验 Skill/版本与所属评测运行一致，避免错配证据影响发布门禁。
- 质量快照恢复拒绝引用非 `COMPLETED` 评测运行，避免失败/取消运行被误判为可发布证据。
- 质量快照恢复校验套件 ID/版本与所属评测运行一致，避免跨套件结果混入版本对比。
- 质量快照恢复校验 Runner、评测 Provider 和数据源与所属评测运行一致，避免 Mock/生产证据串线。
- 质量快照恢复校验 Runtime/MCP/LLM 执行环境与所属评测运行一致，避免不同环境结果错误合并。
- 评测运行的 `COMPLETED`、`FAILED`、`TIMED_OUT`、`CANCELLED` 等全部终态必须包含 `completedAt`，且不得早于 `createdAt`。
- 质量运行恢复要求 `COMPLETED` 状态具备完成时间，且完成时间不早于创建时间，保证证据时间线可审计。
- 质量引用校验对历史无套件目录状态保持兼容；新状态存在套件目录时强制校验套件/用例归属。
- Provider readiness 区分真实健康 Provider 与 `CONTRACT_ONLY` 适配器；契约适配器全部选中时仍保持 `PARTIAL`，避免把未启用的真实能力误报为 `READY`。
- readiness 同时返回待接入 Provider ID 清单，质量中心可直接展示具体阻塞项。
- readiness 同时返回未配置活动 Provider ID 清单，质量中心可直接展示配置缺口。
- 质量中心提供管理员连接探测入口；探测只访问受控 endpoint、只记录状态/耗时并写入脱敏审计，不改变 readiness 或 `CONTRACT_ONLY` 语义。
- Mock 故障注入、重试边界、幂等、权限、脱敏和 MVP 性能预算已有自动化覆盖。
- 既有资产管理主链路与质量/运营增量已纳入同一套 Web/API 回归命令。
- 优化工作项服务具备独立 JSON 原子存储、活动业务键冲突保护、状态机约束、建议安全快照和审计记录；终态要求候选版本、匹配质量证据及结果说明。
- 优化工作项证据绑定按 Skill/候选版本、dataSource 和 Runtime/MCP/LLM 环境校验，避免 Benchmark、评测运行和质量快照跨上下文串联。
- 质量中心已覆盖建议转工作项、候选版本推进和证据绑定的交互回归，且不渲染 Prompt、输入输出正文或凭据。
- 质量中心工作项面板已覆盖完成、放弃、重新打开和证据台账回归，确保优化结果可以被后续审计和持续迭代复用。
- 执行环境资产目录已覆盖 Agent Runtime、MCP Server、LLM Provider 的登记、版本/能力/状态管理、原子恢复和管理员审计；新评测与 Runner 仅接受 ACTIVE 目录资产，质量中心提供按类型选择并保留目录不可用时的受控 ID 回退。
- 兼容性矩阵已纳入质量证据回归：确定性笛卡尔组合、ACTIVE 环境版本快照、子评测聚合、管理员 API、取消、重启恢复、显式发布门禁和保留期级联均有 API 测试；质量中心提供矩阵创建、状态轮询和组合证据展示。
- 兼容性矩阵用例会保存脱敏的能力列表和 Adapter Provider 快照；矩阵取消会同步请求取消子评测，前端支持多选、100 组合预检、运行中取消，并在环境目录为空时明确阻止创建。
- 评测套件采用逻辑 ID 与不可变版本复合身份；启用指针切换、历史版本追溯、评测/矩阵/Benchmark/对比的精确版本上下文，以及复制新版本的质量中心交互均已纳入回归。
- 优化工作项采用不可变的套件 ID/版本上下文；新工作项拒绝跨套件或跨版本的评测运行、质量快照和 Benchmark 证据，旧工作项保持兼容，状态流转与证据绑定不改变评测口径。
- OptimizationExperiment 已接入优化闭环：实验 ID 与 EvaluationRun/Benchmark 幂等关联，支持持久化恢复、显式 reconcile/取消、同上下文质量快照回写和 Benchmark；实验完成不自动完成工作项或发布 Skill。
- OptimizationExperiment 决策快照已接入闭环：管理员可在同一质量门禁与 Benchmark 上下文中生成幂等决策，保存稳定原因码、推荐动作和证据 ID；重复请求返回原快照，决策不自动发布、回滚或推进工作项。
- OptimizationExperiment 决策已接入 `QualityReleaseGate`：活动实验、失败/取消实验、缺少决策和非促进决策分别以稳定原因阻断候选版本发布；促进决策仍需通过兼容性矩阵、质量快照和人工 Review。
- 发布审核被优化实验门禁阻断时，Web 客户端按稳定原因码展示实验状态、质量管理中心入口和下一步处理指引，同时保留原始错误码与详情供审计定位。
- 发布审核被质量门禁阻断时，服务端持久化 `PACKAGE_APPROVAL_BLOCKED` 审计事件，记录 Skill/版本、审核人、requestId 和稳定门禁原因；审核任务与版本继续保持 `pending_review`，并通过重启恢复测试验证。
- 审计与导出工作台新增管理员生命周期事件查询，可按动作和资源类型筛选发布阻断、质量证据与优化闭环事件；前端仅展示白名单元数据，过滤 Prompt、输入输出和凭据。
- 执行环境目录状态与 Provider readiness 明确分离：登记或连接探测不会把 `CONTRACT_ONLY` 外部适配器变成真实可执行能力。
- Provider 契约一致性校验已覆盖版本/能力漂移、未注册和 `CONTRACT_ONLY` 匹配状态；该诊断接口只读、不发网络、不改变 readiness，质量中心显示稳定原因和差异。
- 优化实验发布后观察已覆盖发布状态与 Promote 决策约束、运行聚合快照、JSON 重启恢复、管理员 API、审计元数据和 Quality Center 手动采集；无流量明确为 `NO_TRAFFIC`，不自动回滚。
- 发布后效果评估已覆盖同窗口源版本基线、健康/回归/流量不足/不可比较结论、人工处置、套件版本及执行环境上下文、工作项证据绑定和 RetentionService 清理；评估只提供建议和审计，不自动回滚或修改发布状态。
- 发布后评估的 `CREATE_FOLLOW_UP` 动作已接入后续优化工作项创建：按评估 ID 幂等、保留观察/结论/原因码证据上下文、写入后续工作项审计关联；质量中心刷新工作项列表并展示来源证据，发布与回滚仍保持人工边界。
- 后续工作项幂等性已覆盖并发创建竞态：活动业务键/确定性工作项 ID 冲突由存储原子写入结果兜底，重复请求复用既有工作项且不重复写创建审计。
- 受控发布控制面已覆盖发布批次不可变上下文、STAGING/PRODUCTION 审批角色、质量门禁快照、幂等/活动业务键、Mock Target 失败收敛、执行中重启 fail-closed、回滚复核和管理员 API；ReleaseRecord 与 SkillVersion 状态解耦。
- 审核通过后的生命周期衔接已覆盖冻结门禁快照、STAGING 幂等登记和安全失败审计；`LEGACY_COMPATIBLE`/`CONTROLLED` 两种准入模式、生产批次状态和制品 SHA-256 一致性均由同一服务判断，且安装授权与下载令牌不会在准入拒绝前被发放/消耗。
- 发布前可执行 `pwsh -NoProfile -File scripts/verify-lifecycle.ps1`，统一运行 Web/API 测试、构建和前台/API 冒烟。
- Skill 版本关系回归覆盖关系存储恢复、重复/自环/环依赖拒绝、反向影响遍历、深度/节点截断、生产晋级与活跃安装补充，以及管理员详情弹窗只读展示；关系报告不会自动触发发布或下架阻断。
- Skill 范围治理回归覆盖 JSON 原子存储、重启恢复、revision 冲突、活动团队、显式维护者、历史 PUBLIC 回退、首版提交、目录/内容/分发/下载/关系隐藏和管理员 Web 显式保存。
- JWKS 身份边界平台适配已补齐：静态 PEM/JWKS 配置互斥、服务端 URL 与 HTTPS/loopback 约束、RSA/RS256/`kid` allowlist、有界 HTTP/TTL、未知 key 单次刷新、重复 miss 退避和过期 fail-closed；平台不会输出 Token、JWKS、密钥、URL 或上游异常。JWT `team-claim` 已映射到 TEAM 可见性并保持空且权威、active TeamDefinition、本地 maintainer binding 边界。真实 SSO/JWKS endpoint、网络白名单、Secret Manager、组织目录字段/撤销、跨团队审核和 UAT 仍未验收。
- 组织声明增量最终回归证据：API `793/793`（0 failure、0 error、33 capability skips），Web `149/149`，生产构建通过，统一 lifecycle verifier `6/6`；Docker 不可用只影响命名 PostgreSQL capability suites。该证据证明平台适配与授权边界，不证明真实 SSO、组织目录同步、撤销时效、跨团队审核或生产 UAT。
- 组织目录快照同步专项已通过 focused 回归：覆盖 schema/边界、原子持久化/重启恢复、HTTP Bearer 与响应限制、管理员同步/状态、目录成员 + JWT claim 授权、失败/过期拒绝、统一 503/409 错误契约和 readiness 投影；该证据证明平台适配边界，不证明真实企业目录、撤销传播、Secret Manager、跨团队审核或生产 UAT。
- 组织目录快照同步专项最终回归：API `820/820`（0 failure、0 error、33 capability skips），Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；Docker skip 仅影响命名 PostgreSQL capability suites。
- 外部包安全扫描 HTTP 适配专项已通过 focused 回归：覆盖配置/URL/凭据/能力边界、ZIP SHA-256 请求头、Bearer 传输、严格响应 allowlist、阻断 finding、非 2xx/超大响应和 Spring 默认/显式选择；该证据证明平台适配边界，不证明真实扫描服务、规则库、Secret Manager 或生产 SLA。
- 外部包安全扫描适配增量最终回归：API `831/831`（0 failure、0 error、33 capability skips），Web `149/149`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；Docker skip 仅影响命名 PostgreSQL capability suites。
- Provider 契约合规矩阵已补齐本地自动化证据：Runner/Evaluation/Observability 适配器覆盖业务用例名称不外传、未知响应字段 fail-closed、响应体 256000 字符上限、稳定错误码、重试策略、幂等/故障注入和关联 ID；本地内存传输基线 900 次适配调用限制在 2 秒内。该基线只证明平台序列化/解析预算，不证明真实 Provider 网络延迟、容量或生产 SLA。
- Provider 契约与 Quality Center 兼容性增量最终回归：API `837/837`（0 failure、0 error、33 capability skips），Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；同时修复缺少评测状态 API 时 Quality Center 轮询产生的异步 TypeError。Docker skip 仅影响命名 PostgreSQL capability suites。
- Provider HTTP 传输安全增量已完成：JDK transport 改为 InputStream 有界读取，固定长度与 chunked 响应超过 256000 字节均在解析前收敛为 `UPSTREAM_RESPONSE_TOO_LARGE`；本地 HTTP Server 已覆盖超大响应、超时、429、5xx、Bearer 和正文脱敏。该边界不替代真实 Provider 网络容量和 SLA 验收。
- Provider HTTP 传输安全增量最终回归：API `839/839`（0 failure、0 error、33 capability skips），Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；固定长度与 chunked 超大响应均在 JDK 流读取边界被拒绝。
- ReleaseTarget 生态集成 focused 回归已通过：HTTP target 覆盖发布/回滚动作、metadata-only 请求、Bearer Secret、未知响应字段、无效响应和稳定 transport failure code；Spring 条件装配确认显式 HTTP 模式不会静默使用 Mock。真实 Runtime/MCP/LLM/CD 目标联调仍不在本地证据范围内。
- ReleaseTarget 生态集成最终回归：API `846/846`（0 failure、0 error、33 capability skips），Web `150/150`，生产构建、生命周期 verifier `6/6` 和 `git diff --check` 均通过；本地 JDK HTTP Server 已覆盖 Promote/Rollback、Bearer 和 metadata-only 请求。

## 尚不能由本地环境签署的项目

以下项目必须在目标部署环境完成，不能用 Mock 或本地测试替代：

1. 真实 OpenClaw、DeepEval、Langfuse 实例联调、认证头/协议适配、故障注入和生产性能压测；本地连接探测不能替代这些验收。
2. Redis 多实例高可用、容量基线、故障转移和备份恢复演练。
3. 生产 SSO、组织目录、跨团队审核映射以及客户端协议联调。
4. Prometheus/Grafana/Alertmanager、部门通知平台、UAT、渗透测试和上线审批。
5. 真实 Runtime/MCP/LLM/CD 发布目标尚未接入；当前 Mock Target 仅用于本地确定性演练，外部目标必须实现同一 ReleaseTarget 契约并保持失败闭环。

## 评审结论

M11 本地代码与契约基线满足继续集成的条件；生产上线门禁保持未签署状态，待上述外部依赖完成并由研发、业务、安全、运维共同验收。
