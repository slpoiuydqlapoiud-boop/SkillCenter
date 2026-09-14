# DATABASE_CAPACITY_SLO 运行手册

版本：V1（2026-09-08）
证据 ID：**DATABASE_CAPACITY_SLO**（P1 上线门禁）
测量范围：PostgreSQL（governance/quality/release/benchmark/production-evidence 等 9 个 schema）
工具：`scripts/slo-smoke.mjs`（已存在）
周期：月度趋势评审 + 季度容量规划 + 上线前 SLO 烟测

本手册约束数据库容量预期、连接数上限、查询延迟门槛与季度规划节奏，不修改任何持久化代码。基础工具 `slo-smoke.mjs` 已提供有界并发测量、自动统计与阈值判定。

---

## 0. 范围与基础

适用范围：所有 `SKILL_CENTER_*_BACKEND=postgresql` 的 schema。生产配置由 `deploy/prod/.env.prod.template` 第 1 节统一约束。

| Backend | Schema | 数据特征 | 主要写者 |
|---|---|---|---|
| persistence | 治理记录、Scope、Relation | 中等写入（avg 20 RPS） | governance、quality |
| governance | 角色、组织目录、决策单 | 高写（avg 100 RPS） | governance loop |
| quality | 评估证据、跨域指标 | 中写（avg 50 RPS） | quality evidence |
| benchmark | 评测套件 | 写后基本只读 | benchmark jobs |
| release | 发布记录、回滚审批 | 低写高读 | release service |
| production-evidence | 9 个证据元数据 | 低写超高读 | handoff check |
| execution-environment | Skill 环境资产 | 中读 | quality |
| skill-scope / skill-relation | Skill 范畴 + 关联 | 中读 | governance |
| lifecycle-projection | 生命周期投影 | 高读 | lifecycle |

---

## 1. SLO 阈值（默认 + 推荐）

| 指标 | 默认阈值 | 推荐生产阈值 |
|---|---|---|
| p50 延迟（读写） | ≤ 50 ms | ≤ 30 ms |
| p95 延迟 | ≤ 500 ms | ≤ 200 ms |
| p99 延迟 | ≤ 1 000 ms | ≤ 500 ms |
| 最小成功率 | ≥ 99 % | ≥ 99.9 % |
| 连接池利用率 | ≤ 80 % | ≤ 60 % |
| 复制延迟 | ≤ 5 s | ≤ 2 s |
| 磁盘水位 | ≤ 70 % | ≤ 60 % |
| 表膨胀（relkind=r）/ 总大小 | ≤ 30 % | ≤ 20 % |
| 索引使用率（pg_stat_user_indexes.idx_scan） | ≥ 50 % | ≥ 80 % |
| 死元组占比（`n_dead_tup/live_tup`） | ≤ 10 % | ≤ 5 % |

> 阈值变更须以 ADR 形式提交 review；不允许本期 hotfix 改动阈值绕过告警。

---

## 2. 5 类基础测量

### 2.1 读写延迟（每分钟）

```sql
SELECT
  datname,
  xact_commit AS commits,
  xact_rollback AS rollbacks,
  blks_hit / NULLIF(blks_hit + blks_read, 0)::float AS cache_hit_ratio,
  tup_fetched AS selects,
  tup_inserted + tup_updated + tup_deleted AS mutations
FROM pg_stat_database
WHERE datname = current_database();
```

### 2.2 长事务（> 30s）

```sql
SELECT pid, state, now() - xact_start AS duration, query, wait_event
FROM pg_stat_activity
WHERE xact_start IS NOT NULL AND now() - xact_start > INTERVAL '30 seconds'
ORDER BY duration DESC;
```

### 2.3 表膨胀 top-N

```sql
SELECT
  relname,
  pg_size_pretty(pg_total_relation_size(oid)) AS total_size,
  n_live_tup, n_dead_tup,
  ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct
FROM pg_stat_user_tables
WHERE n_live_tup > 1000
ORDER BY n_dead_tup DESC LIMIT 20;
```

### 2.4 索引缺失（high-seq-scan 表）

