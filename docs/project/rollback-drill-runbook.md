# ROLLBACK_DRILL 运行手册

版本：V1（2026-09-08）
证据 ID：**ROLLBACK_DRILL**（P1 上线门禁）
回滚覆盖：API 版本、流量切换、Schema 数据、Provider 流量、制品回滚
触发频率：**季度演练**（首选首个周三 14:00 UTC）
R-RTO 目标：单个回滚动作 RTO ≤ 10 分钟
R-RPO 目标：回滚窗口内数据丢失 ≤ 0

本手册覆盖 5 类回滚策略与季度演练节奏，不引入新的回滚代码。所有回滚动作以 `ReleaseRecord`（`JdbcReleaseRecordStore`）为唯一来源，回滚审批 / 执行 / 验证 / 归档全程纳管审计。

---

## 0. 范围与基础

| 回滚维度 | 影响半径 | 主要接口 |
|---|---|---|
| API 版本回滚 | API 进程 | K8s rolling back / 镜像 tag |
| 流量切换 | API 入口 / 网关 | 网关路由 / DNS |
| Schema 数据回滚 | 数据库 | 备份恢复（联动 BACKUP_PITR）|
| Provider 流量拆分 | 评估流量 | 流量开关 |
| 制品重新发布 | Skill 目录 | release service |

基础：`SKILL_CENTER_RELEASE_ADMISSION_MODE=CONTROLLED`（`deploy/prod/.env.prod.template` 第 7 节）；任何 `CONTROLLED` 之外的取值都会被 `verify-production-config` 拒绝。

---

## 1. 5 类回滚策略

### 1.1 API 版本回滚（最常见）

**触发条件**：发布 API v2 后出现 P0/P1 健康事故 / SLO 跌破阈值。

**动作**：

1. K8s：`kubectl rollout undo deployment/skillcenter-api -n prod --to-revision=<PREV>`
2. 旧版镜像 tag（v1.x.y）保留 7 天，符合版本回滚需求
3. 同时回滚 Web 镜像（连动依赖）
4. 验证：`/actuator/health/readiness` +5 类 slo-smoke

**R-RTO 目标**：≤ 5 分钟（镜像已预热）
**R-RPO 目标**：0（API 不持有写状态）

### 1.2 流量切换（蓝绿 / 金丝雀）

**触发条件**：v2 健康度下降但 v1 仍可用。

**动作**：

1. 网关把 100% 流量切回 v1 蓝组
2. v2 仍保留部分流量作 hot-standby（默认 0 %）
3. 1h 后仍稳定则下线 v2

**R-RTO 目标**：≤ 2 分钟（流量切换零停机）
**R-RPO 目标**：0

### 1.3 数据库 Schema 回滚

**触发条件**：破坏性 migration 上线后导致写入异常（如 unique 冲突）。

**前置**：所有破坏性 migration 必须成对（up/down），且 down 脚本与 up 同样签名。

**动作**：

1. 应用层先停写入（流量切到只读模式）
2. 运行反向 migration：`flyway/info` → `flyway/migrate -target=PREV_VERSION`
3. 数据修复（如需要，参照 `BACKUP_PITR` §5 流程从 basebackup + WAL recovery）
4. 重启应用，恢复流量

**R-RTO 目标**：≤ 15 分钟（migration + 应用重启）
**R-RPO 目标**：≤ 5 分钟（受 backup RPO 约束）

### 1.4 Provider 流量拆分

**触发条件**：OpenClaw / DeepEval / Langfuse 任何 Provider 错误码突增（`RATE_LIMITED / UPSTREAM_REJECTED`）。

**动作**：

1. 把对应 Provider 流量切回 `mode=contract`（仅本地契约校验，不发外）
2. 同时熔断：调用层快速失败，禁止慢请求拥塞
3. 待 Provider SLO 恢复后重新开启 `mode=http` + 灰度 1% → 10% → 50% → 100%

**R-RTO 目标**：≤ 2 分钟（切回 contract 模式无新部署）
**R-RPO 目标**：0（评估数据不丢失）

### 1.5 制品重新发布

**触发条件**：发布有缺陷的 Skill 包导致用户报错。

**动作**：

1. 通过 release service 标记有缺陷版本为 `WITHDRAWN`
2. 通过 release service 用历史稳定版 `PROMOTED` 到 target namespace
3. 通知用户侧 `WITHDRAWN` 已生效

**R-RTO 目标**：≤ 5 分钟
**R-RPO 目标**：0（包本身是只读）

