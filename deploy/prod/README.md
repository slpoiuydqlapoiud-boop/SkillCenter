# SkillCenter 生产环境部署指南

> 适用于目标生产集群。本目录**不**包含凭据明文，只承载模板与流程。

## 0. 推荐优先级（与生产证据 ID 对齐）

本项目所有生产上线门禁由 `verify-production-handoff.ps1` 汇总，共 9 个证据 ID，每项需在外部系统被目标环境验收为 `ACCEPTED` 才视为 `READY`：

| 优先级 | 证据 ID | 本目录交付物 |
|---|---|---|
| **P0 上线硬门槛** | `SSO_ORGANIZATION` | 第 9、10 节 + [`sso-integration.md`](./sso-integration.md)（含 §8 自检清单 20 项） |
| **P0 上线硬门槛** | `PROVIDER_SECURITY` | 第 5 节 + [`security-scanner.md`](./security-scanner.md)（含 §9 自检清单 20 项） |
| **P0 上线硬门槛** | `REDIS_HA` | 第 2 节 + [`redis-ha.md`](./redis-ha.md)（含 §8 自检清单 20 项） |
| **P0 上线硬门槛** | `OBJECT_STORAGE` | 第 4 节 + [`object-storage.md`](./object-storage.md)（含 §12 自检清单 20 项，联动 BACKUP_PITR） |
| **P0 业务能力** | `PROVIDER_RUNTIME_GATEWAY` + `LLM_PROVIDER` | 第 6 节 + [`provider-integration.md`](./provider-integration.md)（含 §10 自检清单 20 项） |
| P1 | `BACKUP_PITR` | 第 12 节 + [`docs/project/backup-pitr-runbook.md`](../../docs/project/backup-pitr-runbook.md)（含 §9 自检清单 20 项）|
| P1 | `DATABASE_CAPACITY_SLO` | 见 `scripts/slo-smoke.mjs` + [`docs/project/database-capacity-slo-runbook.md`](../../docs/project/database-capacity-slo-runbook.md)（含 §8 自检清单 20 项）|
| P1 | `RELEASE_APPROVAL` | 第 7 节 + [`docs/project/release-approval-runbook.md`](../../docs/project/release-approval-runbook.md)（含 §6 自检清单 20 项）|
| P1 | `SLO_UAT` | 联合 `docs/project/slo-evidence-runbook.md` + [`docs/project/slo-uat-runbook.md`](../../docs/project/slo-uat-runbook.md)（含 §8 自检清单 20 项）|
| P1 | `ROLLBACK_DRILL` | 见 `M11-external-integration-runbook.md` + [`docs/project/rollback-drill-runbook.md`](../../docs/project/rollback-drill-runbook.md)（含 §7 自检清单 20 项）|

> 本目录**不**取代 M11 外部 Provider 联调运行手册与 slo-evidence-runbook；
> 本目录的职责是**单一事实来源的环境变量与 Secret Manager 引用模板**。

## 1. 文件与目录

```
deploy/prod/
├── README.md                       ← 本文件
├── .env.prod.template              ← 必须用本文件作起点，禁止直接照抄本地
├── sso-integration.md              ← SSO/JWT/JWKS/组织目录生产接入指南（P0：SSO_ORGANIZATION）
├── security-scanner.md             ← 外部安全扫描网关生产接入指南（P0：PROVIDER_SECURITY）
├── redis-ha.md                     ← Redis HA 生产接入指南（P0：REDIS_HA）
├── provider-integration.md         ← OpenClaw / DeepEval / Langfuse Provider 生产接入指南（P0：PROVIDER_RUNTIME_GATEWAY + LLM_PROVIDER）
├── object-storage.md               ← 对象存储 + PIT 备份 生产接入指南（P0：OBJECT_STORAGE + BACKUP_PITR）
└── secret-references.md            ← Secret Manager 命名约定

apps/api/src/main/resources/
└── application-prod.yml.example    ← Spring 生产 profile 配置示例（不进 git，构建时由部署平台覆盖）
```

实际部署时复制模板为具体集群名（例 `.env.prod.cluster-a`），该副本**不进 git**。

