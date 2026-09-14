# SkillCenter 对象存储 + PIT 备份 生产接入指南

> **证据 ID**：OBJECT_STORAGE（P0 上线硬门槛）+ BACKUP_PITR（P1）
> **适配层**：`apps/api/src/main/java/com/huawei/skillcenter/distribution/`
> **本文件不修改代码**——适配层已 100% 实现并通过测试，本文产出生产环境接入的契约、配置矩阵、应急与上线 Checklist。

---

## §1. 适用范围

`apps/api` 的所有"持久化字节数据"通过一组对象存储抽象落地：
- 发布的 Skill 制品包（ZIP）及其内容
- 上传断点续传会话的临时对象
- PIT 备份的快照基线（WAL 段、增量文件、`manifest.json`）

生产环境强制走 **S3 兼容 HTTP 适配器**（`S3CompatibleArtifactStorage`），无任何本地或 contract fallback。本地开发可用 `local` 后端或 MinIO 容器（见 `deploy/local/compose.yaml`）。

---

## §2. 制品存储使用面（5 类）

| 用途 | 调用点 | 存储原语 |
|---|---|---|
| 1. 发布 Skill 包（ZIP） | `ArtifactStorage.store(Path, packageId)` | PUT + `if-none-match=*` 幂等 |
| 2. 分块上传临时对象 | `S3ObjectClient.putIfAbsent(key, bytes)` | PUT + `if-none-match=*` |
| 3. 客户端下载 | `ArtifactStorage.open(reference, sha256)` | GET + 完整性校验 |
| 4. 元数据查询 | `ArtifactStorage.inspect(reference, sha256)` | HEAD + content-length |
| 5. 删除/重写 | `ArtifactStorage.delete(key)` | DELETE（404 视为成功）|

**关键约束**：
- 文件路径不接受符号链接（防越权）
- ZIP 必须在 GET 时**完整流式解压**，捕获隐藏的 CRC 错误
- HEAD/GET 头部必须含 `x-amz-meta-sha256=<digest>`，**双重校验**

---

## §3. S3 兼容契约（手工 SigV4，**无 AWS SDK 依赖**）

`S3CompatibleArtifactStorage` 仅依赖标准 JDK（`java.net.http.HttpClient`），自行按 AWS SigV4 规范计算 `Authorization` 头。

### §3.1 端点对象 storage

```
{endpoint}/{bucket}/{[prefix]/}sha256/<sha256>.zip
```

- `endpoint` — HTTPS，无 `user-info` / `query` / `fragment`
- `bucket` — 1-63 字符，小写字母数字 + `. -`
- `prefix` — 仅 `[A-Za-z0-9._/-]`，禁止 `..` 越权
- `sha256/.zip` — 强制命名（`ObjectStorageConfig.objectKey()` 内置）

### §3.2 reference URI

```
s3://<bucket>/[prefix/]sha256/<sha256>.zip
```

- 仅接受 `s3://` scheme
- bucket 必须匹配当前 config
- 任何 `query` / `fragment` → `ArtifactNotFoundException`（`OPEN_REFERENCE_INVALID`）

### §3.3 HTTP 方法与头部

| 操作 | Method | 必须头部 | 期望状态 |
|---|---|---|---|
| 上传（幂等） | PUT | `Authorization`, `x-amz-content-sha256`, `x-amz-date`, `x-amz-meta-sha256`, `if-none-match: *` | 200-299 / 409 / 412 |
| 检元数据 | HEAD | 同上（`UNSIGNED-PAYLOAD`）| 200-299 / 404 |
| 检元数据错误 | HEAD | 同上 | 4xx/5xx（非 404）→ 错误码 |
| 下载 | GET | 同上 | 200-299 / 404 |
| 删除 | DELETE | 同上 | 200-299（404 容忍） |

### §3.4 响应处理

