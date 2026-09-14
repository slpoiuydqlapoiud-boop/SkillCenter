# BACKUP_PITR 运行手册

版本：V1（2026-09-08）
证据 ID：**BACKUP_PITR**（P1 上线门禁，P0 OBJECT_STORAGE 联动）
适配对象：PostgreSQL + OpenSearch + 对象存储（即 S3CompatibleArtifactStorage）
RPO 目标：**≤ 5 分钟**（连续 WAL 上传间隔）
RTO 目标：**≤ 30 分钟**（basebackup + PIT replay）

本手册覆盖三类资产的备份与恢复策略：关系数据库（PostgreSQL）、搜索引擎（OpenSearch）、制品与备份对象存储（S3 Compatible）。生产平台的备份基础已经由 `OBJECT_STORAGE` 证据 ID 提供的 S3 兼容适配器落地；本文不修改适配层，仅约定备份链、恢复流程与演练证据归档。

---

## 0. A1 持久化控制面前置检查

部署前确认数据库、搜索与对象存储三类资产的现状：

```yaml
skill-center:
  persistence:
    backend: postgresql
    governance-backend: postgresql
    quality-evidence-backend: postgresql
    benchmark-backend: postgresql
    release-backend: postgresql
    production-evidence-backend: postgresql
  search:
    backend: opensearch
    mode: http
  artifact-storage:
    backend: object-storage
    mode: http
```

控制面与制品底层已收敛到 `ArtifactStorage`（SHA-256 内容寻址 + S3-compatible HTTP）；备份链路借此复用同一适配器，避免引入第二条对象访问通道。

---

## 1. RPO 与 RTO 目标拆解

| 资产 | RPO 目标 | RTO 目标 | 实现 |
|---|---|---|---|
| PostgreSQL | ≤ 5 分钟 | ≤ 30 分钟 | basebackup + WAL archive 连续上传 |
| OpenSearch | ≤ 1 小时 | ≤ 1 小时 | SM snapshot API（每 30 分钟） |
| S3 对象存储 | 0（多版本） | ≤ 5 分钟 | bucket 版本控制 + CRR（详见 `deploy/prod/object-storage.md` §7）|

---

## 2. PostgreSQL 备份链（3 类）

### 2.1 basebackup（每日 03:00 UTC）

```bash
pg_basebackup -h <primary-host> -U <backup-role> \
  -D - -Ft -z -Xs -P -c fast -l "skillcenter-$(date -u +%Y%m%d)" \
  | aws s3 cp - "s3://skillcenter-prod/backups/pg/basebackup/$(date -u +%Y%m%d).tar.gz" \
      --endpoint-url "${SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT}"
```

- 落地：`s3://skillcenter-prod/backups/pg/basebackup/YYYYMMDD/base.tar.gz`
- 加密：SSE-KMS（与对象存储主区同 primary key）
- 保留：30 天热备份 + 365 天归档

### 2.2 WAL archive（连续上传，间隔 ≤ 5min）

`postgresql.conf`：
```ini
wal_level = replica
archive_mode = on
archive_command = '/usr/local/bin/wal-archive.sh %p /backups/pg/wal/%f'
archive_timeout = 60
```

`wal-archive.sh`（节选）：
```bash
OBJECT_KEY="$1/$2"
curl -X PUT -H "Authorization: AWS4-HMAC-SHA256 ..." \
  --data-binary "@$1" \
  "${SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT}/skillcenter-prod/${OBJECT_KEY}"
```

> WAL 落地目录与 basebackup 保持相同 prefix，便于 PITR 时拉齐时间线。

### 2.3 manifest（每日 03:30 UTC）

```json
{
  "kind": "pg-backup-manifest",
  "version": "v1",
  "generatedAt": "2026-09-08T03:30:00Z",
  "basebackup": {
    "objectKey": "backups/pg/basebackup/20260908/base.tar.gz",
    "sha256": "<64hex>",
    "sizeBytes": 1234567890
  },
  "wal": {
    "startLsn": "0/7000028",
    "endLsn": "0/80000F0",
    "lastArchived": "backups/pg/wal/000000010000000000000080"
  },
  "retention": {
    "hotDays": 30,
    "archiveDays": 365
  }
}
```