---

## 2. 季度演练（10 步流程）

### 2.1 演练节奏

- 季度首周三 14:00 UTC
- 演练环境：生产镜像 + 隔离 namespace（不接入真实业务流量）
- 演练时长：90 分钟（5 类 × 12 min + 复盘）
- 演练人员：ReleaseLead + SRE + 业务 Owner + 审计员

### 2.2 10 步流程

1. **演练预热**：演练前 24h 通知所有相关方
2. **确认基线**：5 类 slo-smoke 通过
3. **场景注入**（4 选 1）：
   - A. API v2 异常 → 演练 §1.1
   - B. v2 健康下降 → 演练 §1.2
   - C. Schema migration 报错 → 演练 §1.3
   - D. Provider 故障 → 演练 §1.4
4. **执行回滚**：按所选场景执行 §1.x 动作
5. **时戳记录**：动作开始 / 结束 / 验证通过的时间戳
6. **业务连续性**：观察客户端重试成功率 ≥ 99 %
7. **审计齐全**：每个动作的事件被审计台账捕获
8. **回滚验证**：与基线比对 5 类 slo-smoke，确认 RTO/RPO 达标
9. **复盘**：演练报告 ≥ 4 KB，写到 `docs/operations/rollback-drill-evidence-YYYYMMDD/`
10. **归档**：演练报告 + 元数据 PUT `ProductionEvidence.ROLLBACK_DRILL` `status=ACCEPTED` + `revision+1`

### 2.3 失败判定

- 任何 5 类中 R-RTO 超目标 → 演练 fail
- 业务连续性 < 99 % → 演练 fail
- 审计捕获 < 100 % 动作 → 演练 fail
- 1 个月内重做；连续 2 次 fail 触发 ADR 评审

---

## 3. 应急 Runbook（生产级）

### 3.1 P0 事故触发

- 监控告警（5 类 slo-smoke 跌破阈值）
- 客户端投诉（quality-evidence 写入失败率 > 0.1 %）
- 内部值班响应：5 分钟内确认事故

### 3.2 决策矩阵

| 事故级别 | 决策 | 响应时间 |
|---|---|---|
| P0 | ReleaseLead 立即一键回滚（保留证据） | ≤ 5 分钟 |
| P1 | 灰度回滚 + 监控 30 分钟 + 全量回滚 | ≤ 30 分钟 |
| P2 | 标记 + 二次发布修复 | ≤ 24 小时 |

### 3.3 一键回滚命令

```bash
# 1. 记录事故 ID + 责任人
INCIDENT_ID="INC-$(date -u +%Y%m%d%H%M)-R$(git rev-parse --short HEAD)"

# 2. K8s 回滚
kubectl rollout undo deployment/skillcenter-api -n prod

# 3. 取消当前 release 状态
curl -X PUT "${API_BASE}/api/v1/admin/release/${RELEASE_ID}/cancel" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"reason\": \"${INCIDENT_ID} - P0 incident\", \"rollback\": true}"

# 4. 5 类 slo-smoke 验证
for path in skills governance/decisions quality/evidences operations/alert-summary; do
  node scripts/slo-smoke.mjs \
    --url="${API_BASE}/api/v1/${path}" \
    --duration-seconds=30 --concurrency=4 \
    --p95-ms=500 --min-success-rate=99 \
    --output=artifacts/slo-${path}-${INCIDENT_ID}.json
done
```

---

## 4. ReleaseRecord 与审计

`JdbcReleaseRecordStore` 是 release 单一来源，每次发布 / 回滚写一条记录：

| 字段 | 取值 |
|---|---|
| releaseId | UUID |
| skillId | 关联 Skill |
| previousReleaseId | 回滚目标（可选）|
| status | ADMITTED / CANCELLED / ROLLED_BACK |
| requestedBy | actor.userId |
| decidedBy | ReleaseLead.userId（CONTROLLED 必需）|
| decision | ALLOW / BLOCK |
| evidenceRef | ProductionEvidence evidenceId |
| auditedAt | ISO-8601 |

回滚审批链：
1. ReleaseLead 审批 → 1 票
2. QualityOwner 审批 → 1 票（评估证据须合格）
3. SREOnCall 审批 → 1 票
4. 3 票齐全 → `release-admission-decision` 写 ALLOW

---

## 5. ProductionEvidence 登记