- 2xx → 成功，按 §2 各原语返回
- 404 → `ArtifactNotFoundException`（GET / HEAD / 完整性失败）
- 409 / 412 → upload 幂等命中，视为成功（已存在则取头部 SHA）
- 其他 4xx / 5xx → `ArtifactStorageUnavailableException("ARTIFACT_STORAGE_HTTP_ERROR")`
- IOException / InterruptedException → `ArtifactStorageUnavailableException("ARTIFACT_STORAGE_NETWORK_UNAVAILABLE")`
- 凭据缺失 → `ArtifactStorageUnavailableException("ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED")`
- SHA-256 不一致 → `ArtifactNotFoundException("PUBLISHED_ARTIFACT_INTEGRITY_FAILED")`

### §3.5 超时（adapter 内部常量）

| 操作 | timeout |
|---|---|
| PUT / HEAD / GET / DELETE | **30 s**（`HttpRequest.Builder.timeout`） |
| Probe HEAD bucket | **2 s**（`ArtifactStorageConnectivityProbeScheduler`） |

> ⚠ **本任务清单（第③步）**：当前 adapter 把 30s 写死在代码中。如果生产需要更短超时（如 PUT 10s），要修改 `S3CompatibleArtifactStorage.send()` 的 `Duration.ofSeconds(30)` 或在 `ObjectStorageConfig` 增加 `request-timeout-ms` 字段（**见 §10 自检 O-08**）。

---

## §4. 完整性校验（双重 + ZIP CRC）

| 维度 | 校验点 | 失败动作 |
|---|---|---|
| **HEAD 头** | `x-amz-meta-sha256` 与期望值比对 | 抛 `ArtifactNotFoundException("PUBLISHED_ARTIFACT_INTEGRITY_FAILED")` |
| **content-length** | 与 GET 后本地 size 比对 | 同上 |
| **GET 后本地** | `SHA-256(bytes)` == 期望 | 同上 |
| **ZIP** | `ZipInputStream` 全条目游走（`getNextEntry + read(buf)`） | 同上 |

> ⚠ **任何**完整性失败都不允许降级；安全语义优先于可用性。

---

## §5. 凭据注入两种模式

### §5.1 默认 Bean：`EnvironmentObjectStorageCredentialResolver`

仅识别 `secret://env/<NAME>`，从 `System.getenv()` 解析。

- 部署平台必须设置：
  - `SKILL_CENTER_OBJECT_STORAGE_ACCESS_KEY_ID=<aws-access-key-id>`
  - `SKILL_CENTER_OBJECT_STORAGE_SECRET_ACCESS_KEY=<aws-secret-access-key>`
- 优点：开发友好，零额外组件
- 缺点：**不支持热轮转**（变更需重启 API 进程）

### §5.2 生产推荐：**替换** `ObjectStorageCredentialResolver` Bean

```java
@Bean
@Primary
ObjectStorageCredentialResolver objectStorageCredentialResolver(
        SecretManagerClient secretManager) {
    return new SecretManagerObjectStorageCredentialResolver(secretManager);
}
```

- 参考实现：`SecretManagerObjectStorageCredentialResolver implements ObjectStorageCredentialResolver`
- 应支持定时刷新（如 `git clone vault → 缓存 → 每 5 分钟重读`）
- 应支持审计：每次凭据轮转写 audit event `ARTIFACT_STORAGE_CREDENTIAL_ROTATED`
- 推荐 vendor：K8s External Secrets Operator / Vault Agent / 阿里云 KMS

> **本任务清单（第②步）**：Secret Manager 解析器为生产推荐实现，未在代码中提供默认 Bean；K8s 部署侧需自行实现一次。

---

## §6. 连接性探针（8 状态 + scheduler）

### §6.1 Probe 端点

```http
HEAD {endpoint}/{bucket}
x-skill-center-probe: v1
```

> 用于 `ArtifactStorageConnectivityProbeScheduler`（后台）和 admin `/api/v1/admin/artifact-storage/probe`（admin only）。Probe 头部 `x-skill-center-probe: v1` 用于运维侧排查并区分业务流量。

### §6.2 Probe 状态

