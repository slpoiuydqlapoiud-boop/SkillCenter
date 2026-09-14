# RELEASE_APPROVAL 运行手册

版本：V1（2026-09-08）
证据 ID：**RELEASE_APPROVAL**（P1 上线门禁）
强制模式：**CONTROLLED**（`SKILL_CENTER_RELEASE_ADMISSION_MODE=CONTROLLED`）
审批链：3 票齐全（ReleaseLead + QualityOwner + SREOnCall）
证据汇签：6 类（评估证据 + SLO + Security + Audit + Capacity + Rollback）

本手册约束生产环境 release 审批链与证据汇签要求。ReleaseAdmissionDecision 是 `release` 子系统唯一准入控制器；CONTROLLED 之外的任何取值都会被 `verify-production-config` 拒绝并阻止部署。

---

## 0. 范围与基础

```
SKILL_CENTER_RELEASE_ADMISSION_MODE=CONTROLLED        # 强制
SKILL_CENTER_RELEASE_TARGET_MODE=http                  # release target
SKILL_CENTER_RELEASE_TARGET_ENDPOINT=https://...       # HTTPS release target
```

5 类审批人角色：

| 角色 | 范围 | 权限 |
|---|---|---|
| Requester | 发起发布请求 | 提交 `release` 请求 |
| ReleaseLead | Release 主负责人 | 1 票审批 |
| QualityOwner | Quality 评估证据责任人 | 1 票审批 |
| SREOnCall | 生产值班 | 1 票审批 |
| Approver | 跨角色会签 ≥ 3 票时才生效 | 实际 ALLOW |

---

## 1. 6 类证据汇签

ReleaseAdmissionDecision 仅在 6 类证据齐备时 ALLOW：

### 1.1 评估证据（Q）

- 来源：`QualityEvaluationReleaseGate`、`QualityReleaseGate`
- 关联指标：5 类 SLO + 评估分数阈值
- 通过判定：本次 release 影响范围内所有评估 + 基准全部通过

### 1.2 SLO 证据（S）

- 来源：`slo-smoke.mjs` 输出
- 通过判定：5 类只读路径通过（`scripts/verify-production-handoff.ps1 -Json` 中 DATABASE_CAPACITY_SLO 与 SLO_UAT 已 ACCEPTED）

### 1.3 Security（Sec）

- 来源：`deploy/prod/security-scanner.md` 的 4 类扫描
- 通过判定：`PROVIDER_SECURITY=ACCEPTED` + 最近 24h 内扫描结果无严重发现

### 1.4 Audit（Aud）

- 来源：所有 release-relevant 动作的审计台账
- 通过判定：审计日志**无** `release-rollback` / `release-rollforward` 异常链

### 1.5 Capacity（C）

- 来源：`docs/operations/capacity-evidence-*` 月度趋势
- 通过判定：`DATABASE_CAPACITY_SLO=ACCEPTED` 且最近触发条件未命中

### 1.6 Rollback（R）

- 来源：`docs/operations/rollback-drill-evidence-*`
- 通过判定：`ROLLBACK_DRILL=ACCEPTED` 且最近一次演练在 90 天内

---

## 2. CONTROLLED 流程（10 步）

### 2.1 Requester 提交

```http
POST /api/v1/release
Content-Type: application/json
X-User-Role: developer

{
  "skillId": "<uuid>",
  "version": "<semver>",
  "artifactRef": "s3://skillcenter-prod/prefix/sha256/<64hex>.zip",
  "rationale": "Hotfix for X (issue-123)",
  "rollbackPlan": "kubectl rollout undo deployment/skillcenter-api (RTO 5min)",
  "evidenceRefs": {
    "quality": "evidence-ref:v1:quality:release-2026Q4",
    "slo": "evidence-ref:v1:slo-uat:smoke-2026Q4",
    "security": "evidence-ref:v1:provider-security:scan-2026Q4",
    "capacity": "evidence-ref:v1:db-capacity:trend-2026Q4"
  }
}
```

