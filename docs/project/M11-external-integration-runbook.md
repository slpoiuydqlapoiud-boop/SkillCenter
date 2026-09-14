# M11 外部 Provider 与 Redis 联调运行手册

版本：V1（2026-08-24）

本手册用于目标环境的联调准备，不代表本地 MVP 已连接真实外部系统。默认及未批准的外部 Provider 必须保持 `CONTRACT_ONLY`，不得自动降级为 Mock；经目标环境配置批准的 HTTP adapter 也不等同于生产验收完成。

## 0. A1 持久化控制面前置检查

部署前先确认本地 JSON 控制面配置和启动状态：

```yaml
skill-center:
  persistence:
    backend: json
    configured-root: ${user.dir}
    control-storage: ./data/control
    snapshot-storage: ./data/backups
    startup-mode: fail-closed
    manifest-retention: 10
```

管理员只读检查 `GET /api/v1/admin/persistence/status`。`READY` 表示已登记资产可读取且 migration journal/路径校验通过；非关键资产缺失为 `DEGRADED`；关键缺失/损坏、`MIGRATION_REQUIRED`、`MIGRATION_UNSUPPORTED`、非法 backend/path、符号链接逃逸或控制面异常为 `FAIL_CLOSED`。响应是 metadata-only，只含资产 ID、版本、大小、摘要、计数和稳定原因码，不含业务正文、Prompt、Trace、工具参数、Token、凭据或原始异常。

快照必须显式调用 `POST /api/v1/admin/persistence/snapshots`，GET 不创建快照、不写 journal。`POST /api/v1/admin/persistence/snapshots/{snapshotId}/restore-preflight` 只做 manifest/哈希/资产集合/路径边界检查；它不覆盖在线数据、不执行在线 restore。实际恢复必须停机或隔离执行，先备份当前根、离线校验并由审批脚本原子替换，再重启验证 startup gate。所有 configured root、control/snapshot root、artifact ID、snapshot ID 都要规范化并拒绝路径穿越及符号链接跳转。

`manifest-retention` 对 `control-storage/snapshots.json` metadata 与 snapshot directories/data copies 是同一 retention 行为，不是仅清理 metadata。淘汰前必须验证对应 manifest 和目录为已发布的 `COMPLETE` snapshot，随后删除该完整 snapshot directory；因此外部备份保留、审批和恢复演练仍由目标环境负责。API 仍只返回 snapshot metadata，A1 不提供在线 restore。

A1 当前不实现 PostgreSQL/Flyway 或数据库切换；A2 handoff 需要在不改变 artifact ID、状态码和 metadata-only 边界的前提下提供 PostgreSQL adapter、事务一致性、备份/恢复演练和迁移审批。平台侧已将 Skill 制品统一收敛到 `ArtifactStorage`：新上传按 SHA-256 内容寻址并保存 opaque 引用，Manifest/下载/目录读取均重新校验 ZIP 与 SHA-256，缺失或篡改不再静默生成替代包。`skill-center.artifact-storage-backend=object-storage` 默认是 contract-only；显式 `skill-center.artifact-storage.mode=http` 时使用 S3-compatible HTTP adapter，以 `endpoint/bucket/region/prefix` 和 `secret://...` 凭据引用配置，未配置或不可用时 fail-closed，绝不回退本地文件。管理员可调用 `POST /api/v1/admin/platform/artifact-storage/probe` 做 metadata-only bucket 控制面探测；探测结果只投影安全状态、稳定原因码、HTTP 状态、耗时和时间，并写入脱敏审计，不读取制品内容。服务重启会从治理域已有的脱敏探测审计恢复最近记录，但恢复结果必须同时匹配当前 backend 和稳定非敏感 `adapterId`（S3 HTTP/contract-only/local），并通过状态、数值字段和 `probe-ttl-seconds`（默认 300 秒）校验；旧格式、非法或不完整审计直接忽略。只有最近一次且未过期的探测为 `REACHABLE`，才会将 HTTP 对象存储 readiness 提升为 `READY`；过期或未来时间戳返回 `ARTIFACT_STORAGE_PROBE_EXPIRED` 并保持 fail-closed。Local/contract-only 后端为 `SKIPPED`，不会伪造云存储连通性。真实 S3/OBS 网络、复制/保留、签名 URL、备份/容量/SLO 仍需目标环境验收。企业 SSO/JWT、生产 Provider、密钥管理、Redis HA、监控告警、安全扫描、性能/UAT 和上线审批仍未完成。

### M11 组织目录快照同步（平台侧适配，真实目录仍待验收）

组织目录采用 `organization-directory.v1` 快照，不在请求路径访问外部目录。默认配置为 `mode: local`；企业环境显式切换到 `mode: http` 后，仅管理员调用以下接口触发拉取：

```text
GET  /api/v1/admin/governance/organization-directory
POST /api/v1/admin/governance/organization-directory/sync
```

HTTP 模式配置使用 `endpoint`、`credential-ref: secret://...`、有界超时/响应大小、`max-age-seconds` 和本地快照 storage。适配器只接受 bounded JSON、允许字段和活动团队成员 ID，使用 Bearer Secret；不会保存 Token、原始响应、用户属性或异常正文。快照按 revision/content hash 幂等，重复 revision 内容改变、非法响应、同步失败或超过 TTL 时保持 fail-closed，不回退本地成员表。

TEAM 授权在 HTTP 模式同时要求本地活动 TeamDefinition、未过期目录快照中的团队成员关系以及 JWT authoritative `team-claim`（若启用）；管理/发布继续要求本地 maintainer binding。状态 `ACTIVE` 才可用于授权，`STALE`、`FAILED`、`NOT_CONFIGURED` 均拒绝目录授权；本地模式仅报告 `ORGANIZATION_DIRECTORY_LOCAL_ONLY` 降级，不阻断本地开发。真实目录字段、撤销 SLA、Secret Manager、网络白名单、跨团队审核映射和 UAT 必须由目标环境单独验收。