## 2. 部署流程（5 步）

### Step 1 — 复制并填写环境变量

```bash
cp deploy/prod/.env.prod.template deploy/prod/.env.prod.<cluster>
$EDITOR deploy/prod/.env.prod.<cluster>
```

替换规则：
- `CHANGE-ME.*` 域名 → 实际企业域名（公网或内网）
- 所有 `*_REF=secret://CHANGE-ME/...` → 见 `secret-references.md` 的命名约定
- 所有 `https://` URL 必须落在企业网络白名单（VPN / VPC / 专线）

### Step 2 — 凭据全部走 Secret Manager，禁止明文

`ProductionConfigCheck.psm1` 对以下三类做格式校验：
- `https-uri` — 必须是 `https://`，禁止内嵌 `user:pass@`
- `secret-ref` — 必须是 `secret://<key-path>` 格式，禁止明文
- `postgres-url` — `jdbc:postgresql://...` 不允许带 `?user=` / `?password=`

任一校验失败 → `verify-production-config.ps1` 返回 `NOT_READY` → **禁止部署**。

### Step 3 — 注入并启动

```bash
# 由部署平台（K8s/VM 编排）读取 .env.prod.<cluster> 与 Secret Manager，
# 把 secret://... 解析为真实值后注入 API/Web 进程环境变量。
# 推荐：K8s ExternalSecret + ESO (External Secrets Operator) 自动同步。
```

### Step 4 — 本地配置预检

```bash
pwsh scripts/verify-production-config.ps1 -Json
# 期望："passed": true, "ready": "<N>/<N>"
```

### Step 5 — 上线前证据汇总

```bash
pwsh scripts/verify-production-handoff.ps1 \
  -BaseUrl https://<api-host> \
  -Json
# 期望：9 个证据 ID 全部 READY 或 ACCEPTED
```

任一证据缺失/过期/拒绝 → **fail-closed**，平台不允许 `RELEASE_ADMISSION_MODE=PROD`。

## 3. Secret Manager 命名约定（要点，详细在 secret-references.md）

| 资源类型 | 路径前缀 | 示例 |
|---|---|---|
| PostgreSQL | `secret://skillcenter/prod/postgres/` | `secret://skillcenter/prod/postgres/password` |
| Redis | `secret://skillcenter/prod/redis/` | `secret://skillcenter/prod/redis/auth` |
| 对象存储 | `secret://skillcenter/prod/object-storage/` | `secret://skillcenter/prod/object-storage/access-key` |
| 安全扫描 | `secret://skillcenter/prod/security-scanner/` | `secret://skillcenter/prod/security-scanner/token` |
| OpenClaw | `secret://skillcenter/prod/openclaw/` | `secret://skillcenter/prod/openclaw/token` |
| DeepEval | `secret://skillcenter/prod/deepeval/` | `secret://skillcenter/prod/deepeval/token` |
| Langfuse | `secret://skillcenter/prod/langfuse/` | `secret://skillcenter/prod/langfuse/token` |
| 组织目录 | `secret://skillcenter/prod/org-directory/` | `secret://skillcenter/prod/org-directory/token` |
| 发布目标 | `secret://skillcenter/prod/release-target/` | `secret://skillcenter/prod/release-target/token` |

## 4. 备份与灾备（P1：BACKUP_PITR）

- **PostgreSQL**：启用云厂商 PITR（如 AWS RDS / 阿里云 RDS / 自建 WAL-G）；
  WAL 归档到独立对象存储桶 `skillcenter-prod-pitr`；保留窗口 ≥ 7 天。
- **对象存储**：跨区复制（CRR）到灾备 Region，开启版本控制 + 对象锁。
- **配置/审计**：本目录的 `.env.prod.<cluster>` 副本与 Secret Manager 内容
  纳入版本控制 + 季度备份；轮转记录写入审计日志。
- **恢复演练**：每季度至少一次全量恢复演练，证据写入 `ROLLBACK_DRILL`。
  本目录不直接承载演练脚本，引用 `docs/project/M11-*` 系列。