```sql
SELECT relname, seq_scan, idx_scan,
       ROUND(100.0 * seq_scan / NULLIF(seq_scan + idx_scan, 0), 1) AS seq_pct
FROM pg_stat_user_tables
WHERE seq_scan > 1000
ORDER BY seq_pct DESC LIMIT 20;
```

### 2.5 连接数与锁

```sql
SELECT state, count(*) FROM pg_stat_activity GROUP BY state;
SELECT count(*) AS exclusive_locks FROM pg_locks WHERE mode LIKE 'Exclusive%';
```

---

## 3. slo-smoke 烟测（5 类只读路径）

### 3.1 Skills 列表端点

```bash
node scripts/slo-smoke.mjs \
  --url="${SKILL_CENTER_PUBLIC_BASE_URL:-http://127.0.0.1:8081}/api/v1/skills" \
  --duration-seconds=60 \
  --concurrency=8 \
  --request-timeout-ms=2000 \
  --p95-ms=300 \
  --min-success-rate=99.9 \
  --output=artifacts/slo-skill-list-$(date -u +%Y%m%d).json
```

### 3.2 五类只读 path

- `/api/v1/skills` — 列表
- `/api/v1/skills/{id}` — 详情
- `/api/v1/governance/decisions` — 决策列表
- `/api/v1/quality/evidences` — 质量证据列表
- `/api/v1/operations/alert-summary` — 告警汇总

> slo-smoke 不发送请求正文、不携带认证头、不记录响应正文，对应 ProductionConfigCheck 第 1 节的 metadata-only 边界。

### 3.3 阈值与解读

退出码：
- `0` — 通过阈值
- `2` — 测量完成但**未达**阈值
- `1` — 参数 / 运行错误

通过阈值不直接置 `ACCEPTED`；最终验收由责任人确认测试窗口、负载模型、脱敏、SLO 阈值、告警、容量余量、回滚影响。

---

## 4. 容量趋势（月度评审）

### 4.1 输入数据

- slo-smoke 输出（5 类 × 月度）
- `pg_stat_database` 周度快照
- `pg_stat_user_tables` 死元组曲线
- Prometheus `pg_*` 指标（30 天）

### 4.2 评审产物

- 月度趋势报告：`docs/operations/capacity-evidence-YYYYMM/capacity-trend.md`
- 季度容量规划：`docs/operations/capacity-evidence-YYYYQQ/capacity-plan.md`
- 二者作为 ProductionEvidence `DATABASE_CAPACITY_SLO` 的 `evidenceRef` 来源

### 4.3 扩容触发条件

- 连接数均值 > 推荐阈值 60 %
- p95 延迟均值月环比 +20 %
- 任意 schema 磁盘水位 > 70 %

任一触发 → 容量规划事件 + 季度预算评估。

---

## 5. 季度容量规划

### 5.1 范围

- 容量：连接数 / IOPS / 磁盘 / 备份窗口
- 性能：5 类端点延迟趋势
- 索引：缺失与未用索引清单
- Schema：表膨胀 top-10 与 VACUUM 计划

### 5.2 6 步流程

1. 拉取 90 天 slo-smoke 与 Prometheus 指标
2. 计算月环比 / 年同比 + 剩余空间
3. 评估下一季度峰值预估
4. 决策：扩容 / 切片 / 索引优化 / 分区表
5. 与基础设施团队 sync；纳入季度预算
6. 写入 `capacity-plan.md` 并归档到 `ProductionEvidence`

---

## 6. ProductionEvidence 登记

```http
PUT /api/v1/admin/platform/evidence/DATABASE_CAPACITY_SLO
Content-Type: application/json
X-User-Role: admin

{
  "status": "ACCEPTED",
  "ownerUserId": "dba-oncall",
  "expiresAt": "2026-12-31T00:00:00Z",
  "evidenceRef": "evidence-ref:v1:db-capacity:trend-2026Q4",
  "summary": "Monthly SLO passed; p95 180ms; cache-hit 98.5%; no expansion trigger",
  "revision": 7
}
```

约束同 `BACKUP_PITR`：4 态 status、`evidenceRef` 正则、`summary` ≤240 字符、无敏感词。

---

## 7. 上线 checklist