### M11 企业 SSO/JWKS 身份边界（平台侧适配，真实联调仍待验收）

JWT 模式继续只接受 `Authorization: Bearer`，忽略客户端身份/角色 Header。平台侧支持静态 `public-key` 与服务端配置 `jwks-uri` 二选一；JWKS 仅接受生产 HTTPS（loopback 测试允许 HTTP）、RSA/RS256、非空 `kid` 和合法 `n/e`，响应大小、连接/请求超时和缓存 TTL 均有界。未知 `kid` 最多触发一次刷新；缓存过期且刷新失败、重复/非法 key、HTTP 非 2xx、超大响应或非法 JSON 均拒绝 Token，不把过期 key 延长为可信，不回显 Token、JWKS、密钥、URL 或上游异常。JWT 的 `team-claim` 由服务端配置并只接受 bounded 字符串 ID；缺失可选 claim 仍是空且权威团队集合，TEAM 必须命中本地 active TeamDefinition，管理/发布仍要求本地 maintainer binding。真实 SSO endpoint、网络白名单、密钥管理、组织目录 claim 约定、撤销策略和 UAT 仍需目标环境完成；本地 HTTP Server 测试只证明平台适配器边界。

## M11 Provider HTTP 适配器（平台侧已实现，真实连接仍待验收）

平台已提供 OpenClaw Runner、DeepEval EvaluationProvider 和 Langfuse ObservabilityProvider 的 contract-v1 HTTP adapter。适配器只发送 Skill/版本、运行/套件/用例 ID、状态、耗时、错误码、哈希和受控 Runtime/MCP/LLM 标识；不发送 Prompt、输入输出、工具参数、文件正文、路径或凭据。

默认配置继续使用 Mock 或 `mode: contract`：

```yaml
skill-center:
  providers:
    runner: mock
    evaluation: mock
    observability: mock
    trace: mock
    openclaw:
      mode: contract
      endpoint: ""
      credential-ref: ""
    langfuse:
      mode: contract
      endpoint: ""
      trace-endpoint: ""
      credential-ref: ""
```

目标环境完成 Secret Manager、网络出口和 Provider 契约评审后，才可显式切换单个 Provider：

```yaml
skill-center:
  providers:
    runner: openclaw
    trace: langfuse
    openclaw:
      mode: http
      endpoint: ${SKILL_CENTER_OPENCLAW_ENDPOINT:}
      credential-ref: secret://env/SKILLCENTER_OPENCLAW_TOKEN
    langfuse:
      mode: http
      endpoint: ${SKILL_CENTER_LANGFUSE_INGEST_ENDPOINT:}
      trace-endpoint: ${SKILL_CENTER_LANGFUSE_TRACE_ENDPOINT:}
      credential-ref: secret://env/SKILLCENTER_LANGFUSE_TOKEN
```

`secret://env/...` 只引用环境注入的 Secret 名称，Secret 值不得写入仓库、日志或手册；企业环境应替换为 Secret Manager resolver。Langfuse 的 `trace-endpoint` 专用于脱敏 Trace 元数据查询，不能复用包含业务正文的厂商接口。JDK HTTP transport 使用有界流读取 Provider 响应：固定长度或分块响应超过 256000 字节时立即关闭连接并返回 `UPSTREAM_RESPONSE_TOO_LARGE`，不会先用无界字符串缓冲；缺少 Secret、endpoint 非 HTTP(S)、上游超时、429、5xx、无效 JSON 或响应字段越界时，adapter 返回稳定错误并 fail-closed，不回退 Mock。adapter `UP` 只表示配置可用，不代表连接成功；管理员 connectivity probe 仅返回状态码/延迟/稳定 reason，不返回响应 body。

启用前必须完成 Provider contract、超时/失败/取消、权限、幂等、脱敏、网络出口、数据保留、全量回归和 UAT。平台生产 readiness 仍受外部证据台账约束；没有真实目标环境证据时保持 `NOT_READY`。

## M11 Operations Alert 状态持久化与多实例去重

告警状态由 `skill-center.operations.alert-state-backend` 显式选择，默认 `memory` 仅适合本地或单实例环境，并在平台 readiness 中报告 `DEGRADED/OPERATIONS_ALERT_STATE_MEMORY_ONLY`。生产多实例应切换为 `redis`，并配置 `skill-center.operations.alerts.state-key`；Redis 不可用时报告 `NOT_READY/OPERATIONS_ALERT_STATE_REDIS_UNAVAILABLE`，不会静默回退到进程内存。

Redis repository 使用单个 Lua 脚本原子读取、计算并写回每个告警键的 `active/firstTriggeredAt/lastEvaluatedAt` 状态，因此重启后仍能保持 ACTIVE 重复评估不重复通知，ACTIVE→RESOLVED 只通知一次。状态只保存告警规则键和时间/状态元数据，不保存 Prompt、输入输出、Trace 正文、凭据或上游异常。切换前需完成 Redis HA、容量、故障转移、备份恢复和多实例故障注入演练；本地 Mockito/单元测试不能替代这些目标环境验收。

## A2 Phase 1 PostgreSQL 质量证据接入（本地验证，不代表生产就绪）

默认保持 JSON，无需 datasource URL：

```yaml
skill-center:
  quality-evidence-backend: json
  persistence:
    backend: json
```

要显式启用 PostgreSQL，两个 selector 必须同时为 `postgresql`；URL、用户名和密码只从部署环境的环境变量或密钥管理系统注入，不能写入仓库、运行手册、日志或 API 响应：