## 5. 网络出口与白名单

API/Web 进程出口到下列域名时必须经过企业网关/VPC 专线：

```
*.sso.prod.internal          SSO / JWKS
*.org-directory.prod.internal 组织目录
*.openclaw-runtime.prod.internal OpenClaw Agent Runtime
*.deepeval.prod.internal     DeepEval
*.langfuse.prod.internal     Langfuse
*.security-scanner.prod.internal 安全扫描
*.s3.prod.internal           对象存储
*.opensearch.prod.internal   OpenSearch
*.release-target.prod.internal 发布目标
*.alerts.prod.internal       告警通知
```

DNS 解析由企业内 DNS 提供，公网域名（如 `.example.com`）走 CDN/egress 代理。

## 6. 与现有脚本的关系

| 脚本 | 作用 |
|---|---|
| `scripts/verify-production-config.ps1` | 校验本目录模板导出的环境变量 |
| `scripts/verify-production-handoff.ps1` | 校验 9 个证据 ID |
| `scripts/slo-smoke.mjs` | 数据库容量 SLO 冒烟（DATABASE_CAPACITY_SLO） |
| `scripts/ProductionConfigCheck.psm1` | 配置校验规则的单一来源 |

本目录的 `.env.prod.template` 必须与 `ProductionConfigCheck.psm1` 中的
`$script:ProductionConfigDefinitions` 保持一一对应；任何新增/删除环境变量
必须同时改两处，否则校验会漂移。

## 7. SSO / 组织目录接入（**P0：SSO_ORGANIZATION**）

完整的接入流程（IdP 选择、契约对齐、密钥轮转、应急撤销、20 项证据自检清单）见：

→ **[`sso-integration.md`](./sso-integration.md)**

关键要点速览：

- 适配层已 100% 实现：`ActorAuthenticationProperties`、`JwksKeySetProvider`、`HttpOrganizationDirectoryClient`、`OrganizationDirectorySyncService`
- 生产强制 `mode=jwt`，JWKS 走 HTTPS；**禁止**同时配置 `jwks-uri` 和 `public-key`
- Token 仅接受 RS256（RSA-2048+），角色收敛到 `developer / admin / viewer / maintainer / reviewer`
- 组织目录响应必须为 `organization-directory.v1`，严格字段 allowlist
- 凭据全部走 Secret Manager（`secret://skillcenter/prod/org-directory/token` 等）
- 上线前 UAT 必须完成 `sso-integration.md` §8 的 20 项自检，证据归档到 `docs/operations/sso-uat-evidence-YYYYMMDD/SSO_ORGANIZATION-{01..20}.md`

## 7b. 外部安全扫描网关接入（**P0：PROVIDER_SECURITY**）

完整的接入流程（HTTP 契约、四类能力全集、凭据注入两种模式、应急降级、20 项证据自检清单）见：

→ **[`security-scanner.md`](./security-scanner.md)**

关键要点速览：

- 适配层已 100% 实现：`HttpExternalPackageSecurityScanner`、`PackageSecurityScanCoordinator`、`PackageSecurityReadinessController`
- 生产强制 `mode=required`；扫描不可用/能力不全/响应非法 → 上传拒绝（fail-closed）
- 四类能力必须齐全：`MALWARE / SENSITIVE_INFORMATION / DEPENDENCY_VULNERABILITY / LICENSE`
- 响应 schema 必须为 `package-security-scan.v1`，字段 allowlist 严格
- 健康探针：`GET /api/v1/admin/package-security/readiness`（admin only）
- 凭据注入两条路径：默认 Bean `secret://env/<NAME>`，生产推荐替换 `ProviderCredentialResolver` Bean
- 上线前 UAT 必须完成 `security-scanner.md` §9 的 20 项自检，证据归档到 `docs/operations/security-scanner-uat-evidence-YYYYMMDD/PROVIDER_SECURITY-{01..20}.md`

## 7c. Redis HA 接入（**P0：REDIS_HA**）

完整的接入流程（Sentinel/Cluster 选型、生产配置、fail-over 演练、灾备 Runbook、20 项证据自检清单）见：