manifest 上传：`s3://skillcenter-prod/backups/pg/basebackup/YYYYMMDD/manifest.json`，用 `If-None-Match: *` 保证幂等。

---

## 3. OpenSearch 备份（SM snapshot）

### 3.1 Snapshot 注册

```bash
curl -X PUT "${SKILL_CENTER_OPENSEARCH_ENDPOINT}/_cluster/settings" \
  -H "Authorization: Bearer ${SKILL_CENTER_SEARCH_INDEX_CREDENTIAL_REF}" \
  -d '{
    "persistent": {
      "cluster.routing.allocation.disk.threshold_enabled": true,
      "cluster.routing.allocation.disk.watermark.low": "85%",
      "cluster.routing.allocation.disk.watermark.high": "90%"
    }
  }'

curl -X POST "${SKILL_CENTER_OPEN_SEARCH_ENDPOINT}/_snapshot/skillcenter-prod" \
  -H "Authorization: Bearer ${SKILL_CENTER_SEARCH_INDEX_CREDENTIAL_REF}" \
  -d '{
    "type": "s3",
    "settings": {
      "bucket": "skillcenter-prod",
      "region": "cn-north-1",
      "base_path": "backups/os/daily",
      "endpoint": "<SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT>"
    }
  }'
```

### 3.2 每日快照

```bash
curl -X PUT "${SKILL_CENTER_OPEN_SEARCH_ENDPOINT}/_snapshot/skillcenter-prod/$(date -u +%Y%m%d-%H%M)" \
  -H "Authorization: Bearer ${SKILL_CENTER_SEARCH_INDEX_CREDENTIAL_REF}" \
  -d '{"include_global_state": false}'
```

- 落地：`s3://skillcenter-prod/backups/os/daily/YYYYMMDD-HHMM/`
- 保留：30 天热备份；过期后转存到 Glacier

### 3.3 PIT search restore

```bash
curl -X POST "${SKILL_CENTER_OPEN_SEARCH_ENDPOINT}/_snapshot/skillcenter-prod/20260908-0300/_restore" \
  -H "Authorization: Bearer ${SKILL_CENTER_SEARCH_INDEX_CREDENTIAL_REF}" \
  -d '{"indices": "*", "include_global_state": false}'
```

---

## 4. 制品 / 备份对象存储（S3 兼容）

- bucket 版本控制 + CRR 跨区复制，详 `deploy/prod/object-storage.md` §7
- 备份链 §2.1 §2.2 §2.3 §3.2 全部落到同一 bucket（multi-tenant 用 prefix 隔离）
- PITR 备份链天然复用 `S3CompatibleArtifactStorage`（PUT + HEAD + GET + 完整性校验）

---

## 5. PITR 恢复演练（季度必做）

### 5.1 演练目标

- RTO ≤ 30 分钟（PostgreSQL）
- RTO ≤ 1 小时（OpenSearch）
- 完整性：恢复后基线数据 + 当前 WAL 段恢复一致

### 5.2 演练流程（9 步）

1. **隔离演练环境**：副本 VPC / 数据库 `restore_test` 数据库 / Opensearch `restore_test` 索引
2. **拉取 basebackup**：`s3 cp s3://.../backups/pg/basebackup/<date>/base.tar.gz /tmp/pg/`
3. **解包 + 校验 SHA-256**：HEAD 比对 `x-amz-meta-sha256`，比 1 处不一致即 fail-closed
4. **解压 + 启动**：`tar -xzf base.tar.gz -C /tmp/pg/data && pg_ctl -D /tmp/pg/data start`
5. **WAL replay**：从 `recovery.conf` / `postgresql.auto.conf` 指明 `restore_command` 到对象存储的 WAL 路径
6. **回放至目标 PIT**：设置 `recovery_target_time = '<演练时刻>'`
7. **完整性校验**：对比预演表的 `count(*)` 与 WAL 段 last-archived LSN
8. **OpenSearch 恢复**：snapshot restore 到独立索引，diff 演练前后 top-100 搜索结果
9. **证据归档**：演练报告写到 `docs/operations/backup-pitr-evidence-YYYYMMDD/BACKUP_PITR-{01..20}.md`