### 2.2 Quality 评估自动校验（异步 30s）

`QualityEvaluationReleaseGate` 拉本次 release 影响范围内评估用例 + 基准：
- 失败 → 决策 BLOCK
- 通过 → 进入 3 票审批阶段

### 2.3 ReleaseLead 1 票

```http
PUT /api/v1/release/{releaseId}/approve
X-User-Role: release-lead
{
  "decision": "ALLOW",
  "evidenceRefs": ["..."]
}
```

### 2.4 QualityOwner 1 票

```http
PUT /api/v1/release/{releaseId}/approve
X-User-Role: quality-owner
{ "decision": "ALLOW" }
```

### 2.5 SREOnCall 1 票

```http
PUT /api/v1/release/{releaseId}/approve
X-User-Role: sre-oncall
{ "decision": "ALLOW" }
```

### 2.6 Approver 汇总

3 票齐全 + 6 类证据齐备 → `release_admission_decision = ALLOW`
不齐全 → `BLOCK` 并写明缺失原因

### 2.7 release target 接收

`HttpReleaseTarget` 接收 ALLOW，把 `release` 状态推到目标 endpoint。该过程：
- 仅发送 metadata（skillId/version/releaseId/status/deciders/decisions/evidenceRefs）
- 不发送 Prompt / 输出 / Token / Trace
- 失败重试 3 次（指数退避）；仍失败 → BLOCK + 告警

### 2.8 部署执行

- release target 端按 §2.7 接收后启动实际部署
- 部署过程受 K8s readiness + slo-smoke 监控
- 任一指标跌破阈值 → 立即触发 §3 应急回滚（联动 ROLLBACK_DRILL）

### 2.9 验证上线

5 类 slo-smoke + 业务连续性比对 + Audit 写入完整 + `release` 状态 `DEPLOYED`

### 2.10 归档到 ProductionEvidence

```http
PUT /api/v1/admin/platform/evidence/RELEASE_APPROVAL
{
  "status": "ACCEPTED",
  "ownerUserId": "release-lead-oncall",
  "expiresAt": "2026-12-31T00:00:00Z",
  "evidenceRef": "evidence-ref:v1:release-approval:Q4-2026-acceptance",
  "summary": "CONTROLLED release: 3-approval + 6-evidence satisfied; 0 incident",
  "revision": 12
}
```

约束同 BACKUP_PITR / ROLLBACK_DRILL：4 态 status、`evidenceRef` 正则、`summary` ≤240 字符、无敏感词。

---

## 3. 应急（CONTROLLED 内）

| 场景 | 应急 |
|---|---|
| 3 票齐全但 release target 拒收 | 重试 3 次（指数退避）；仍失败 → 升级 ReleaseLead |
| 评估突然失败 | SREOnCall / QualityOwner 投票改 BLOCK；sync Requester 后重新提交 |
| 上线后 SLO 跌破 | 触发 ROLLBACK_DRILL §1.1 |
| Audit 异常 | 暂停所有 release；上线审批冻结 |

---

## 4. 角色与权限映射

| 角色 | RBAC 必需 |
|---|---|
| Requester | `release.create` 权限 |
| ReleaseLead | `release.approve.release-lead` 权限 |
| QualityOwner | `release.approve.quality-owner` 权限 |
| SREOnCall | `release.approve.sre-oncall` 权限 |
| Approver（虚拟）| 上述 3 票齐全 + ApprovalService 自动触发 |

SSO/JWT `roles` claim 必须含上述任一角色（详 `deploy/prod/sso-integration.md`）。

---

## 5. 上线 checklist

