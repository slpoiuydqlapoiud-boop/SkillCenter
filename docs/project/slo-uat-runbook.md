# SLO_UAT 运行手册

版本：V1（2026-09-08）
证据 ID：**SLO_UAT**（P1 上线门禁）
UAT 范围：5 类只读路径 + Provider 流量 + 对象存储 + Redis HA + SSO/JWT 旁路测试
工具：`scripts/slo-smoke.mjs`（已存在）+ `verify-production-handoff.ps1`（已存在）
触发窗口：每个 release 前必做 + 季度大演练 + CONTROLLED 释放前必做

本手册覆盖 20 项 UAT 用例与准出门禁；不引入新工具，复用既有 `slo-smoke.mjs` 与 handoff 脚本。UAT 验证不直接产生 ACCEPTED 状态——最终由责任人 `ProductionEvidence.UPDATE` 完成。

---

## 0. 范围与边界

5 类只读路径（与 `scripts/slo-smoke.mjs` 一致）：

1. `/api/v1/skills` — Skill 列表
2. `/api/v1/governance/decisions` — 治理决策列表
3. `/api/v1/quality/evidences` — 质量证据列表
4. `/api/v1/operations/alert-summary` — 告警汇总
5. `/api/v1/platform/release-records` — 发布记录列表

附加验证：
- Provider 流量（OpenClaw / DeepEval / Langfuse）
- Redis HA（fail-over 子用例）
- SSO/JWT 旁路测试（issuer / audience / 角色 / JWKS 失效）
- 对象存储 CRUD 路径
- 制品上传完整路径（含 security-scanner）

---

## 1. 通用 UAT 节奏（10 步）

1. **确认基线**：5 类端点最近 24h 内 slo-smoke 通过
2. **拉取 release / change ID**：从 release service 拿 `releaseId`
3. **选定窗口**：测试窗口 ≤ 30 分钟（防止业务方影响）
4. **执行 §2 的 20 项用例**：每项用例 ≤ 60s，单序列或并发
5. **采集证据**：每项用例结果写入 `evidence-runbook.json`
6. **检查阈值**：p95/p99/success-rate 与推荐阈值比对
7. **记录失败**：任一不通过 → 状态置 `SUBMITTED` 待复审
8. **责任人签字**：UAT Lead + Release Lead + SREOnCall 三签
9. **ProductionEvidence.UPDATE**：`status=ACCEPTED` + `revision+1`
10. **归档到 handoff report**

---

## 2. 20 项 UAT 用例

### 2.1 性能延迟类（5 项）

| ID | 端点 | 并发 | 推荐 p95 | 通过判定 |
|---|---|---|---|---|
| U-01 | `/api/v1/skills` | 8 | ≤ 300 ms | p95 + success-rate |
| U-02 | `/api/v1/governance/decisions` | 8 | ≤ 300 ms | 同上 |
| U-03 | `/api/v1/quality/evidences` | 8 | ≤ 500 ms | 同上 |
| U-04 | `/api/v1/operations/alert-summary` | 4 | ≤ 500 ms | 同上 |
| U-05 | `/api/v1/platform/release-records` | 4 | ≤ 500 ms | 同上 |

执行示例（U-01）：

```bash
node scripts/slo-smoke.mjs \
  --url=https://${SKILL_CENTER_PUBLIC_BASE_URL}/api/v1/skills \
  --duration-seconds=60 --concurrency=8 \
  --request-timeout-ms=2000 \
  --p95-ms=300 --min-success-rate=99.9 \
  --output=artifacts/slo-skill-list-uat-$(date -u +%Y%m%d).json
```

退出码：
- `0` 通过
- `2` 不达阈值
- `1` 参数/运行错误

> slo-smoke 工具不读取/记录响应正文，遵守 metadata-only 边界。

### 2.2 路径完整类（5 项）