### 5.3 失败判定

- RTO 超 30 分钟（PostgreSQL）或 1 小时（OpenSearch）→ 演练 fail
- 恢复后 ≥ 1 处 SHA-256 不一致 → 演练 fail
- OpenSearch top-100 diff > 5% → 演练 fail
- 任一演练 fail → 限 1 个月内重做；若 2 次 fail → 触发应急预案并上报

---

## 6. 应急 Runbook（5 类）

### 6.1 basebackup 缺失

- 备份链断 ≥ 1 天 → 触发告警 `BACKUP_PITR_BASEBACKUP_MISSING`
- 处理：人工立即触发 basebackup；同时停止写入以缩小 RPO
- 已知限制：basebackup 重做期间上传业务不可用

### 6.2 WAL archive 间隔 > 5min

- 告警 `WAL_ARCHIVE_LAG`
- 处理：检查 `archive_command` 与对象存储 connectivity；如不可用，告警 `OBJECT_STORAGE_UNREACHABLE`

### 6.3 OpenSearch snapshot 失败

- 告警 `OPENSEARCH_SNAPSHOT_FAILED`
- 处理：cluster 状态（红/黄/绿）；磁盘水位；CRR 同步状态；endpoint 校验

### 6.4 CRR 主区 → 备区 持续滞后

- 告警 `OBJECT_STORAGE_CRR_LAG`
- 处理：双写；触发副本切换（详见 `deploy/prod/object-storage.md` §7.2）

### 6.5 区域级灾难

- 主区 AWS region 不可用 ≥ 30 分钟
- 切换到备区 endpoint，CRR 自动接管；演练已在 §5 验证

---

## 7. ProductionEvidence 台账登记

证据写入：

```http
PUT /api/v1/admin/platform/evidence/BACKUP_PITR
Content-Type: application/json
X-User-Role: admin

{
  "status": "ACCEPTED",
  "ownerUserId": "sre-team-oncall",
  "expiresAt": "2026-12-31T00:00:00Z",
  "evidenceRef": "evidence-ref:v1:backup-pitr:drill-2026Q4",
  "summary": "Quarterly PITR drill passed; RTO=22min; RPO=3min",
  "revision": 4
}
```

约束（与 `ProductionEvidence` 一致）：
- `evidenceRef`：`[A-Za-z0-9][A-Za-z0-9._:-]{0,119}`
- `summary`：≤ 240 字符、无控制字符、无敏感词
- `evidenceId`：枚举中的字面值
- UPDATE 时 `revision` 单调递增；冲突 → `ProductionEvidenceConflictException`
- 退化为 `MISSING` / `REJECTED` 时 status 不可写回 `ACCEPTED`（需提升 revision）

`ProductionEvidence.revision` 不允许倒退；要撤销 `ACCEPTED` 必须先 `status=REJECTED` 并 `revision+1`。

---

## 8. 上线 checklist（生产部署前必做）

- [ ] **C-01** 确认 `SKILL_CENTER_PERSISTENCE_BACKEND=postgresql`、`OBJECT_STORAGE_BACKEND=object-storage`、`SEARCH_INDEX_BACKEND=opensearch`
- [ ] **C-02** 创建 IAM 角色 `skillcenter-backup`，赋权 `s3:PutObject`、`s3:GetObject`、`s3:ListBucket` 仅限 `skillcenter-prod/backups/*` 前缀
- [ ] **C-03** 配置对象存储 bucket 版本控制 + CRR 跨区复制（详 `object-storage.md` §7）
- [ ] **C-04** `archive_command` 走对象存储 HTTP PUT（与 S3CompatibleArtifactStorage 同接口）
- [ ] **C-05** 设置 basebackup 03:00 UTC cron；输出 `<date>.tar.gz` + `<date>.manifest.json`
- [ ] **C-06** 设置 OpenSearch snapshot 03:30 UTC cron
- [ ] **C-07** 注册 PITR 演练提醒：每季度首周四 09:00 UTC
- [ ] **C-08** 配置告警：basebackup 缺失 / WAL archive lag > 5min / OpenSearch snapshot 失败 / CRR 滞后
- [ ] **C-09** 完成 §9 的 20 项自检；归档到 `docs/operations/backup-pitr-evidence-YYYYMMDD/`
- [ ] **C-10** `pwsh scripts/verify-production-handoff.ps1 -Json -FailOnNotReady` 期望 `BACKUP_PITR=ACCEPTED` + revision ≥ 1