- [ ] **C-01** `SKILL_CENTER_RELEASE_ADMISSION_MODE=CONTROLLED` 已注入
- [ ] **C-02** 3 类审批人角色齐全且 SSO `roles` claim 已包含
- [ ] **C-03** 6 类证据汇签通路清晰：QualityEvaluationReleaseGate + quality-evidence + slo-smoke + security-scanner + capacity-trend + rollback-drill
- [ ] **C-04** release target (`HttpReleaseTarget`) 在 §2.7 通过 metadata-only 验收
- [ ] **C-05** 任一证据缺失时能立即拒绝（§2.6）
- [ ] **C-06** `QualityEvaluationReleaseGate` 自动校验在 30s 内生效
- [ ] **C-07** release target 重试策略（3 次指数退避）
- [ ] **C-08** §6 自检 20 项完成
- [ ] **C-09** ProductionEvidence `RELEASE_APPROVAL=ACCEPTED` + revision ≥ 1 登记
- [ ] **C-10** `verify-production-handoff.ps1 -Json -FailOnNotReady` 期望全部 9 个证据 ACCEPTED

---

## 6. RELEASE_APPROVAL 自检清单（20 项）

路径：`docs/operations/release-approval-evidence-YYYYMMDD/RELEASE_APPROVAL-{01..20}.md`

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| A-01 | `SKILL_CENTER_RELEASE_ADMISSION_MODE=CONTROLLED` 已注入 | env audit |
| A-02 | Requester 提交 release 接口可访问 | API 200 |
| A-03 | QualityEvaluationReleaseGate 自动校验 30s 内生效 | 异步执行日志 |
| A-04 | Quality 证据未通过 → BLOCK | 失败用例执行 |
| A-05 | Quality 证据通过 → 进入审批链 | 失败用例执行 |
| A-06 | ReleaseLead 一票生效 | RBAC 验证 |
| A-07 | QualityOwner 一票生效 | RBAC 验证 |
| A-08 | SREOnCall 一票生效 | RBAC 验证 |
| A-09 | 3 票齐全 → ALLOW | 测试场景 |
| A-10 | 缺任一票 → BLOCK | 测试场景 |
| A-11 | release target 接收 metadata-only | 包实测 |
| A-12 | release target 不传播敏感信息 | 包实测 |
| A-13 | release target 重试 3 次指数退避 | 失败用例 |
| A-14 | 部署失败触发 ROLLBACK_DRILL §1.1 | 联动演练 |
| A-15 | 应急冻结（任一 Audit 异常）| 表单 |
| A-16 | 6 类证据汇签通路清晰 | 角色权限矩阵 |
| A-17 | SSO `roles` claim 含 3 角色 | JWT 抽样 |
| A-18 | 上线 checklist 全部 10 项通过 | 文件归档 |
| A-19 | ProductionEvidence `RELEASE_APPROVAL=ACCEPTED` + revision ≥ 1 | handoff script |
| A-20 | 连续 2 个 quarter 仍 CONTROLLED（无降级）| env audit |

---

## 7. 与其他证据 ID 的关联

| 关联证据 | 依赖 / 联动 |
|---|---|
| `SLO_UAT` | §2.2 SLO 通过依赖 |
| `DATABASE_CAPACITY_SLO` | §1.5 Capacity 通过依赖 |
| `PROVIDER_SECURITY` | §1.3 Security 通过依赖 |
| `ROLLBACK_DRILL` | §1.6 Rollback 通过依赖 + §3 应急联动 |
| `SSO_ORGANIZATION` | §4 角色 RBAC 依赖 |

---

## 附录 A：相关源文件清单（不修改）

| 类 | 职责 |
|---|---|
| `release/ReleaseAdmissionDecision.java` | ADMIT / BLOCK 决策 |
| `release/ReleaseAdmissionException.java` | 拒绝时抛出 |
| `release/HttpReleaseTarget.java` | release target HTTP 客户端 |
| `release/HttpReleaseTargetConfig.java` | target 配置 |
| `governance/QualityEvaluationReleaseGate.java` | 评估证据自动校验 |
| `governance/QualityReleaseGate.java` | 质量门禁 |
| `release/JdbcReleaseRecordStore.java` | release 记录存储 |