| HTTP 状态 | Probe status | Probe reason |
|---|---|---|
| 200-299 | `REACHABLE` | `ARTIFACT_STORAGE_PROBE_OK` |
| 其他非 4xx/5xx | `HTTP_ERROR` | `ARTIFACT_STORAGE_PROBE_HTTP_ERROR` |
| IOException | `UNREACHABLE` | `ARTIFACT_STORAGE_NETWORK_UNAVAILABLE` |
| 凭据不可解析 | `NOT_CONFIGURED` | `ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED` |
| config.configured()=false | `NOT_CONFIGURED` | `ARTIFACT_STORAGE_HTTP_NOT_CONFIGURED` |
| 其他 RuntimeException | `FAILED` | 原 reason |
| 缓存超过 300s | `STALE` | `PROBE_EXPIRED` |

### §6.3 Scheduler 行为

- 间隔：`SKILL_CENTER_ARTIFACT_STORAGE_PROBE_SCHEDULER_INTERVAL_MS`（推荐 60000ms = 1 分钟）
- TTL：`SKILL_CENTER_ARTIFACT_STORAGE_PROBE_TTL_SECONDS`（推荐 300s = 5 分钟）
- 首次延迟：`SKILL_CENTER_ARTIFACT_STORAGE_PROBE_SCHEDULER_INITIAL_DELAY_MS`（默认 1000ms）
- 静默执行（**不**写审计）
- admin 显式触发才写审计（`ARTIFACT_STORAGE_PROBE_REQUESTED`，资源 `ARTIFACT_STORAGE`）

---

## §7. CRR 跨区复制（强烈推荐生产开启）

> OBJECT_STORAGE 的**唯一**对接层是 `S3CompatibleArtifactStorage`。跨区复制是底层 S3 兼容存储的能力（OSS / S3 / MinIO 都支持），需要在云端**预先**开启 CRR 规则，本适配层不感知、也不需要修改代码。

### §7.1 推荐 CRR 配置

| 维度 | 主区 (primary) | 备区 (replica) |
|---|---|---|
| Region | `cn-north-1` | `cn-east-2` |
| Bucket | `skillcenter-prod` | `skillcenter-prod-replica` |
| 触发策略 | 实时（< 1min RPO） | 异步 |
| 过滤规则 | 仅 `sha256/` 前缀（排除 `tmp/`, `lock/`） | 全量 |
| 加密 | SSE-KMS（primary key 唯一） | SSE-KMS（同一 primary key） |
| 版本控制 | **必须开启**（备份防误删）| 同上 |
| 生命周期 | prod → IA → Glacier（按合规要求）| 仅 IA |

### §7.2 灾难切换 Runbook

1. 监控触发：`HTTP_ERROR / UNREACHABLE` 持续 > 5 分钟
2. 运维手动切换 `SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT`（改为 replica endpoint）+ 重启 API（**不**要热加载，避免半状态）
3. 业务连续性：客户端重试即可；期间 partial-failure 用 `OBJECT_STORAGE_DRILL` 标记
4. 切换回主区：等主区恢复后，逐步通过 dual-write 验证一致（schema 一致天然支持）
5. 演练记录：归档到 `docs/operations/object-storage-dr-evidence-YYYYMMDD/`

> ⚠ **本任务清单（第④步）**：CRR 配置在云端控制台，本指南仅描述 contract 与切换流程；运维需在云厂商侧配置并季度演练。

---

## §8. PITR 备份链（与 PostgreSQL / Opensearch PITR 联动）

> ⚠ 对象存储**自身**的 PIT / 版本恢复走 §7.2（CRR + 版本控制）。这里描述的是**数据库 PITR 备份的目标落地**——WAL 段与 manifest 存到对象存储。

### §8.1 备份链设计

```
PostgreSQL    → basebackup (每日 03:00 UTC)  ┐
Opensearch    → snapshot (每日 03:30 UTC)    ├→ Object Storage
WAL archive   → continuous (per segment)      ┘   prefix=backups/pg/YYYY-MM-DD/
```