```yaml
skill-center:
  quality-evidence-backend: postgresql
  persistence:
    backend: postgresql
    postgresql:
      url: ${SKILL_CENTER_POSTGRES_URL:}
      username: ${SKILL_CENTER_POSTGRES_USERNAME:}
      password: ${SKILL_CENTER_POSTGRES_PASSWORD:}
      timeout: 5s
```

启动前 Flyway 必须完成 V1，它创建 `skill_quality_evidence_state` 单例 JSONB 聚合。Repository 写入是事务性的并维护 revision；缺少/非法配置、不可连接数据库或迁移异常不得回退 JSON，必须以脱敏稳定原因码 fail-closed。先检查共享 persistence status/readiness，再允许质量证据写入；不要复制 JDBC URL、凭据或原始异常到工单。

本地验收使用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-postgres-quality-evidence.ps1`。该脚本要求 Web `node_modules` 存在，并把缺失依赖、测试失败、构建失败、缺失 Surefire XML 或 `git diff --check` 问题作为失败。Docker 不可用时，只有 `PostgresQualityEvidenceIntegrationTest` 中带 `CAPABILITY_SKIP` 的 Testcontainers PostgreSQL 跳过可以单独报告；编译、单元、focused 或 full API 失败绝不能转成跳过。

切换/回滚均需停写并经过独立审批：离线读取并完整校验 JSON，导入后比较稳定 ID、计数、版本、数据源、执行环境和门禁字段，再发布 PostgreSQL selector。回滚是恢复不变的 JSON selector，并进行离线、另行审批的数据迁移/导入比对；不是自动 fallback 或双写。A1 文件快照不会复制 PostgreSQL 质量证据，snapshot creation 和 restore-preflight 对它均为 unsupported/blocked。

仍未完成：生产数据库供应与连接池/SLO 验证、数据库一致性快照/PITR、治理/发布域关系迁移、Skill 元数据关系投影、SSO/JWT、对象存储以及备份/恢复演练。即使本地 Docker 可运行，这些测试也不证明生产就绪。

## A2 Phase 2 ReleaseRecord PostgreSQL 接入（本地验证，不代表生产就绪）

ReleaseRecord 默认仍使用 JSON：

```yaml
skill-center:
  release-backend: json
  release-storage: ./data/governance/releases.json
  persistence:
    backend: json
```

显式启用关系型发布记录时，两个 selector 必须同时为 `postgresql`：

```yaml
skill-center:
  release-backend: postgresql
  persistence:
    backend: postgresql
    postgresql:
      url: ${SKILL_CENTER_POSTGRES_URL:}
      username: ${SKILL_CENTER_POSTGRES_USERNAME:}
      password: ${SKILL_CENTER_POSTGRES_PASSWORD:}
      timeout: 5s