| ID | 用例 | 通过判定 |
|---|---|---|
| U-06 | Skills 列表 → 详情 → 版本列表 | 端点 200 + content-type application/json |
| U-07 | Quality 评估用例列表 → 详情 → mock execute | 端点 200 |
| U-08 | Release records 列表 → 详情 → admit 决策 | release_id 状态 ADMITTED |
| U-09 | Organization directory 列表 → 单 member | `sso-integration.md` §8 自检一致 |
| U-10 | Provider probe (`/api/v1/admin/provider-probe`) | REACHABLE × 3 |

### 2.3 故障注入类（5 项）

每项用蓝绿 / fail-injection gateway 完成（**不**直接破坏服务）。

| ID | 注入 | 期望 |
|---|---|---|
| U-11 | JWT 过期 | 401，错误码 JWT_TOKEN_EXPIRED |
| U-12 | JWT issuer 不匹配 | 401，错误码 JWT_ISSUER_MISMATCH |
| U-13 | JWT audience 不匹配 | 401，错误码 JWT_AUDIENCE_MISMATCH |
| U-14 | Provider OpenClaw 5xx | 上层 fail-closed；audit 写 `PROVIDER_UNAVAILABLE` |
| U-15 | JWKS uri 不可达 | fail-closed，无本地降级，401 |

### 2.4 HA & 灾备类（3 项）

| ID | 场景 | 通过判定 |
|---|---|---|
| U-16 | Redis sentinel fail-over | 自动切换 ≤ 15s；业务重连 |
| U-17 | 对象存储 HEAD 探针 | REACHABLE 状态 ≥ 5min |
| U-18 | 数据备份 PITR smoke（无实际恢复） | 链路上 WAL archive ≤ 5min lag |

### 2.5 上线门禁类（2 项）

| ID | 用例 | 通过判定 |
|---|---|---|
| U-19 | `verify-production-handoff.ps1 -Json` | 9 个证据全部 ACCEPTED |
| U-20 | `verify-lifecycle.ps1 -CheckProductionHandoff` | 整体通过 |

---

## 3. 失败判定与重做

### 3.1 性能类失败

- 任一端点 p95 突破推荐阈值 → 该用例不达
- 重做：单用例重新执行；如再次失败 → 阻塞 release，触发 ROLLBACK_DRILL §3

### 3.2 路径完整类失败

- 端点 5xx / 4xx（未授权除外）/ 响应非 JSON → 失败
- 重做：先排查 5 类 readiness → 必要时重启 API

### 3.3 故障注入失败

- fail-open（注入失败但服务端继续提供服务）→ 高危失败
- 重做：检查 ProviderAdapterConfiguration 是否启用 fail-closed；SSO 同理

### 3.4 HA 类失败

- Redis 自动 fail-over > 30s → 演练不达
- 对象存储探针 HTTP_ERROR → 检查 endpoint / IAM

---

## 4. 责任人与签字（3 票齐全）

| 角色 | 签名项 |
|---|---|
| UAT Lead（tester）| 性能 + 路径完整 10 项 |
| ReleaseLead | 故障注入 + HA 8 项 |
| SREOnCall | 上线门禁 + archive 2 项 |

3 票齐全才能 `status=ACCEPTED`；否则保持 `SUBMITTED`。

---

## 5. UAT Lead 例行日程

- 季度首周三 13:00 UTC（先于 ROLLBACK_DRILL 14:00）
- 持续 90 分钟
- 工具：`scripts/slo-smoke.mjs` + SSH 隧道 + K8s probe
- 范围：U-01..U-20 全部

---

## 6. ProductionEvidence 登记

```http
PUT /api/v1/admin/platform/evidence/SLO_UAT
Content-Type: application/json
X-User-Role: admin

{
  "status": "ACCEPTED",
  "ownerUserId": "uat-lead-oncall",
  "expiresAt": "2026-12-31T00:00:00Z",
  "evidenceRef": "evidence-ref:v1:slo-uat:Q4-2026",
  "summary": "UAT passed; 20/20 cases; mean p95 180ms; 0 fault-open",
  "revision": 7
}
```