- 落地路径：`s3://skillcenter-prod/prefix=backups/{pg,os}/YYYY-MM-DD/<artifact>`
- 上传原语：与制品走同一组 `ArtifactStorage` 接口（已支持）
- 加密：SSE-KMS（与 §7.1 同 primary key）
- 保留：30 天热备份 + 365 天归档（Glacier）

### §8.2 与 BACKUP_PITR 证据 ID 的关联

| OBJECT_STORAGE 提供 | BACKUP_PITR 要求 |
|---|---|
| S3 PUT 完整存储 + 5s 探针 | 备份可用性等级：≤ 30 分钟恢复 RTO |
| If-None-Match 幂等 | 备份链路**不**会因重试产生重复副本 |
| HEAD + GET 校验链 | 备份完整性证据可被 `BACKUP_PITR-{01..05}.md` 引用 |
| Probe scheduler | §6 节可观测性同时覆盖 `BACKUP_PITR_PROBE_OK` 维度 |

> 备份 runbook 文档另立 `docs/project/backup-pitr-runbook.md`（**待写**，见下一步）。

---

## §9. 错误码全集（7 类）

| 错误码 | 触发条件 | HTTP 透传 |
|---|---|---|
| `ARTIFACT_STORAGE_HTTP_ERROR` | 任一非 2xx 状态 | upload / download / delete / inspect |
| `ARTIFACT_STORAGE_NETWORK_UNAVAILABLE` | IOException / InterruptedException | 全部 |
| `ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED` | Bean 解析失败 | 全部 |
| `ARTIFACT_STORAGE_HTTP_NOT_CONFIGURED` | `config.configured()`=false | readiness / probe |
| `PUBLISHED_ARTIFACT_NOT_FOUND` | 404 | inspect / open |
| `PUBLISHED_ARTIFACT_INTEGRITY_FAILED` | SHA-256 / size / ZIP CRC 不匹配 | inspect / open |
| `ARTIFACT_STORAGE_PROBE_OK` / `ARTIFACT_STORAGE_PROBE_HTTP_ERROR` | probe | probe only |

> ⚠ **失败全部 fail-closed**，禁止 adapter 内部降级到 `ContractOnlyArtifactStorage`。生产 `MODE=http` + `BACKEND=object-storage` 是**唯一**装配组合。

---

## §10. 上线 Checklist（10 步）

- [ ] **C-01**：复制 `.env.prod.template` → `.env.prod.<cluster>`，填入真实 endpoint / bucket / region
- [ ] **C-02**：生产部署**必须**把 6 字段（backend/mode/endpoint/bucket/region/prefix）填齐
- [ ] **C-03**：在 Secret Manager 创建两个独立 secret（access-key-id / secret-access-key），分别绑定到对应环境变量
- [ ] **C-04**：填充 `SKILL_CENTER_ARTIFACT_STORAGE_PREFIX`（多租户隔离用，禁止默认 `skill-packages` 跨集群）
- [ ] **C-05**：确认 `SKILL_CENTER_ARTIFACT_BASE_URL`（CDN 或签名 URL 域名）落点正确
- [ ] **C-06**：开启云端 bucket 版本控制 + CRR 跨区复制 + SSE-KMS
- [ ] **C-07**：开启 probe scheduler（`_PROBE_SCHEDULER_ENABLED=true`），确保 admin dashboard 可查
- [ ] **C-08**：运行 `pwsh scripts/verify-production-config.ps1 -Json`，期望 `"ready": "N/N"`（含 `artifact-storage.backend`、`artifact-storage.mode`、`artifact-storage.endpoint`、`artifact-storage.bucket`、`artifact-storage.access-key-ref`、`artifact-storage.secret-key-ref`、`artifact.public-base-url` 共 7 项）
- [ ] **C-09**：UAT 阶段执行 §11 的 6 个故障场景，确认 fail-closed 行为
- [ ] **C-10**：完成 §12 的 20 项证据归档，写入 `docs/operations/object-storage-uat-evidence-YYYYMMDD/`

---

## §11. 故障场景速查