```

Flyway V4 创建 `release_records` 表，并使用数据库唯一约束保护发布 ID、幂等键和同一 Skill/版本/目标环境的非终态批次。`gate_snapshot` 只保存受领域模型约束的 JSONB 门禁快照；所有 SQL 参数绑定，发布记录上下文仍由 `ReleaseRecord` 构造器和服务状态机校验。PostgreSQL 连接/迁移失败、selector 不一致或 wiring 缺失时必须 fail-closed，不得回退 JSON。

`ReleaseService`、`ReleaseAdmissionService`、生命周期投影事实源和关系影响查询只依赖 `ReleaseRecordRepository`，因此审批、晋级、失败、回滚和分发准入 API 不变。A1 JSON 快照不复制 PostgreSQL `releases` artifact，snapshot creation/restore-preflight 对该后端返回 `PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED`。本地验证使用 `JdbcReleaseRecordStoreTest`；Docker 不可用时只能报告该命名 Testcontainers capability skip，不能把它当作生产数据库验收。

## A3-1 Skill 生命周期关系投影（本地 MVP，离线导入）

本阶段的 JSON `GovernanceStore`、发布、范围和关系 Store 仍是事实源。默认配置保持
`skill-center.persistence.backend=json` 与 `skill-center.lifecycle-projection.backend=json`；关系型投影不是在线主库，运行时不双写、不按请求切换，也不自动回退。

### 前置条件与显式启用

- 本地默认无需 PostgreSQL；JSON 源、A1 persistence startup gate 和现有授权回归必须先通过。
- 只有在目标环境明确批准离线投影时，才同时设置 `skill-center.persistence.backend=postgresql` 和 `skill-center.lifecycle-projection.backend=postgresql`，并提供 A2 数据库配置和 Flyway V3 readiness。两个 selector 不一致、数据库不可达、迁移版本不足或 schema gate 非 `READY` 时必须 fail-closed。
- URL、用户名、密码只通过部署密钥管理系统/环境注入；仓库配置和 verifier 不保存凭据。PostgreSQL 供应、连接池、容量与 SLO 仍是外部交付责任。
- `source-mode=offline-snapshot`、`import-mode=explicit-admin`、`startup-mode=fail-closed` 和 `allow-json-fallback=false` 是不可变安全契约标签，不是可用于在线切换的运行时开关；缺失或改成其他值必须被验证器阻断，Java 运行时也保持同样的 fail-closed/offline/explicit-admin 语义。

### 源快照、预检与导入顺序

按以下顺序执行，任何一步失败都停止后续导入：

1. 从四个已校验的 JSON 事实源生成离线 source snapshot，并计算固定字段的 lowercase SHA-256；快照不得包含 Prompt、输入输出、Trace 正文、工具参数、Token、凭据、制品路径或原始异常。
2. 由变更负责人核对 source hash、Skill/版本/发布/范围/关系计数和输入时间点，保存审批证据。
3. 使用管理员身份调用 `POST /api/v1/admin/skill-lifecycle/projection/preflight`，只读确认当前投影 hash、schema/revision 和差异原因；预检不写数据库。
4. hash 与审批证据一致后，使用同一管理员身份和 requestId 调用 `POST /api/v1/admin/skill-lifecycle/projection/import`，请求体只包含 `sourceSha256`。服务端重新读取源并再次比较 hash，过期 hash 直接拒绝。
5. 导入成功后通过 status、skills 和 impact 管理 API 复核 revision、计数、授权边界和关系影响；相同 hash 重复导入必须幂等。

管理员可通过 `GET /api/v1/admin/skill-lifecycle/projection/reconciliation` 查看可运营的一致性结果：`LIVE_SOURCE` 表示 JSON 事实源实时读取，`HEALTHY` 表示 PostgreSQL 投影 hash/五类计数一致且未超过 `max-source-age-seconds`，`DRIFTED` 表示 hash 或五类计数不一致（分别使用 `SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED` / `SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH`），`STALE` 表示投影导入时间超过新鲜度阈值，`NOT_IMPORTED`/`NOT_READY` 表示尚未完成导入或持久化门禁未就绪。`max-source-age-seconds` 只能配置在 60–86400 秒范围内。接口只返回 hash、时间、revision、计数和稳定 reason code，不执行修复。

运行运营中心管理员页面会以同一接口展示生命周期健康卡片，便于与运行量、失败 Trace 和 Provider/环境证据并行排查；卡片只读，不提供导入、切换事实源或自动修复动作。

同一 reconciliation 结果也会进入 `GET /api/v1/admin/operations/alerts`：`DRIFTED`、`STALE`、`NOT_IMPORTED` 和 `NOT_READY` 分别通过稳定 reason code 生成生命周期投影告警，`HEALTHY`/`LIVE_SOURCE` 会将对应告警恢复为 `RESOLVED`。状态迁移复用现有 Operations Alert notification sink；通知只携带 rule、reason code、状态、阈值和脱敏时间字段，不触发自动导入或修复。目标环境仍需独立配置并验收 Webhook/告警平台，不能把本地 Noop sink 视为生产通知已接通。

### 管理员授权、观测与故障定位

六条 projection API 均要求服务端解析的 admin Actor；请求不能自带 actor、owner team 或维护者列表。`requestId` 只来自 HTTP request filter。响应只返回状态、计数、稳定 ID、生命周期状态、环境和稳定错误码，不返回业务正文、凭据、路径或异常自由文本。

运维至少记录 requestId、source hash 的脱敏标识、schema/revision、计数、耗时、稳定 reason code 和审计结果；不要记录快照原文、数据库连接串或管理员凭据。失败定位先区分 source invalid、source changed、schema not ready、authorization denied 和 import failed，再检查 A1/A2 readiness 证据。

### 回滚、恢复与 capability skip 边界

本地 MVP 的回滚是停止导入并继续以 JSON 事实源运行；不得把关系投影反向写回 JSON，不提供在线数据库 restore 或自动 JSON fallback。数据库迁移回退、备份恢复、PITR、RPO/RTO 和灾备演练必须由目标环境执行并留存证据。

Docker 不可用时，Testcontainers 仅允许将 `PostgresSkillLifecycleProjectionStoreTest` 和
`PostgresQualityEvidenceIntegrationTest` 中带有 `CAPABILITY_SKIP: Docker is unavailable` 的测试报告为命名 capability skip；任意其他 skipped、failure、error、缺少或过期 Surefire 报告都必须阻断验证，不能用 skip 掩盖失败。

## A3-2 Skill 包安全门（本地验证与外部扫描接入边界）

上传校验在结构、Schema 和风险等级判断之后执行本地确定性安全扫描。扫描只读取 ZIP 条目，不联网、不执行包内容、不解压到业务目录；当前阻断禁止的二进制/可执行载荷和文本中的敏感凭据模式，并返回 `PASSED`、`BLOCKED` 或 `NOT_SCANNED`、稳定 finding code、严重度和脱敏路径。命中内容不会进入响应、日志或审计。

`GET`/上传成功响应中的 `securityStatus` 与 `securityFindings` 只用于说明本地扫描结论；`BLOCKED` 或 `NOT_SCANNED` 不得进入审核提交。扫描异常必须 fail-closed，不能自动放行或回退为 Mock 通过。当前本地扫描不覆盖恶意文件引擎、依赖漏洞数据库、许可证规则、供应链信誉和生产 SLA；目标环境仍需接入经批准的外部扫描服务，并由其提供规则版本、测试样本、故障注入、保留策略和上线验收证据。

外部扫描通过 `ExternalPackageSecurityScanner` contract 接入，生产配置只允许：

```yaml
skill-center:
  package-security:
    external:
      mode: ${SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MODE:disabled}
      endpoint: ${SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_ENDPOINT:}
      credential-ref: ${SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF:}
      connect-timeout-ms: 1000
      request-timeout-ms: 10000
      max-response-bytes: 1048576
      max-request-bytes: 20971520
      capabilities: MALWARE,SENSITIVE_INFORMATION,DEPENDENCY_VULNERABILITY,LICENSE