约束同其他 P1 证据：4 态 status、`evidenceRef` 正则、`summary` ≤240 字符、无敏感词。

---

## 7. 上线 checklist

- [ ] **C-01** 5 类 slo-smoke 路径在 §0 与 production baseurl 一致
- [ ] **C-02** 故障注入工具（蓝绿 / fail-injection）已部署
- [ ] **C-03** UAT Lead / ReleaseLead / SREOnCall 三组角色齐全
- [ ] **C-04** UAT 季度节奏（首周三 13:00 UTC）已写入日历
- [ ] **C-05** §2 全部 20 项用例已通过
- [ ] **C-06** 任一失败项已分类（性能 / 路径 / 注入 / HA / 门禁）
- [ ] **C-07** 3 票齐全签字
- [ ] **C-08** §8 自检 20 项完成
- [ ] **C-09** ProductionEvidence `SLO_UAT=ACCEPTED` + revision ≥ 1 登记
- [ ] **C-10** `verify-production-handoff.ps1 -Json -FailOnNotReady` 期望全部 9 个证据 ACCEPTED

---

## 8. SLO_UAT 自检清单（20 项）

路径：`docs/operations/slo-uat-evidence-YYYYMMDD/SLO_UAT-{01..20}.md`

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| U-01 | Skills list p95 ≤ 300ms | slo-smoke JSON |
| U-02 | Governance p95 ≤ 300ms | slo-smoke JSON |
| U-03 | Quality p95 ≤ 500ms | slo-smoke JSON |
| U-04 | Operations alert p95 ≤ 500ms | slo-smoke JSON |
| U-05 | Platform releases p95 ≤ 500ms | slo-smoke JSON |
| U-06 | Skills 列表 → 详情路径完整 | 端点 200 |
| U-07 | Quality 评估用例路径完整 | 端点 200 |
| U-08 | Release records → admit 决策完整 | ADMITTED |
| U-09 | Org directory 列表路径完整 | 端点 200 |
| U-10 | Provider probe 全 REACHABLE | admin probe |
| U-11 | JWT 过期 fail-closed | 401 JWT_TOKEN_EXPIRED |
| U-12 | JWT issuer mismatch fail-closed | 401 JWT_ISSUER_MISMATCH |
| U-13 | JWT audience mismatch fail-closed | 401 JWT_AUDIENCE_MISMATCH |
| U-14 | Provider 5xx fail-closed | audit |
| U-15 | JWKS 不可达 fail-closed | audit |
| U-16 | Redis sentinel 自动 fail-over ≤ 15s | 切换日志 |
| U-17 | 对象存储 HEAD REACHABLE ≥ 5min | probe 日志 |
| U-18 | WAL archive ≤ 5min lag | 监控 |
| U-19 | handoff 9/9 ACCEPTED | verify script |
| U-20 | lifecycle check pass | verify-lifecycle.ps1 |

---

## 9. 与其他证据 ID 的关联

| 关联证据 | 依赖 / 联动 |
|---|---|
| `SSO_ORGANIZATION` | §2.3 U-11..15 JWT 验证 |
| `REDIS_HA` | §2.4 U-16 |
| `OBJECT_STORAGE` | §2.4 U-17 |
| `BACKUP_PITR` | §2.4 U-18 |
| `PROVIDER_RUNTIME_GATEWAY` | §2.3 U-14 |
| `DATABASE_CAPACITY_SLO` | §2.1 U-01..05 直接复用 |

---

## 附录 A：相关源文件清单（不修改）

| 文件 | 职责 |
|---|---|
| `scripts/slo-smoke.mjs` | 有界并发 SLO 测量 |
| `scripts/verify-production-handoff.ps1` | 9 个证据 ID 校验 |
| `scripts/verify-lifecycle.ps1` | 整体回归 |
| `operations/ProductionEvidenceController.java` | 证据登记端点 |