| 场景 | 现象 | 排错命令/路径 |
|---|---|---|
| F-01 endpoint 拼错 | 持续 `ARTIFACT_STORAGE_NETWORK_UNAVAILABLE` | `pwsh scripts/verify-production-config.ps1` → `artifact-storage.endpoint` 提示符 |
| F-02 region 错（S3 返回 SignatureDoesNotMatch） | 上传 403，SigV4 scope 错误 | `pwsh scripts/verify-production-config.ps1` → `artifact-storage.region` |
| F-03 access-key 失效 | 探针 `FAILED / REASON=ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED` | 替换 `secret://env/SKILL_CENTER_OBJECT_STORAGE_ACCESS_KEY_ID` 并重启 API（默认 Bean 不支持热加载） |
| F-04 bucket 不存在 | PUT 返回 404 `NoSuchBucket` | 控制台建桶；命名匹配 `[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]` |
| F-05 prefix 含 `..` | 启动期 `IllegalArgumentException(prefix contains unsafe path characters)` | `set SKILL_CENTER_ARTIFACT_STORAGE_PREFIX=skill-packages` 之类的安全路径 |
| F-06 If-None-Match 被禁用 | 重复上传返回 200（覆盖旧版本） | 业务上游检查 `_CONTROL_PLANE_OBJECT_KEY` 幂等，或运维启用条件写入 |
| F-07 探针 2s 内未返 | `ARTIFACT_STORAGE_PROBE_HTTP_ERROR` + 客户端上传 503 | 检查云厂商控制台 endpoint 健康；考虑切到 replica |
| F-08 ZIP CRC 失败 | `PUBLISHED_ARTIFACT_INTEGRITY_FAILED`，GET 抛错 | 上传源文件损坏，重新打包；客户端按 503 处理 |
| F-09 SHA-256 头不符 | `PUBLISHED_ARTIFACT_INTEGRITY_FAILED` | 重传（中间件被篡改，需安全告警） |
| F-10 跨区 CRR 滞后 | `OBJECT_STORAGE_DRILL_LOG` 标记最近 RPO 超标 | 控制台查复制状态；如持续 > 5min 触发 F-11 |
| F-11 主区不可用 | `HTTP_ERROR` / `UNREACHABLE` 持续 5min | 执行 §7.2 切换到 replica endpoint |

---

## §12. OBJECT_STORAGE + BACKUP_PITR 证据 ID 自检清单（20 项）

> 路径约定：`docs/operations/object-storage-uat-evidence-YYYYMMDD/OBJECT_STORAGE-{01..15}.md` + `BACKUP_PITR-{16..20}.md`

### OBJECT_STORAGE（15 项）

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| O-01 | endpoint 是 HTTPS，无 user-info / query / fragment | `verify-production-config.ps1` 通过 `https-uri` |
| O-02 | bucket 命名合规（`[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`） | `ObjectStorageConfig` 启动期校验不抛 |
| O-03 | region 真实填写（非占位） | `grep _REGION=changeme` 为空 |
| O-04 | prefix 仅含 `[A-Za-z0-9._/-]` | 启动期校验不抛 |
| O-05 | access-key-id 与 secret-access-key 走独立 secret | Secret Manager 路径不同 |
| O-06 | 凭据**无明文**进 git / 镜像 | `git log -p deploy/prod/.env.prod.*` 不含明文 |
| O-07 | 默认 Bean 仅 `secret://env/<NAME>` | 替换计划文档化（§5.2） |
| O-08 | 30s PUT 超时可降为 10s（**清单项**） | 评估修改 `S3CompatibleArtifactStorage.send()` 或加 config 字段 |
| O-09 | Probe scheduler 已开 | `curl /actuator/scheduledtasks` 可查 |
| O-10 | admin `/api/v1/admin/artifact-storage/probe` 返回 5 类状态 | Role 鉴权 + JSON 输出 |
| O-11 | 完整性双重校验（HEAD/GET） | 单测覆盖率 100% |
| O-12 | ZIP CRC 失败捕获 | 单测注入坏包 → `ArtifactNotFoundException` |
| O-13 | If-None-Match=* 命中（409/412）走存在分支 | 重复上传不重复写 |
| O-14 | Bucket CRR 跨区复制已配置 | 控制台审计归档 |
| O-15 | Bucket 版本控制开启 + SSE-KMS | 控制台审计归档 |