```

默认 `disabled` 只执行本地门；`required` 模式仍先执行本地门，本地 `BLOCKED`/`NOT_SCANNED` 直接拒绝且不调用外部服务。平台 HTTP adapter 只允许非 loopback HTTPS；loopback HTTP 仅用于本地契约测试；endpoint 禁止 userinfo/query/fragment，ZIP 请求和响应均有界。请求使用 `Authorization: Bearer`、`Content-Type: application/zip` 和 `X-Skill-Package-Sha256`，不发送 Prompt、输入输出或业务属性。响应必须符合 `package-security-scan.v1`，只允许状态、scanner provenance、四类能力和 `code/path/severity` finding 字段；原始响应、finding reason 和命中内容不会持久化或回显。

真实 scanner 必须声明并覆盖四类安全证据：`MALWARE`、`SENSITIVE_INFORMATION`、`DEPENDENCY_VULNERABILITY`、`LICENSE`。只有覆盖完整、健康为 `READY` 且返回 `PASSED` 才能通过；未配置、覆盖不完整、`CONTRACT_ONLY`、超时、异常、非法或 `NOT_SCANNED` 结果均以稳定 finding fail-closed。仓库内的 `ContractOnlyExternalPackageSecurityScanner` 不联网、不读取凭据、不伪报生产 READY；`HttpExternalPackageSecurityScanner` 只提供平台适配，不代表真实扫描服务已验收。

管理员可读取安全 readiness：

```text
GET /api/v1/admin/package-security/readiness
```

响应只返回 `mode`、`status`、`reasonCode`、scanner ID/版本、已声明/缺失能力和检查时间；健康为 `READY` 但能力不全时返回 `DEGRADED` 与 `EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE`。禁止 endpoint、credential-ref、原始供应商响应、finding reason 正文或命中内容。启用 `required` 前，目标环境必须交付真实恶意文件/敏感信息/依赖漏洞/许可证规则适配、凭据托管、规则版本、样本集、超时/故障注入、数据保留与生产扫描 SLA 的独立验收证据。

审核提交时只持久化安全状态、扫描器标识/版本和稳定 finding 摘要到治理域的 `ReviewTask`/`SkillVersion`，审核流转与离线重启会保留这份证据；历史记录归一为 `NOT_SCANNED`。管理员审核响应和 Web 队列只返回状态、扫描器 provenance 与发现数量，不返回命中原因、原始路径或秘密内容。生命周期关系投影 V3 现在也持久化这些安全元数据并纳入 source hash；旧投影迁移后必须执行一次显式 import，历史 hash 漂移属于预期对账信号。contract/gate 已具备软件化生命周期能力，但真实 scanner 适配、凭据、规则和 SLA 仍不能以本地治理证据替代。

### 尚未完成的生产交付

本地 JSON/offline projection/API/verifier 通过不代表生产就绪。仍需外部完成真实 PostgreSQL 供应、连接池与容量/SLO、SSO/JWT 与组织目录、对象存储、数据库备份/PITR 与恢复演练、全量关系域写入迁移、容量压测、UAT、上线审批和回滚演练。

## 1. 接入前检查

- 确认 OpenClaw、DeepEval、Langfuse 的服务地址、网络出口、TLS 证书和责任人。
- 为每个 Provider 创建受控密钥引用；配置中只写 `secret://...`，禁止写入 Token、Bearer、Basic、`sk-*` 或密码原文。
- 确认数据保留、脱敏、跨域传输和审计策略；外部系统不得接收 Prompt、输入输出正文、文件正文或工具参数。
- 先执行 `pwsh -NoProfile -File scripts/verify-lifecycle.ps1`，确认本地回归基线通过。

## 2. 执行环境资产目录

Agent Runtime、MCP Server 和 LLM Provider 先作为平台执行环境资产登记，再被新评测或 Runner 任务引用。管理员接口：

```text
GET   /api/v1/admin/execution-environments?kind=AGENT_RUNTIME&status=ACTIVE
POST  /api/v1/admin/execution-environments
PATCH /api/v1/admin/execution-environments/{kind}/{environmentId}/status
```

目录资产保存版本、能力、状态和可选的 `secret://...` 引用，不保存 endpoint、Token、Prompt、输入输出正文或工具参数。只有 `ACTIVE` 资产允许新任务使用；`DEGRADED` 和 `DISABLED` 仅用于历史查询和运营排查。登记资产或连接探测不等于外部 Provider 已完成真实适配，`CONTRACT_ONLY` 门禁仍然有效。

## 3. Provider 配置

通过部署密钥管理系统注入配置，不直接修改代码：

```yaml
skill-center:
  providers:
    runner: mock                 # 联调审批后切换为 openclaw
    evaluation: mock             # 联调审批后切换为 deepeval
    observability: mock          # 联调审批后切换为 langfuse
    openclaw:
      endpoint: https://openclaw.example.internal
      credential-ref: secret://skillcenter/openclaw
    deepeval:
      endpoint: https://deepeval.example.internal
      credential-ref: secret://skillcenter/deepeval
    langfuse:
      endpoint: https://langfuse.example.internal
      credential-ref: secret://skillcenter/langfuse
```

endpoint 必须是 HTTP(S) 地址，不能包含用户密码、query 或 fragment。未配置、配置错误或适配器未批准时，服务应 fail-closed。

## 4. Readiness 验证

管理员调用：

```text
GET /api/v1/admin/quality/provider-readiness
```

## 兼容性矩阵与真实联调边界

管理员可以使用以下质量证据接口验证一个 Skill 版本在受管执行环境组合上的结果：

```text
POST /api/v1/admin/quality/compatibility-matrices
GET  /api/v1/admin/quality/compatibility-matrices?skillId=&skillVersion=&dataSource=
GET  /api/v1/admin/quality/compatibility-matrices/{matrixRunId}/cases
POST /api/v1/admin/quality/compatibility-matrices/{matrixRunId}/cancel
```

