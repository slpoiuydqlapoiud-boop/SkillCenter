# SkillCenter Redis HA 生产接入指南

> **证据 ID**：`REDIS_HA`
> **适配层**：`apps/api/src/main/java/...`（5 个 Redis 用户）
> **生产契约**：[`deploy/prod/.env.prod.template`](./.env.prod.template) 第 2 节 + [`apps/api/src/main/resources/application-prod.yml.example`](../../apps/api/src/main/resources/application-prod.yml.example) `spring.data.redis.*`
> **健康探针**：`/actuator/health/redis`（Spring Boot Actuator 自动注册）
> **自检清单**：见文末 [§8 REDIS_HA 证据 ID 自检清单](#8-redis_ha-证据-id-自检清单)

本指南面向**生产环境把 Redis 真实高可用化**的运维与平台工程师。平台侧所有 Redis 用户（5 个）已 100% 接入 Spring Data Redis，本指南只覆盖**Sentinel/Cluster 拓扑选型、生产配置、fail-over 演练、运维 Runbook**。

---

## 1. Redis 使用面与契约

### 1.1 平台内 5 个 Redis 用户

| 类 | 路径 | 用途 | 关键操作 |
|---|---|---|---|
| `RedisRuntimeSummaryStore` | `operations/RedisRuntimeSummaryStore.java` | 跨实例 runtime summary 共享 | `HSET` + `ZADD` Lua 原子去重 |
| `RedisOperationsAlertStateRepository` | `operations/RedisOperationsAlertStateRepository.java` | 告警状态共享 | `HSET` / `HGET` |
| `RedisOperationsMetricsStore` | `operations/RedisOperationsMetricsStore.java` | 操作 metrics 跨实例聚合 | `HSET` + Lua 聚合 |
| `RedisResumableUploadMetadataStore` | `packageupload/RedisResumableUploadMetadataStore.java` | 断点续传元数据（分布式上传） | `HSET` + Lua 限额/原子更新 |
| `RedisSkillSearchRefreshEventBus` | `search/RedisSkillSearchRefreshEventBus.java` | 搜索刷新事件总线（Streams） | `XADD` / `XREADGROUP` / `XACK` |

**关键观察**：
- 全部基于 `StringRedisTemplate`（Spring Data Redis 默认 Lettuce 客户端）
- 大量 Lua 脚本要求**原子性**和**同分区**（Cluster 模式下必须 hash tag）
- Streams 使用（仅 `RedisSkillSearchRefreshEventBus`）—— **Cluster 模式下 Streams key 必须落在同一 slot**

### 1.2 已有健康/就绪端点

| 端点 | 路径 | 权限 | 说明 |
|---|---|---|---|
| Spring Boot Actuator | `GET /actuator/health` | public | 含 `redis` 子健康（`RedisHealthIndicator`） |
| Spring Boot Actuator | `GET /actuator/health/redis` | public | 单独的 Redis 健康（自动注册） |
| Resumable Upload | `GET /api/v1/admin/platform/resumable-uploads/readiness` | admin | 含 Redis PING 探测，返回 `(backend, status)` |

### 1.3 不在本指南范围

- ❌ Redis 本身的安装/调优（属 DBA / 平台 DBA 职责）
- ❌ Redis Cloud / ElastiCache / MemoryDB 的运维 SLA（云厂商文档）
- ❌ Redis Cluster 的 slot 规划与 rehash 流程
- ❌ 持久化策略（RDB / AOF / 混合）—— 平台侧只关心 HA，不关心持久化

---

## 2. Sentinel vs Cluster 选型

### 2.1 对比

| 维度 | Sentinel | Cluster |
|---|---|---|
| **拓扑** | 1 master + N replica + 3 sentinel | N master × M replica（最少 3 master） |
| **数据分片** | 单分片（受单节点内存限制） | 自动分片 16384 slot |
| **fail-over** | Sentinel 选举，30s 内自动切换 | 每个 master 独立 failover，5-30s |
| **运维复杂度** | 低（无需 slot 规划） | 中（需 hash tag 隔离相关 key） |
| **客户端** | Lettuce 自动订阅 +switch-master | Lettuce 自动路由 slot + 重定向 |
| **适用场景** | < 100GB 数据，强一致优先 | > 100GB 数据，吞吐优先 |

### 2.2 平台适配结论

**推荐 Sentinel**，理由：
1. **5 个 Redis 用户的数据量均较小**（每用户 < 10GB）：runtime summary / alert state / operations metrics / upload metadata 都是 KV 类；搜索刷新事件 Streams 是低 QPS
2. **不需要分片**：受限于单 master 内存，但当前业务量 < 50GB
3. **运维更简单**：无需 slot 规划，无需 hash tag
4. **fail-over 更快**：Sentinel 切换后 Lettuce 通过订阅自动重连

**选 Cluster 的场景**：
- 业务增长到 > 100GB 数据量
- 写入吞吐需要 > 10 万 QPS
- 需要地理多活

### 2.3 强制约束

| 约束 | Sentinel | Cluster |
|---|---|---|
| Streams key（如 `skill-center:search:events`） | ✅ 自动落同一 master | ⚠️ 必须用 hash tag（如 `skill-center:{search}:events`） |
| Lua 脚本跨 key | ✅ 同一节点执行 | ⚠️ 必须 hash tag 保证同 slot |
| Lettuce fail-over | ✅ 自动 | ✅ 自动 |
| Redis ACL（用户名） | 可选 | 可选（推荐） |
| TLS | 推荐 | 强制（cross-node traffic） |

---

## 3. 生产推荐拓扑

### 3.1 Sentinel 拓扑（推荐）

```
                  ┌─────────────────────┐
                  │   Sentinel Node 1   │  (仲裁 / 监控)
                └─────────────────────┘
                  ┌─────────────────────┐
                  │   Sentinel Node 2   │
                  └─────────────────────┘
                  ┌─────────────────────┐
                  │   Sentinel Node 3   │
                  └─────────────────────┘
                         │
                  ┌──────┴──────┐
                  ▼             ▼
          ┌──────────────┐  ┌──────────────┐
          │   Master     │  │   Replica 1  │
          │  (R/W)       │  │   (R)        │
          └──────────────┘  └──────────────┘
                                ▲
                                │
                          ┌──────────────┐
                          │   Replica 2  │
                          │   (R)        │
                          └──────────────┘
```

**配置（生产示例）**：

```conf
# /etc/redis/redis.conf (master)
port 6379
bind 0.0.0.0
requirepass <password>
masterauth <password>           # replica 同步时使用
protected-mode yes
appendonly yes
appendfsync everysec
maxmemory-policy noeviction     # 平台不允许驱逐
```

```conf
# /etc/redis/sentinel.conf
port 26379
sentinel monitor skillcenter-redis-master <master-ip> 6379 2
sentinel auth-pass skillcenter-redis-master <password>
sentinel down-after-milliseconds skillcenter-redis-master 5000
sentinel failover-timeout skillcenter-redis-master 30000
sentinel parallel-syncs skillcenter-redis-master 1
```

### 3.2 Cluster 拓扑（仅大数据量场景）

```
                  ┌─────────────────────┐
                  │   Cluster Node A    │  Master (slots 0-5460)
                  │   Replica A1        │
                  └─────────────────────┘
                  ┌─────────────────────┐
                  │   Cluster Node B    │  Master (slots 5461-10922)
                  │   Replica B1        │
                  └─────────────────────┘
                  ┌─────────────────────┐
                  │   Cluster Node C    │  Master (slots 10923-16383)
                  │   Replica C1        │
                  └─────────────────────┘
```

**Cluster key 设计约束**：
- Streams key 必须 hash tag：例如 `skill-center:{search}:events`（所有 key 在同一 slot）
- Lua 脚本跨 key 也必须在同一 slot
- 平台代码当前**未使用 hash tag**——切 Cluster 需代码改造（详见 §6.3）

---

## 4. 配置矩阵

### 4.1 变量清单

| 变量 | 必填 | 适用模式 | 取值依据 |
|---|---|---|---|
| `SKILL_CENTER_RUNTIME_SUMMARY_BACKEND` | ✅ | 通用 | ProductionConfigCheck 强制 `redis` |
| `SKILL_CENTER_OPERATIONS_ALERT_STATE_BACKEND` | ✅ | 通用 | ProductionConfigCheck 强制 `redis` |
| `SKILL_CENTER_OPERATIONS_METRICS_STORAGE` | ✅ | 通用 | ProductionConfigCheck 强制 `redis` |
| `SKILL_CENTER_REDIS_MODE` | 推荐 | 通用 | `sentinel`（生产推荐） / `cluster` / `standalone`（仅 loopback 测试） |
| `SKILL_CENTER_REDIS_SENTINEL_MASTER` | Sentinel | sentinel master 服务名（如 `skillcenter-redis-master`） | 与 `sentinel monitor` 配置一致 |
| `SKILL_CENTER_REDIS_SENTINEL_NODES` | Sentinel | 逗号分隔 `host:port`，至少 3 个 | 分布在不同故障域 |
| `SKILL_CENTER_REDIS_CLUSTER_NODES` | Cluster | 逗号分隔 `host:port` | 至少 3 个 master |
| `SKILL_CENTER_REDIS_PASSWORD_REF` | ✅ | 通用 | `secret://` 引用；当前默认 Bean 解析 `secret://env/<NAME>` |
| `SKILL_CENTER_REDIS_USERNAME_REF` | 可选 | Cluster + Redis 6+ ACL | 同上 |
| `SKILL_CENTER_REDIS_TLS_ENABLED` | 推荐 | 通用 | 生产 `true` |

### 4.2 Spring 客户端配置

**Sentinel 模式**（`spring.data.redis.*`）：

```yaml
spring:
  data:
    redis:
      connect-timeout: 2s
      timeout: 2s
      lettuce:
        pool:
          enabled: true
          max-active: 32
          max-idle: 16
          min-idle: 4
          max-wait: 1s
      sentinel:
        master: ${SKILL_CENTER_REDIS_SENTINEL_MASTER}
        nodes: ${SKILL_CENTER_REDIS_SENTINEL_NODES}
        read-from: MASTER  # 生产推荐强一致
      password: ${SKILL_CENTER_REDIS_PASSWORD}
      username: ${SKILL_CENTER_REDIS_USERNAME:}
      ssl:
        enabled: ${SKILL_CENTER_REDIS_TLS_ENABLED:true}
```

**Cluster 模式**：

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: ${SKILL_CENTER_REDIS_CLUSTER_NODES}
        max-redirects: 3
        read-from: MASTER
      password: ${SKILL_CENTER_REDIS_PASSWORD}
      ssl:
        enabled: ${SKILL_CENTER_REDIS_TLS_ENABLED:true}
```

详见 [`apps/api/src/main/resources/application-prod.yml.example`](../../apps/api/src/main/resources/application-prod.yml.example) 第 2 节。

### 4.3 凭据注入

按 [`deploy/prod/secret-references.md`](./secret-references.md) 第 2 节约定：

```bash
# 生产推荐路径（替换 ProviderCredentialResolver Bean 后）
SKILL_CENTER_REDIS_PASSWORD_REF=secret://skillcenter/prod/redis/password

# 应急路径（当前默认 Bean，仅识别 secret://env/<NAME>）
SKILL_CENTER_REDIS_PASSWORD_REF=secret://env/SKILL_CENTER_REDIS_PASSWORD
```

两种路径都满足 `ProductionConfigCheck` 的 `^secret://[A-Za-z0-9._/-]+$` 校验。

---

## 5. Fail-over 与灾备

### 5.1 Sentinel 模式 Fail-over 流程

```
T0   Master 节点故障
     ↓
T1+5s Sentinel 半数以上确认主观下线（down-after-milliseconds=5000）
     ↓
T1+5s Sentinel 选举 Leader，发布 +odm 事件
     ↓
T1+10s Leader 选最优 replica 提升为新 master（failover-timeout=30000 内完成）
     ↓
T1+15s Lettuce 客户端收到 +switch-master 频道，自动重连到新 master
     ↓
T1+20s 平台无感知继续服务（除短暂 ops metrics 抖动）
```

**平台自动行为**：
- Lettuce 默认开启 `RedisSentinelTopologyRefresh` 和 `cluster topology refresh`，自动监听 +switch-master 频道
- 无需重启 API 节点
- 5-15s 内自动恢复（取决于网络延迟和 Sentinel 选举时间）

### 5.2 灾备演练步骤（季度必做）

| # | 步骤 | 验证点 | 记录 |
|---|---|---|---|
| 1 | `redis-cli -h <master> PING` | 返回 PONG | 演练前基线 |
| 2 | `redis-cli -h <master> INFO replication` | 看到 2 个 replica connected | 演练前基线 |
| 3 | 模拟 master 故障：`redis-cli -h <master> DEBUG SLEEP 60` | master 无响应 | 演练开始 |
| 4 | Sentinel 日志观察 | `+odm`、`+switch-master` 事件 | 时间戳 |
| 5 | 等待 30s 后 `redis-cli -h <new-master> PING` | 新 master 响应 | 切换时间 |
| 6 | 平台 API `/actuator/health/redis` | 始终返回 UP（fail-over 期间可能短暂 UNKNOWN） | 健康变化 |
| 7 | 上传一个测试包 → 触发 Redis 写 | 写操作成功 | 业务影响 |
| 8 | 恢复原 master（`redis-cli ... SHUTDOWN NOSAVE` 后启动） | Sentinel 把它设为 replica | 恢复时间 |
| 9 | 演练总结报告 | 切换耗时 / 业务影响 / 改进项 | 归档 |

### 5.3 Cluster 模式 Fail-over

每个 Master 独立 fail-over，集群内其他分片不受影响。fail-over 流程与 Sentinel 类似，由 gossip 协议完成（无需 sentinel）。Lettuce 自动感知并重定向。

### 5.4 灾难场景

| 现象 | 处置 |
|---|---|
| Sentinel 半数以上宕机 | 平台无法自动 fail-over；人工介入；考虑双 region Sentinel |
| 整个 Redis 集群宕机 | 所有 Redis-backed 功能降级（runtime summary / alert state / upload metadata / search events）；本地 JSON 兜底（`backend=memory`/`json`）—— **仅紧急**，写入审计 |
| 网络分区 | Sentinel 切换可能脑裂；优先保证数据安全（Redis 默认拒绝写） |
| 单个 Redis 节点 OOM | `maxmemory-policy=noeviction` 配置下会拒绝写而非驱逐；监控告警 |

---

## 6. 网络与白名单

### 6.1 端口清单

| 用途 | 端口 | 协议 | 备注 |
|---|---|---|---|
| Redis 数据 | 6379 | TCP（推荐 TLS） | client → master / replica |
| Sentinel | 26379 | TCP | API 节点 → Sentinel |
| Cluster bus | 16379 | TCP | 仅 Cluster 模式；节点间 gossip |

### 6.2 网络白名单

```
出向（API 节点 → Redis）：
  6379/TCP    → Redis master + replicas（生产建议内网）
  26379/TCP   → Sentinel 节点（≥3 个）
  16379/TCP   → Cluster 节点（仅 Cluster 模式）

出向（Redis → Redis）：
  6379/TCP    → replica 同步（masterauth）
  26379/TCP   → sentinel 互相通信
  16379/TCP   → cluster bus gossip
```

### 6.3 凭据与 TLS

- 凭据：master 与所有 replica 必须使用**相同密码**（否则 replica 同步失败）
- TLS：生产强制开启；平台注入 `SKILL_CENTER_REDIS_TLS_ENABLED=true`
- 证书：企业内部 CA 时需注入 JVM truststore

---

## 7. 上线前 8 步 Checklist

| # | 步骤 | 责任方 | 证据 |
|---|---|---|---|
| 1 | Redis 拓扑搭建（Sentinel/Cluster） | DBA / 平台 DBA | `redis-cli INFO replication` 输出 |
| 2 | Sentinel quorum ≥ 3 节点 | DBA | `SENTINEL MASTER skillcenter-redis-master` 输出 |
| 3 | Replica 同步成功 | DBA | `redis-cli -h <replica> INFO replication` role=slave |
| 4 | 平台 `.env.prod.<cluster>` 第 2 节按 §4 替换占位 | 运维 | git diff |
| 5 | Secret Manager 注入 Redis 密码 | 安全 + 运维 | ESO 同步状态 |
| 6 | `spring.data.redis.sentinel.*` 配置注入 | 运维 | K8s ConfigMap diff |
| 7 | `pwsh scripts/verify-production-config.ps1 -Json` 3 项 redis backend READY | 运维 | JSON 输出 |
| 8 | UAT：手动 `kill -9 <master>` → 验证平台自动恢复 | QA + DBA | UAT 报告 + 演练记录 |

---

## 8. REDIS_HA 证据 ID 自检清单

> 每条对应 ProductionConfigCheck 或运行时验证点；上线 UAT 时逐项打勾。

| # | 验证项 | 验证方式 | 通过判据 |
|---|---|---|---|
| 1 | `SKILL_CENTER_RUNTIME_SUMMARY_BACKEND == redis` | `verify-production-config.ps1` | `operations.runtime-summary` READY |
| 2 | `SKILL_CENTER_OPERATIONS_ALERT_STATE_BACKEND == redis` | 同上 | `operations.alert-state` READY |
| 3 | `SKILL_CENTER_OPERATIONS_METRICS_STORAGE == redis` | 同上 | `operations.metrics` READY |
| 4 | Redis 拓扑：Sentinel ≥ 3 节点 / Cluster ≥ 3 master | `redis-cli -h <sentinel> SENTINEL MASTER <name>` 或 `CLUSTER INFO` | quorum 达成 |
| 5 | Replica 同步延迟 < 5s | `redis-cli -h <replica> INFO replication` `master_last_io_seconds_ago` | < 5 |
| 6 | Redis 启用密码 + ACL（生产） | `INFO` 或 `redis-cli ACL WHOAMI` | 密码有效 |
| 7 | TLS 启用 | `redis-cli --tls -h <host> PING` | PONG |
| 8 | `GET /actuator/health/redis` 返回 UP | admin Token + curl | `status: UP` |
| 9 | `GET /api/v1/admin/platform/resumable-uploads/readiness` 返回 `READY` | admin Token + curl | `status=READY, backend=redis` |
| 10 | 平台启动无 Redis 连接异常 | 应用启动日志 | 无 `RedisConnectionFailureException` |
| 11 | fail-over 后 Lettuce 自动重连 | 手动 kill master → 等 30s → 调 API | 持续 200 |
| 12 | fail-over 期间无平台重启 | 平台 metrics / Pod 状态 | API 节点未 OOM/restart |
| 13 | replica 提升后写入新数据 | fail-over 后上传测试包 | 业务正常 |
| 14 | 旧 master 恢复后变 replica | `INFO replication` role=slave | 自动降级 |
| 15 | Streams 消息不丢（仅 Sentinel/同 master） | XADD → master 切换 → XREAD | 消息可达 |
| 16 | Lua 脚本不破坏（fail-over 后） | fail-over 后触发 runtime summary 写 | 写入成功 |
| 17 | 平台 metrics 含 `redis.*` 指标（来自 Lettuce / Redis exporter） | Prometheus scrape | 指标存在 |
| 18 | Redis 满内存告警 | 设置 `maxmemory=1mb` → 写 → 告警 | Alertmanager 触发 |
| 19 | 季度 fail-over 演练记录 | 演练 runbook + 时间戳 | 归档 |
| 20 | Redis 季度凭据轮转记录 | Secret Manager 审计日志 | 轮转记录 |

> **证据归档**：上线后把上述每条验证项的截图/JSON 输出归档到 `docs/operations/redis-ha-uat-evidence-YYYYMMDD/REDIS_HA-{01..20}.md`。

---

## 9. 故障排查速查

| 现象 | 根因 | 排查路径 |
|---|---|---|
| `/actuator/health/redis` 返回 DOWN | Redis 连接失败 | 看应用日志 `RedisConnectionFailureException` + sentinel 状态 |
| Lettuce 连接超时 | 网络或防火墙 | `redis-cli -h <host> -p 6379 PING` |
| fail-over 后部分请求失败 | Lettuce 未及时刷新拓扑 | 调高 `spring.data.redis.lettuce.cluster.refresh.period` 或 `sentinel.refresh.period` |
| Sentinel quorum 丢失 | sentinel 节点数不足 | 检查 sentinel 节点存活 + 网络 |
| Cluster 模式 `MOVED` 错误频繁 | 客户端未自动重定向 | Lettuce `cluster.max-redirects=3` 是否配置 |
| Streams 消息积压 | consumer 未 ack | `XPENDING <stream> <group>` 检查 |
| 写操作返回 OOM | Redis 满内存 | 扩容或调 `maxmemory`；监控告警 |
| 凭据错误 | 密码不一致 / ACL 问题 | `redis-cli -h <host> AUTH <password>` + `ACL WHOAMI` |
| TLS 握手失败 | 证书链不全 | `openssl s_client -connect <host>:6379` 看证书链 |

---

## 10. 监控指标（推荐 Prometheus 抓取）

**Lettuce / Spring Data Redis 暴露指标**：
- `lettuce.command.firstresponse.duration` — 命令响应时间
- `lettuce.command.completion.duration` — 命令总耗时

**Redis Exporter（推荐部署）**：
- `redis_up` — Redis 实例存活
- `redis_connected_slaves` — replica 连接数
- `redis_used_memory_bytes` — 内存使用
- `redis_commands_processed_total` — 命令 QPS
- `redis_keyspace_hits_total / misses_total` — 命中率
- `redis_net_input_bytes_total / redis_net_output_bytes_total` — 网络吞吐

**Alertmanager 告警规则（推荐）**：
- `redis_up == 0` 持续 30s → P2 告警
- `redis_connected_slaves < 2` 持续 1m → P3 告警
- `redis_used_memory_ratio > 0.8` 持续 5m → P3 告警
- `redis_commands_latency_p99 > 50ms` 持续 5m → P3 告警

---

**版本**：v1.0（2026-09-08）  
**责任人**：平台架构组 + DBA / 平台 DBA  
**变更历史**：首版（Redis HA 生产接入指南）