### BACKUP_PITR（5 项联动）

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| B-01 | basebackup 每日落 `s3://<bucket>/backups/pg/YYYY-MM-DD/` | 控制台列目录 |
| B-02 | WAL archive 持续上传 | 段间隔 ≤ 5min |
| B-03 | OpenSearch snapshot 每日落 `s3://<bucket>/backups/os/YYYY-MM-DD/` | SM 注册 |
| B-04 | 演练记录：季度恢复演练归档 | docs/operations/backup-drill-*/ |
| B-05 | 恢复 RTO ≤ 30 分钟 | 演练基线 + 当前平均 |

---

## 附录 A：ProductionConfigCheck 对应表

| ID | 变量 | kind | 期望值 |
|---|---|---|---|
| `artifact-storage.backend` | `SKILL_CENTER_ARTIFACT_STORAGE_BACKEND` | equals | `object-storage` |
| `artifact-storage.mode` | `SKILL_CENTER_ARTIFACT_STORAGE_MODE` | equals | `http` |
| `artifact-storage.endpoint` | `SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT` | https-uri | https://... |
| `artifact-storage.bucket` | `SKILL_CENTER_ARTIFACT_STORAGE_BUCKET` | required | 非空 + 命名合规 |
| `artifact-storage.access-key-ref` | `SKILL_CENTER_ARTIFACT_STORAGE_ACCESS_KEY_ID_REF` | secret-ref | `secret://...` |
| `artifact-storage.secret-key-ref` | `SKILL_CENTER_ARTIFACT_STORAGE_SECRET_ACCESS_KEY_REF` | secret-ref | `secret://...` |
| `artifact.public-base-url` | `SKILL_CENTER_ARTIFACT_BASE_URL` | https-uri | https://... |

模板额外声明（ProductionConfigCheck 未校验但生产必要）：
- `SKILL_CENTER_ARTIFACT_STORAGE_REGION`（影响 SigV4）
- `SKILL_CENTER_ARTIFACT_STORAGE_PREFIX`（多租户隔离）
- `SKILL_CENTER_ARTIFACT_STORAGE_PROBE_TTL_SECONDS`、`..._SCHEDULER_ENABLED`、`..._SCHEDULER_INTERVAL_MS`、`..._SCHEDULER_INITIAL_DELAY_MS`

---

## 附录 B：相关源文件清单（不修改，列出供追溯）

| 类 | 职责 |
|---|---|
| `distribution/S3CompatibleArtifactStorage.java` | 主适配器（手工 SigV4） |
| `distribution/ObjectStorageConfig.java` | 非凭据配置（含 `configured()` 完整性） |
| `distribution/ObjectStorageCredentialResolver.java` | 凭据解析接口（替换点） |
| `distribution/EnvironmentObjectStorageCredentialResolver.java` | 默认 Bean：`secret://env/<NAME>` |
| `distribution/ArtifactStorageBackendConfiguration.java` | Spring 自动装配（按 backend + mode 切换） |
| `distribution/ContractOnlyArtifactStorage.java` | `mode=contract` 时的占位实现（生产禁用） |
| `distribution/ArtifactStorageConnectivityProbe.java` | Probe 接口 |
| `distribution/ArtifactStorageUnavailableException.java` | 5 类错误码 |
| `distribution/ArtifactNotFoundException.java` | not-found / integrity-failed 错误 |
| `operations/ArtifactStorageConnectivityProbeController.java` | admin probe 端点 |
| `operations/ArtifactStorageConnectivityProbeScheduler.java` | 后台 probe 调度 |
| `operations/ArtifactStorageReadinessService.java` | readiness 聚合 |