矩阵最多生成 100 个组合；服务端只接受目录中 `ACTIVE` 的 Runtime、MCP Server 和 LLM Provider，并冻结版本/状态快照。`dataSource=mock` 的通过结果只证明平台编排和质量证据链路可用，不证明 OpenClaw、外部 MCP、LLM 或 Langfuse 的真实认证、协议、容量和上线能力。将矩阵标记为 `releaseGateRequired=true` 前，必须完成对应 Provider 的真实联调、脱敏、故障注入、超时取消和上线验收。

验收要点：

- `READY` 仅表示所有实际启用的 Provider 健康且没有契约缺口；`CONTRACT_ONLY` 仍应为 `PARTIAL`。
- `DEGRADED` 时检查 `notConfiguredProviderIds`。
- `PARTIAL` 时检查 `contractOnlyProviderIds`。
- `reason` 用于自动化判断，`reasonDescription` 用于运营页面；两者均不得包含密钥。
- `/api/v1/admin/quality/providers` 的 Provider ID、版本、能力和健康原因与 readiness 摘要一致。

### 3.1 连接探测（控制面诊断）

管理员可以调用：

```text
POST /api/v1/admin/quality/provider-readiness/probe
POST /api/v1/admin/quality/provider-readiness/probe?providerId=openclaw-runner
```

探测只对服务端已配置的 HTTP(S) endpoint 发起无业务正文的 GET，请求不携带 `credential-ref` 原文，响应只返回 Provider ID、状态、稳定原因码、HTTP 状态码和耗时。`SKIPPED` 表示当前使用 Mock，`NOT_CONFIGURED` 表示外部配置不完整，`REACHABLE`/`HTTP_ERROR`/`TIMEOUT`/`UNREACHABLE` 用于区分网络诊断结果。

该接口是连接性信号，不会改变 Provider readiness，也不会把 `CONTRACT_ONLY` 适配器标记为可执行；真实 Skill 执行、评测和观测联调仍需完成后续适配器门禁。

### 3.3 契约一致性校验

管理员可调用以下只读接口比较外部契约目录与当前注册适配器：

```text
GET /api/v1/admin/quality/provider-contract-verification
```

响应会区分 `MATCHED`、`MISMATCH` 和 `NOT_REGISTERED`，并返回稳定的版本/能力差异；`CONTRACT_ONLY` 的匹配结果仍以 `PROVIDER_CONTRACT_MATCHED_NOT_ENABLED` 标识，不代表真实执行 readiness。该接口不发起网络请求、不改变 Provider 选择，也不返回 endpoint、credential-ref、Prompt 或输入输出正文。

### 3.2 优化实验发布门禁

候选版本如果存在优化实验，发布审核还必须满足实验决策门禁：

- `OPTIMIZATION_EXPERIMENT_INCOMPLETE`：仍有排队或运行中的实验；
- `OPTIMIZATION_EXPERIMENT_FAILED` / `OPTIMIZATION_EXPERIMENT_CANCELLED`：最新实验未形成可发布证据；
- `OPTIMIZATION_DECISION_REQUIRED`：实验完成但尚未生成决策快照；
- `OPTIMIZATION_DECISION_BLOCKED`：决策为迭代、拒绝或不可比较。

只有 `PROMOTE_CANDIDATE` 才能进入后续质量快照、兼容性矩阵和人工 Review 门禁。该规则只读判断，不自动发布、回滚或推进优化工作项；没有优化实验的历史版本保持原有发布兼容行为。

### 3.4 发布后运行观察

候选版本发布后，管理员可以按窗口采集脱敏运行聚合，形成优化实验的后发布证据：

```text
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/observations
POST /api/v1/admin/quality/optimization-experiments/{experimentId}/observations
     body: { "window": "24h" }
```

接口仅允许 `COMPLETED + PROMOTE_CANDIDATE` 且曾经具有 `publishedAt` 的实验；`NO_TRAFFIC` 只表示窗口内没有样本，不代表成功或失败。观察记录追加保存并写入 `OPTIMIZATION_EXPERIMENT_POST_RELEASE_OBSERVED` 审计事件，不自动回滚、降级 Provider 或修改 Skill 发布状态。观察指标只包含调用计数、成功率、P95、错误类别聚合和执行环境标识，不包含业务正文。

### 3.5 发布后效果评估与人工处置

管理员可基于已采集观察重建同窗口的源版本基线，并保存不可变评估证据：

```text
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments/{assessmentId}
POST /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments
     body: { "observationId": "observation-...", "action": "KEEP|CREATE_FOLLOW_UP|ROLLBACK_REVIEW", "note": "" }
```

评估固定记录源版本/候选版本、窗口、数据源、Runtime/MCP/LLM、套件版本、候选与基线指标、阈值、结论和推荐动作。`HEALTHY` 推荐保留，`REGRESSION` 推荐进入回滚评审，`INSUFFICIENT_TRAFFIC` 推荐继续观察，`INCONCLUSIVE` 推荐创建后续工作项；人工动作仅产生审计证据，不会自动回滚、发布、切换 Provider 或完成工作项。观察和评估记录均由统一 RetentionService 按调用保留期清理，清理数量计入质量证据总量。

### 3.6 受控发布晋级与回滚评审

发布批次独立于 `SkillVersion.status`，通过以下控制面接口推进：

```text
GET  /api/v1/admin/releases?skillId=&version=&targetEnvironment=&status=
POST /api/v1/admin/releases
GET  /api/v1/admin/releases/{releaseId}
POST /api/v1/admin/releases/{releaseId}/approve
POST /api/v1/admin/releases/{releaseId}/reject
POST /api/v1/admin/releases/{releaseId}/promote
POST /api/v1/admin/releases/{releaseId}/rollback-review
POST /api/v1/admin/releases/{releaseId}/rollback
```