→ **[`redis-ha.md`](./redis-ha.md)**

关键要点速览：

- 适配层 5 个 Redis 用户已 100% 接入 Spring Data Redis（Lettuce 客户端）：`RedisRuntimeSummaryStore` / `RedisOperationsAlertStateRepository` / `RedisOperationsMetricsStore` / `RedisResumableUploadMetadataStore` / `RedisSkillSearchRefreshEventBus`
- **推荐生产拓扑**：Redis Sentinel（3 sentinel + 1 master + 2 replica）；Lettuce 自动 fail-over 5-15s
- Cluster 模式仅大数据量场景；必须用 hash tag 隔离 Streams key（当前代码未改，Cluster 需评估改造成本）
- 配置：`spring.data.redis.sentinel.*` + Lettuce pool；TLS 强制开启
- 健康探针：`/actuator/health/redis`（自动）+ `/api/v1/admin/platform/resumable-uploads/readiness`（admin only，含 Redis PING）
- 凭据走 Secret Manager：推荐 `secret://skillcenter/prod/redis/password`（替换 Bean 后）或应急 `secret://env/SKILL_CENTER_REDIS_PASSWORD`
- **季度必做演练**：手动 kill master → 验证平台自动 fail-over，证据写入 `BACKUP_PITR` / `REDIS_HA` 联合归档
- 上线前 UAT 必须完成 `redis-ha.md` §8 的 20 项自检，证据归档到 `docs/operations/redis-ha-uat-evidence-YYYYMMDD/REDIS_HA-{01..20}.md`

## 7d. Provider 真实联调接入（**P0：PROVIDER_RUNTIME_GATEWAY + LLM_PROVIDER**）

完整的接入流程（OpenClaw / DeepEval / Langfuse HTTP 契约、凭据两种注入模式、连接性探针与调度、错误码映射、故障场景速查、20 项证据自检清单）见：

→ **[`provider-integration.md`](./provider-integration.md)**

关键要点速览：

- 适配层 4 个 Bean 已 100% 实现：`OpenClawRunnerAdapter` / `DeepEvalEvaluationAdapter` / `LangfuseObservabilityAdapter` / `LangfuseTraceProviderAdapter`
- 生产强制 `*_MODE=http` 全部启真实 HTTP 适配；`mock`/本地 fallback **禁止**进入生产
- OpenClaw 请求必传 10 字段（含 `evaluationRunId/suiteId/caseId/scenario/runtimeId/mcpServerId/llmProviderId`），响应 `durationMs ∈ [0,120000]`、`providerVersion` 1-128 字符；adapter 30s 超时
- DeepEval 请求**不**传 raw prompt/completion（仅传哈希 + 元数据），响应 `score ∈ [0,100]`、`reason` 无控制字符；adapter 30s 超时
- Langfuse 两个端点完全独立：observability（10s 超时）与 trace（10s 超时），trace 响应**必须**JSON array + 13 字段白名单（防止 `rawPrompt/userEmail/authToken` 等越界字段）
- 凭据注入两条路径：① 默认 Bean `secret://env/<NAME>`（贴近模板），② **生产推荐替换** `ProviderCredentialResolver` Bean 为 Secret Manager 实现，支持热轮转
- 连接性探针三件套：`PROVIDERS_PROBE_SCHEDULER_ENABLED=true`（60s/次，静默不写审计）+ `PROVIDERS_PROBE_TTL_SECONDS=300`（过期标 STALE）+ admin `/api/v1/admin/provider-probe` 显式触发写审计
- 5 类错误码映射（429/5xx/解析失败/不可达/4xx）由 `ProviderUnavailableException` 抛出，**禁止** adapter 吞异常降级到 mock——fail-closed
- 上线前 UAT 必须完成 `provider-integration.md` §10 的 20 项自检，证据归档到 `docs/operations/provider-uat-evidence-YYYYMMDD/PROVIDER_RUNTIME_GATEWAY-{01..15}.md` + `LLM_PROVIDER-{16..20}.md`

## 7e. 对象存储 + PIT 备份接入（**P0：OBJECT_STORAGE + P1：BACKUP_PITR**）

