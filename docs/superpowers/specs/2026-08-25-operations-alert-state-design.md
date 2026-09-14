# Operations 告警状态持久化与多实例去重设计

## 目标

让 Operations Alert 的 `ACTIVE`/`RESOLVED` 状态、首次触发时间和通知迁移具备重启恢复与多实例一致性，同时保持本地开发可运行、生产配置显式、Redis 故障可观测且不静默伪造共享状态。

## 范围与不变项

- 只改告警状态存储与状态迁移；不改变现有告警规则、阈值、API 响应结构或 webhook payload。
- 默认 `skill-center.operations.alert-state-backend=memory`，保持当前单进程开发语义，并在生产 Readiness 标记为 `DEGRADED`。
- 显式选择 `redis` 时只装配 Redis 状态仓库；连接失败不回退 memory，Readiness 返回 `NOT_READY`，告警查询仍返回当前评估结果但不宣称跨实例去重可用。
- Redis 状态迁移使用 Lua 一次性读取旧状态、计算新状态并写入，只有状态边沿发生时才触发一次通知。
- 状态仓库只保存 rule key、active、firstTriggeredAt、lastEvaluatedAt 等安全元数据，不保存 Prompt、Trace、请求正文、凭据或原始异常。

## 组件与数据流

1. `OperationsAlertService.evaluate()` 计算每条规则的目标 active 状态。
2. `OperationsAlertStateRepository.transition(key, active, evaluatedAt)` 原子返回旧状态、新状态和是否发生边沿迁移。
3. Service 用新状态生成现有 `OperationsAlertSnapshot`；仅在 `transitioned=true` 且为首次 ACTIVE 或从 ACTIVE 到 RESOLVED 时调用既有 NotificationSink。
4. `MemoryOperationsAlertStateRepository` 用同步 Map 支持本地测试和默认模式。
5. `RedisOperationsAlertStateRepository` 用固定 key 前缀下的 Hash 字段和 Lua 脚本共享状态；不使用动态 Redis key，不回显连接信息。
6. `PlatformReadinessService` 展示 `OPERATIONS_ALERT_STATE` 组件：memory 为 `DEGRADED`，Redis PING 成功为 `READY`，PING 异常/非 PONG 为 `NOT_READY`。

## 状态迁移语义

- 第一次评估为 ACTIVE：创建状态，`firstTriggeredAt=evaluatedAt`，迁移为 ACTIVE，发送一次通知。
- 已 ACTIVE 再评估 ACTIVE：保留 `firstTriggeredAt`，不发送通知。
- 已 ACTIVE 评估 RESOLVED：保留 `firstTriggeredAt`，迁移为 RESOLVED，发送一次 RESOLVED 通知。
- 第一次评估为 RESOLVED：创建非活动状态，不发送通知。
- 已 RESOLVED 再评估 RESOLVED：保持非活动状态，不发送通知。
- Redis Lua 返回的旧状态和边沿结果必须与单进程实现一致；错误时抛出稳定持久化异常，由现有 API 错误边界处理。

## 安全与故障边界

- Redis 配置只接受固定 backend 名称和受控 key 前缀；状态值与返回摘要做控制字符清理和长度限制。
- Redis 故障不能自动切到 memory，否则会造成重复通知；Readiness 必须暴露稳定 reason code `OPERATIONS_ALERT_STATE_REDIS_UNAVAILABLE`。
- Webhook 仍由已有 sink 负责，状态仓库故障与 webhook 发送失败互相隔离。

## 验收标准

- 单进程测试覆盖五种状态迁移和通知边沿去重。
- Redis 仓库测试确认状态迁移通过单个 Lua 脚本，并覆盖 PING 成功、非 PONG、异常 fail-closed。
- Platform Readiness 测试确认 memory/Redis 状态及稳定阻塞码。
- API 全量、Web 全量、生产构建、生命周期 verifier 和 `git diff --check` 全部通过。
- 文档明确 memory/Redis 切换、重启恢复、Redis 故障和真实 HA/容量/故障转移验收边界。