发布请求会冻结制品哈希、目标环境、门禁快照、幂等键和可选评估 ID；`PRODUCTION` 不接受 `NO_EVIDENCE`。`reviewer` 只能审批 STAGING，PRODUCTION 审批、拒绝、晋级和回滚均由 `admin` 执行；请求者不能审批自己的批次。目标执行失败或服务重启发现 `PROMOTING/ROLLING_BACK` 时，状态进入 `FAILED`，不得猜测外部系统是否成功。

本地默认目标是确定性 Mock：普通 release ID 返回稳定外部引用，包含 `fail` 的 fixture 返回 `MOCK_TARGET_FAILED`。平台现已提供显式 `skill-center.release-target.mode=http` 的 `HttpReleaseTarget`：请求使用 `release-target.v1` 元数据契约，只发送 action、releaseId、skillId、version、SHA-256 和目标环境；响应只接受 `SUCCEEDED`/`FAILED`、受界限外部引用和稳定 reason code。JDK HTTP transport 对响应使用 256000 字节有界流读取，超时、429、5xx、超大响应和未知字段均 fail-closed，Secret 仅通过 `secret://` 引用解析。默认 `mock` 不变，HTTP 配置缺失时不会回退 Mock。真实 OpenClaw、MCP、LLM 或 CD/Kubernetes endpoint、Secret Manager、认证、故障注入、容量和上线验收仍需目标环境完成；适配器不传输 Prompt、输入输出正文、工具参数或凭据。

发布目标连通性证据通过管理员控制面显式触发，不在查询 readiness 时自动访问外部网络：

```text
POST /api/v1/admin/platform/release-target/probe
GET /api/v1/admin/platform/release-target/probes?limit=20
```

HTTP 模式探测只向配置的目标发送带 `Authorization: Bearer` 和 `X-Skill-Center-Release-Target-Probe: v1` 的状态型 `GET`，丢弃响应正文，不执行发布、晋级或回滚。响应只返回 `targetId`、`status`、稳定 `reasonCode`、HTTP 状态、耗时和检查时间；`endpoint`、`credential-ref`、Token、响应正文和上游异常均不得出现。`mock` 返回 `SKIPPED`，未配置、超时、不可达和非 2xx 分别保持 `NOT_CONFIGURED`、`TIMEOUT`、`UNREACHABLE` 或 `HTTP_ERROR/FAILED`。最近一次安全审计证据按当前 mode/endpoint/credential-ref 的 SHA-256 指纹恢复，并受默认 300 秒 TTL 约束；过期证据返回 `STALE/RELEASE_TARGET_PROBE_EXPIRED`，不会继续维持 readiness。只有未过期的 `REACHABLE` 探测会使平台 readiness 的 `RELEASE_TARGET` 组件为 `READY`，其余状态均 fail-closed 阻断 PRODUCTION 发布；真实目标、Secret Manager、网络出口和故障注入仍需目标环境验收。

历史接口只读取治理域中 action 为 `RELEASE_TARGET_CONNECTIVITY_PROBED` 且匹配当前安全配置指纹的审计记录，默认返回最近 20 条、最多 100 条；服务端再次执行状态、原因码、耗时和 HTTP 状态边界校验并按检查时间倒序排列。运营中心据此展示最近探测次数、失败/超时数量和脱敏明细，不把历史记录当作新的 readiness 证据，也不执行网络探测。

生产发布请求和晋级还必须通过统一平台准入 gate：`ReleaseService` 在 `targetEnvironment=PRODUCTION` 且质量门禁通过后读取 `PlatformReadinessService`。只有整体状态为 `READY` 才会创建 PRODUCTION 发布记录，且晋级前会再次检查当前 readiness；`DEGRADED`、`NOT_READY`、gate 异常或 readiness 缺失均以 `QUALITY_GATE_BLOCKED` 和稳定 `PLATFORM_PRODUCTION_READINESS_*` / 组件 reason code 返回，不创建发布记录、不改变已审批批次状态，也不会调用 ReleaseTarget。STAGING 不受该 gate 影响。管理员可先查看：

```text
GET /api/v1/admin/platform/readiness
```

该 gate 当前会明确阻断真实外部证据尚未核验的环境；本地 Mock/JSON 通过不能绕过生产发布准入。

生产外部证据通过管理员台账维护，接口只处理安全元数据：

```text
GET /api/v1/admin/platform/evidence
PUT /api/v1/admin/platform/evidence/{evidenceId}
```

固定证据项为 `BACKUP_PITR`、`DATABASE_CAPACITY_SLO`、`OBJECT_STORAGE`、`PROVIDER_SECURITY`、`REDIS_HA`、`RELEASE_APPROVAL`、`ROLLBACK_DRILL`、`SLO_UAT` 和 `SSO_ORGANIZATION`。更新使用 `expectedRevision` 乐观并发控制并写入审计；`evidenceRef` 只能是脱敏不透明引用，禁止 URL、凭据、报告正文和原始供应商响应。只有九项全部为未过期的 `ACCEPTED`，平台外部证据组件才会变为 `READY`；缺失、提交中、拒绝、过期、存储异常均保持 fail-closed。台账本身纳入 persistence artifact catalog，但本地台账状态不等于真实外部系统验收。

### 3.7 审核完成与分发准入迁移

最终审核通过后，平台使用同一次质量门禁快照自动创建一个幂等的 STAGING `REQUESTED` 批次，幂等键为 `review:{reviewId}:staging`。该登记不会自动审批、晋级或调用真实部署目标；登记失败只产生 `RELEASE_STAGING_ENROLLMENT_FAILED` 审计事件，审核结果不回滚。

分发准入配置：

```yaml
skill-center:
  release-admission-mode: LEGACY_COMPATIBLE
```