```http
PUT /api/v1/admin/platform/evidence/ROLLBACK_DRILL
Content-Type: application/json
X-User-Role: admin

{
  "status": "ACCEPTED",
  "ownerUserId": "release-lead-oncall",
  "evidenceRef": "evidence-ref:v1:rollback-drill:2026Q4",
  "summary": "Quarterly rollback drill passed; 5/5 scenarios; mean RTO 4min",
  "expiresAt": "2026-12-31T00:00:00Z",
  "revision": 4
}
```

约束同 BACKUP_PITR；`summary` 描述回滚类型 + 通过数量 + 平均 RTO。

---

## 6. 上线 checklist

- [ ] **C-01** 所有破坏性 migration 已配 down 脚本且与 up 同签名
- [ ] **C-02** 历史 release 镜像保留 ≥ 7 天
- [ ] **C-03** ReleaseLead / QualityOwner / SREOnCall 三组角色齐全
- [ ] **C-04** 应急一键回滚脚本 §3.3 可直接执行（演练环境验证）
- [ ] **C-05** Provider 流量开关 gate 启用（探针 + 灰度能力）
- [ ] **C-06** §2.2 季度演练节奏已写入日历
- [ ] **C-07** Provider 错误码突增告警接警
- [ ] **C-08** §7 自检 20 项完成
- [ ] **C-09** 演练报告归档 + ProductionEvidence 登记
- [ ] **C-10** `verify-production-handoff.ps1 -Json -FailOnNotReady` 期望 `ROLLBACK_DRILL=ACCEPTED` + revision ≥ 1

---

## 7. ROLLBACK_DRILL 自检清单（20 项）

路径：`docs/operations/rollback-drill-evidence-YYYYMMDD/ROLLBACK_DRILL-{01..20}.md`

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| R-01 | API 版本回滚 §1.1 演练完成 | mean RTO ≤ 5min |
| R-02 | 流量切换 §1.2 演练完成 | RTO ≤ 2min |
| R-03 | Schema migration 反向 §1.3 演练 | RTO ≤ 15min |
| R-04 | Provider 流量拆分 §1.4 演练 | RTO ≤ 2min |
| R-05 | 制品回滚 §1.5 演练 | RTO ≤ 5min |
| R-06 | 5 类演练全部完成（季度） | 演练报告 |
| R-07 | 业务连续性 ≥ 99 % | 演练报告 |
| R-08 | 审计捕获 100 % 动作 | 审计台账 |
| R-09 | 一键回滚脚本 §3.3 跑通 | 执行截图 |
| R-10 | ReleaseLead / QualityOwner / SREOnCall 角色齐全 | 角色 RBAC |
| R-11 | 3 票齐全才能 ALLOW | release service |
| R-12 | 历史 release 镜像保留 ≥ 7 天 | image registry 审计 |
| R-13 | migration down 脚本与 up 同签名 | SQL 审核记录 |
| R-14 | Provider 错误码突增告警接警 | 告警注册 |
| R-15 | Provider 灰度能力（1/10/50/100） | release service |
| R-16 | §2.2 演练节奏日历事件已建立 | 日历事件归档 |
| R-17 | 演练时长 ≤ 90 分钟 | 演练报告 |
| R-18 | 演练报告 ≥ 4KB 归档 | 文件归档 |
| R-19 | ProductionEvidence `ROLLBACK_DRILL=ACCEPTED` + revision ≥ 1 | handoff script |
| R-20 | 连续 2 次 fail 触发 ADR（若有） | ADR 文档 |

---

## 8. 与其他证据 ID 的关联

| 关联证据 | 依赖 / 联动 |
|---|---|
| `BACKUP_PITR` | §1.3 Schema 数据回滚依赖备份恢复 |
| `RELEASE_APPROVAL` | 回滚审批链与发布审批同链 |
| `DATABASE_CAPACITY_SLO` | 5 类 slo-smoke 与回滚验证共用 |
| `PROVIDER_RUNTIME_GATEWAY` | §1.4 Provider 流量拆分联动 Provider 探针 |

---

## 附录 A：相关源文件清单（不修改）

| 类 | 职责 |
|---|---|
| `release/JdbcReleaseRecordStore.java` | release 记录 |
| `release/ReleaseAdmissionDecision.java` | CONTROLLED 决策 |
| `release/RollbackReviewRequest.java` | 回滚审批请求 |
| `release/HttpReleaseTarget.java` | 真实 release target（已实现）|
| `operations/ProductionEvidenceController.java` | 证据登记 |