---

## 9. BACKUP_PITR 自检清单（20 项）

路径：`docs/operations/backup-pitr-evidence-YYYYMMDD/BACKUP_PITR-{01..20}.md`

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| B-01 | basebackup 每日落 `s3://<bucket>/backups/pg/basebackup/YYYY-MM-DD/` | 控制台列目录 |
| B-02 | basebackup 大小 > 1MB 且 manifest.json 含完整 schema | sha256 + size 校验 |
| B-03 | WAL archive 持续上传 | 段间隔 ≤ 5min（监控） |
| B-04 | manifest `<date>.manifest.json` 与 basebackup 同 prefix | HEAD 同 prefix 路径 |
| B-05 | WAL 从 `startLsn` 到 `endLsn` 完整无缺段 | 最近 7 天 manifest 抽样 |
| B-06 | 加密：SSE-KMS | bucket policy 审计 |
| B-07 | 保留：30 天热 + 365 天归档 | lifecycle 规则 |
| B-08 | OpenSearch snapshot 每 30min | 监控曲线 |
| B-09 | OpenSearch snapshot 落地 prefix `backups/os/daily/` | 控制台列目录 |
| B-10 | Opensearch snapshot SM 注册成功 | GET `/_snapshot/skillcenter-prod/_all` |
| B-11 | 演练 RTO ≤ 30min（PostgreSQL） | 演练报告 `elapsedSeconds` |
| B-12 | 演练 RPO ≤ 5min | 演练报告 `maxWalLagSeconds` |
| B-13 | 演练完整性校验通过 | SHA-256 全比对 |
| B-14 | OpenSearch top-100 diff ≤ 5% | 演练报告 `topResultsDiff%` |
| B-15 | CRR 跨区复制配置 | 控制台审计归档 |
| B-16 | bucket 版本控制开启 | 控制台审计归档 |
| B-17 | 告警 4 类全部接警（basebackup / WAL / OS / CRR） | 告警注册截图 |
| B-18 | 季度演练节奏已建立（首周四 09:00 UTC） | 日历事件归档 |
| B-19 | ProductionEvidence `BACKUP_PITR.status=ACCEPTED` + revision ≥ 1 | `verify-production-handoff.ps1 -Json` |
| B-20 | 应急 Runbook §6 全部回看演练已通过（5 类） | 演练记录归档 |

---

## 10. 与其他证据 ID 的关联

| 关联证据 | 依赖 / 联动 |
|---|---|
| `OBJECT_STORAGE` | 备份链全部落到 bucket；CRR 切换由 OBJECT_STORAGE 控制 |
| `SLO_UAT` | 演练中的 RPO/RTO 数据可被 SLO_UAT 引用 |
| `DATABASE_CAPACITY_SLO` | 备份压缩/解压速度纳入 SLO 维度 |

---

## 附录 A：相关源文件清单（不修改）

| 类 | 职责 |
|---|---|
| `distribution/S3CompatibleArtifactStorage.java` | S3 兼容适配器（手工 SigV4）|
| `operations/ProductionEvidenceController.java` | 证据登记端点 |
| `operations/ProductionEvidenceService.java` | 证据状态机（4 态）|
| `operations/ProductionEvidence.java` | 证据 metadata-only 边界 |
| `operations/ProductionEvidenceCatalog.java` | 9 个固定证据 ID |