迁移阶段使用 `LEGACY_COMPATIBLE`：没有 PRODUCTION 发布记录的历史版本继续按既有 `published/deprecated` 语义分发；一旦某版本已有 PRODUCTION 批次，则必须存在同一 Skill、版本和 SHA-256 的 `PROMOTED` 记录。完成历史版本基线登记、生产批次核对和回归验收后，切换为 `CONTROLLED`，此时没有匹配生产晋级的版本统一返回 `RELEASE_ADMISSION_REQUIRED`、`RELEASE_NOT_PROMOTED` 或 `RELEASE_ARTIFACT_MISMATCH` 并 fail-closed。

管理员可通过只读接口核对当前版本准入结论：

```text
GET /api/v1/admin/releases/admission?skillId=&version=
```

该接口只返回模式、允许/阻断、稳定原因码和发布批次 ID，不返回 Prompt、输入输出、Trace、工具参数、凭据或 Provider 异常正文。切回 `LEGACY_COMPATIBLE` 只适用于迁移故障缓解，仍需记录变更审批和后续补齐生产基线。

### 3.8 Skill 版本关系迁移与影响分析

版本关系使用独立的 `SkillRelationStore` 保存，关系两端必须绑定具体 `skillId + version`。历史版本没有关系记录时返回空影响集，不影响既有审核、发布、安装和下载；关系覆盖率在本阶段只作为可观测事实，不自动改变发布准入或下架结果。

迁移时先补录 `DEPENDS_ON`、`COMPOSES` 和 `REPLACES` 关系，验证两端版本存在、关系键不重复且图中无环，再通过以下只读接口检查目标版本的反向影响：

```text
GET /api/v1/admin/skill-relations/impact?skillId=&version=&maxDepth=&maxNodes=
```

管理员在生命周期操作弹窗中可查看受影响下游版本、生命周期状态、生产晋级事实和活跃安装数量。关系撤销保留历史记录；达到关系覆盖率和人工处置成熟度前，不将影响结果接入自动发布门禁。

### 3.9 Skill 可见范围与维护资格迁移

Skill 范围由独立 `SkillScopeStore` 保存，管理员通过以下接口显式创建或更新：

```text
GET /api/v1/admin/skill-access/scopes?skillId=
PUT /api/v1/admin/skill-access/scopes/{skillId}
```

迁移规则如下：

- 没有显式范围记录的历史 Skill 按 `PUBLIC` 处理；最新非下架版本的 `uploadedBy` 只作为内部维护/提交回退，不写入或暴露为范围记录。
- 首次创建 Skill 允许有效 `developer/maintainer` 或 `admin` 提交；后续版本必须通过统一授权服务的维护资格判定。首次显式范围创建使用 `revision=0` 请求并落盘为 `revision=1`。
- `TEAM` 必须引用活动团队；JWT Actor 的 `team-claim` 必须命中该活动团队，缺失/空 claim 不得回退本地成员表；local Actor 继续使用本地成员与 active RoleBinding。`RESTRICTED` 必须至少有一个显式维护者。范围更新只改变可见/维护判定，不改变版本、质量门禁、发布批次、制品或 Token 状态。
- 目录、详情、内容、审核、生命周期、关系、安装授权和下载均在服务端重新校验范围；Web 隐藏字段不是安全边界。不可见资源统一返回稳定不可见/不存在语义，不回显 owner、团队成员或维护者详情。

真实 SSO/JWT、组织目录同步、跨团队审核映射和机器凭证仍由目标部署环境负责；本地 `ActorResolver` 仅用于契约和回归验证。切换到外部身份源时必须保持 `SkillAuthorizationService` 的输入语义与 fail-closed 行为不变。

## 5. 分阶段联调门禁

每个 Provider 依次完成：

1. 健康检查、超时、取消、失败和错误码映射；
2. 幂等、有限重试、关联 ID 和数据来源标记；
3. 脱敏扫描，确认响应、Trace、质量快照和运行摘要不含业务正文或凭据；
4. 真实流量容量、故障注入和回滚演练；
5. Web/API 全量回归与业务、安全、运维签署。

任何一项未通过时保持 `CONTRACT_ONLY`，不切换生产流量。

## 6. Redis 运行摘要切换

Redis 仅在目标环境完成高可用、容量、故障转移、备份恢复和多实例一致性演练后启用：

```yaml
skill-center:
  runtime-summary-backend: redis
  runtime-summary-redis-key: skill-center:runtime:summaries
```

切换前后必须核对运行摘要幂等、Trace 查询、保留期预览/执行和服务重启恢复；Redis 不可用时不得静默丢弃或重复计数。

Redis 运行摘要写入通过单次 Lua 脚本原子完成事件 Hash 与时间索引 ZSet 写入，事件 ID 冲突仍按完整内容判断重复或冲突；不能以先写 Hash、再写索引的非原子实现替代。平台 readiness 会在显式选择 Redis 后执行 metadata-only PING，返回 `RUNTIME_SUMMARY_REDIS_READY` 或 `RUNTIME_SUMMARY_REDIS_UNAVAILABLE`，不会暴露连接信息，也不会在 Redis 不可用时回退到 JSON。默认 JSON 模式会显示 `RUNTIME_SUMMARY_JSON_ONLY` 降级状态。

## 7. 回滚

- Provider：将对应 `providers.runner/evaluation/observability` 恢复为 `mock`，确认 readiness 和数据来源标记后再恢复服务流量。
- Redis：切回 `runtime-summary-backend: json` 前先停止写入、导出并校验摘要，禁止直接覆盖 JSON 文件。
- 回滚后执行统一生命周期门禁，并记录变更单、requestId、审计事件和影响范围。