- [ ] **C-01** slo-smoke.mjs 在 5 类只读路径通过（p95 ≤ 推荐阈值）
- [ ] **C-02** `pg_stat_user_tables` 无 dead_tup_pct > 30 %
- [ ] **C-03** `pg_stat_user_tables.idx_scan/seq_scan ≥ 80/20`
- [ ] **C-04** 复制延迟 ≤ 2s
- [ ] **C-05** 磁盘水位 ≤ 60 %
- [ ] **C-06** 连接池利用率 ≤ 60 %
- [ ] **C-07** 月度趋势报告已归档
- [ ] **C-08** 季度容量规划最近一次审批 ≤ 90 天
- [ ] **C-09** 完成 §8 的 20 项自检
- [ ] **C-10** `verify-production-handoff.ps1 -Json -FailOnNotReady` 期望 `DATABASE_CAPACITY_SLO=ACCEPTED` + revision ≥ 1

---

## 8. DATABASE_CAPACITY_SLO 自检清单（20 项）

路径：`docs/operations/capacity-evidence-YYYYMMDD/DATABASE_CAPACITY_SLO-{01..20}.md`

| ID | 可验证产物 | 通过判定 |
|---|---|---|
| D-01 | 5 类只读路径 slo-smoke 通过 | exit 0 |
| D-02 | p95 ≤ 推荐阈值（5 路径） | JSON `latencyMs.p95` |
| D-03 | 成功率 ≥ 99.9 % | JSON `successRate` |
| D-04 | 缓存命中率 ≥ 95 % | `pg_stat_database.cache_hit_ratio` |
| D-05 | 写活跃连接数 ≤ 池利用 60 % | `pg_stat_activity` |
| D-06 | 复制延迟 ≤ 2s | replica `pg_last_xact_replay_timestamp` |
| D-07 | 磁盘水位 ≤ 60 % | `pg_tablespace_location` + OS level |
| D-08 | 表膨胀 dead_tup ≤ 5 % | `pg_stat_user_tables` |
| D-09 | 索引使用率 ≥ 80 % | `pg_stat_user_indexes.idx_scan` |
| D-10 | 长事务无 > 30s | `pg_stat_activity` |
| D-11 | 死锁数 30 天均值 ≤ 1 / 天 | `pg_stat_database.deadlocks` |
| D-12 | 备份窗口 ≤ 30 分钟 | basebackup 调用日志 |
| D-13 | 表数量 ≤ 业务上限 | 元数据 |
| D-14 | 大表（> 100GB）清单 + 分区策略 | 配置审计 |
| D-15 | 无 `EXCLUSIVE` 锁长持 | `pg_locks` 抽样 |
| D-16 | 月度趋势报告 30 天内最近 | 文件归档 |
| D-17 | 季度规划 90 天内最近 | 文件归档 |
| D-18 | slo-smoke JSON 报告可被引用为 evidenceRef | 文件归档 |
| D-19 | ProductionEvidence `DATABASE_CAPACITY_SLO=ACCEPTED` + revision ≥ 1 | handoff script |
| D-20 | 扩容触发条件未命中或已审批 | 文件归档 |

---

## 9. 失败模式与应急

| 模式 | 触发条件 | 应急 |
|---|---|---|
| p95 突破 | 月环比 +20 % | 触发季度规划事件 |
| 死锁堆积 | 30 天均值 > 1 / 天 | 锁重写 + 索引 |
| 长事务 | 单事务 > 5min | 应用层取消 |
| 复制断裂 | replay lag > 1min | 主从切换决策 |
| 磁盘快满 | 水位 > 80 % | 紧急归档 + 暂停写入 |

应急动作需由 CapacityOwner 审批，所有变更入审计台账。

---

## 附录 A：相关源文件清单（不修改）

| 文件 | 职责 |
|---|---|
| `scripts/slo-smoke.mjs` | 有界并发 SLO 测量 |
| `scripts/slo-smoke.test.mjs` | 单元测试 |
| `scripts/verify-production-handoff.ps1` | 调用 `ProductionEvidence` |
| `operations/ProductionEvidence.java` | evidence 状态机 |