完整的接入流程（S3 兼容契约、手工 SigV4 签名、完整性双重校验、CRR 跨区复制、PITR 备份链、11 类故障场景、20 项证据自检清单）见：

→ **[`object-storage.md`](./object-storage.md)**

关键要点速览：

- 适配层已 100% 实现：`S3CompatibleArtifactStorage`（**无 AWS SDK 依赖**，手工 SigV4 签名，30s 超时）
- 数据完整性**三重**校验：HEAD `x-amz-meta-sha256` + content-length + GET 后本地 SHA-256 + ZIP CRC
- **强幂等**：所有 PUT 用 `If-None-Match: *`，重复上传返回 409/412 走"已存在"分支（避免覆盖）
- reference URI 强约束：`s3://<bucket>/[prefix/]sha256/<64hex>.zip`，任何 `query`/`fragment`/`..` 越权 → fail-closed
- 凭据两条路径：① 默认 `EnvironmentObjectStorageCredentialResolver`（贴近默认 Bean），② **生产推荐替换** Bean 为 Secret Manager 实现（支持热轮转）
- 连接性探针三件套：`PROBE_SCHEDULER_ENABLED=true`（60s/次，静默不写审计）+ `PROBE_TTL_SECONDS=300`（过期标 STALE）+ admin 显式触发写审计
- **CRR 跨区复制**强烈推荐：主区 `cn-north-1` → 备区 `cn-east-2`，bucket 版本控制 + SSE-KMS 强制开启（**§7.2 切换 Runbook** 内置）
- **PITR 备份链**联动 PostgreSQL / OpenSearch：basebackup + WAL archive + Opensearch snapshot 落同一 bucket 的 `backups/{pg,os}/YYYY-MM-DD/`
- 11 类故障场景速查含 SigV4 scope 错（region 错）/ If-None-Match 禁用 / ZIP CRC 错 / CRR 滞后等真实运维场景
- 上线前 UAT 必须完成 `object-storage.md` §12 的 20 项自检：O-01..15（OBJECT_STORAGE 主）+ B-01..05（BACKUP_PITR 联动）；证据归档到 `docs/operations/object-storage-uat-evidence-YYYYMMDD/`

## 8. Spring 生产 Profile

## 8a. Huawei CCE + CSMS 部署资产

仓库已提供 `deploy/k8s/skillcenter/` Helm Chart，面向华为云 CCE 使用原生
DEW/CSMS 密钥管理插件和 Workload Identity。该实现不伪造 ESO 的华为云 Provider，
也不把 CSMS 值、AK/SK 或真实生产地址写入 Git。

部署前提、镜像构建、`SecretProviderClass` 映射、轮转和 Helm 命令见：

→ **[`../k8s/skillcenter/README.md`](../k8s/skillcenter/README.md)**

模板中的 `values-prod.example.yaml` 只可作为结构示例；实际集群应由部署平台
提供独立 values 文件，并在同一环境执行 `verify-production-config.ps1`。

`apps/api/src/main/resources/application-prod.yml.example` 是 Spring 生产 profile 的配置示例（SSO/JWT/JWKS/Org Directory + Provider + Object Storage + Search + Alert + Logging/Actuator）。

**使用方式**：
- **不**把 `application-prod.yml` 打包进镜像；`.gitignore` 已屏蔽，保留 `.example`
- 实际生产配置通过以下任一方式注入：
  - K8s ConfigMap/Secret + `SPRING_CONFIG_ADDITIONAL_LOCATION`
  - Spring Cloud Config / Nacos / Apollo 配置中心
  - 环境变量直接覆盖（与 `.env.prod.template` 第 1-12 节配套）
- 启动时启用 prod profile：`SPRING_PROFILES_ACTIVE=prod`
# 历史参考：不属于当前部门版默认部署

当前部门版不要求本目录中的 PostgreSQL、Redis HA、对象存储、SSO/JWKS、Secret Manager、外部扫描和生产交付证据。部门级 Windows 本地部署请从 `deploy/local/README.md` 开始。
