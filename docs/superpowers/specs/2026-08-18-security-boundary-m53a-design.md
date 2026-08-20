# M5.3-A API 安全边界设计规格

日期：2026-08-18

## 1. 目标

为华为开发部门内部 Skill 平台补齐第一层 API 安全边界，覆盖高风险写入和制品访问接口的限流、幂等/重放拒绝、浏览器来源校验与统一安全响应头。保持现有本地 `X-User-Id` / `X-User-Role` 身份适配器和 API 路径不变，不宣称已经接入企业 SSO。

## 2. 本阶段范围

### 2.1 限流接口

仅对高成本或可产生副作用的接口限流，普通市场查询不受本阶段限流影响：

| scope | 方法与路径 | 默认窗口 | 默认上限 | 主体键 |
|---|---|---:|---:|---|
| `INSTALLATION_CREATE` | `POST /api/v1/skills/{skillId}/installations` | 60 秒 | 30 | `X-User-Id`，缺失时为远端地址 |
| `DISTRIBUTION_CONSUME` | `POST /api/v1/distribution/authorizations/{tokenId}/consume` | 60 秒 | 60 | `X-User-Id`，缺失时为远端地址 |
| `DISTRIBUTION_DOWNLOAD` | `GET /api/v1/distribution/artifacts/{skillId}/{version}` | 60 秒 | 60 | `X-User-Id`，缺失时为远端地址 |
| `INVOCATION_INGEST` | `POST /api/v1/events/invocations[/{batch}]` | 60 秒 | 120 | `X-User-Id`，其次 `X-Client-Id`，最后远端地址 |
| `EXPORT_CREATE` | `POST /api/v1/admin/exports` | 60 秒 | 10 | `X-User-Id`，缺失时为远端地址 |

限流使用进程内固定窗口计数器，窗口边界和计数更新必须线程安全。超限返回 `429`，错误码为 `RATE_LIMITED`，响应包含 `Retry-After`（秒）、`X-RateLimit-Limit`、`X-RateLimit-Remaining` 和 `X-RateLimit-Reset`。计数器不写入治理快照，后续可替换 Redis 适配器。

### 2.2 幂等与重放防护

- 分发授权消费继续使用现有一次性 token；已消费、过期或无效 token 继续返回稳定错误。
- 调用事件继续以 `eventId` 幂等；批量上报继续逐事件返回重复结果，不重复计数。
- 安装创建和管理员导出创建支持可选 `Idempotency-Key` 请求头。键长度为 1–128 个 ASCII 字符，按 `scope + actor + key` 隔离，默认保留 15 分钟。
- 同一键在保留期内再次提交时返回 `409 IDEMPOTENCY_REPLAY`；同一键但请求指纹不同返回 `409 IDEMPOTENCY_CONFLICT`。未携带该请求头的旧客户端保持兼容。
- 幂等记录使用进程内 TTL 存储，不把请求体、token 或 Skill 内容写入日志；服务重启后的持久化幂等存储留给后续 Redis/数据库阶段。

### 2.3 浏览器写请求保护

- 对 `/api/**` 的 `POST`、`PUT`、`PATCH`、`DELETE` 请求：若带 `Origin`，必须匹配配置的允许来源；缺失 `Origin` 的 CLI/服务端请求不因本规则拒绝。
- 默认允许 `http://127.0.0.1:5173`、`http://localhost:5173`，生产通过 `skill-center.security.allowed-origins` 覆盖。
- 来源不允许时返回 `403 CSRF_ORIGIN_REJECTED`，不执行控制器副作用。
- 本阶段不新增 Cookie 会话；接入企业 SSO 后再增加同步令牌或框架级 CSRF 令牌。

### 2.4 统一安全响应头

所有 API 响应增加：

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`

已有业务响应头（如下载响应的 `Cache-Control`、`Content-Disposition`）必须保留。

## 3. 错误契约

统一沿用 `ErrorEnvelope`：

| HTTP | code | 触发条件 |
|---:|---|---|
| 400 | `IDEMPOTENCY_KEY_INVALID` | 幂等键为空白、超过 128 字符或包含控制字符 |
| 403 | `CSRF_ORIGIN_REJECTED` | 浏览器写请求来源不在允许列表 |
| 409 | `IDEMPOTENCY_REPLAY` | 同一主体、范围和幂等键重复提交相同请求 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一幂等键对应不同请求指纹 |
| 429 | `RATE_LIMITED` | 固定窗口内超过接口上限 |

错误响应不得包含 token、请求体、堆栈、绝对文件路径或用户输入原文。

## 4. 组件与数据流

1. `RequestIdFilter` 先生成或透传 request ID。
2. `SecurityHeadersFilter` 写入安全响应头。
3. `OriginGuardFilter` 校验浏览器写请求来源。
4. `RateLimitFilter` 根据方法和路径匹配 scope，调用 `RateLimitService`，超限短路并返回错误契约。
5. 控制器在安装创建、导出创建处调用 `IdempotencyService`；业务成功后保留键，业务异常时释放键。
6. 现有授权 token、调用事件 eventId 和导出下载 token 的一次性/幂等逻辑继续作为第二层保护。

## 5. 配置

新增配置：

```yaml
skill-center:
  security:
    allowed-origins:
      - http://127.0.0.1:5173
      - http://localhost:5173
    idempotency-ttl-seconds: 900
    rate-limit:
      window-seconds: 60
      installation-create: 30
      distribution-consume: 60
      distribution-download: 60
      invocation-ingest: 120
      export-create: 10
```

测试配置将限流窗口缩短、上限设为 1，并使用独立时钟或独立实例，避免测试之间共享计数。

## 6. 非目标与后续替换点

- 不接入 Redis、数据库、企业 SSO、对象存储、API 网关或外部监控平台。
- 不改变市场查询、审核读取和统计读取接口的限流行为。
- 不把本地 Origin 校验描述为完整企业级 CSRF 方案。
- M5.3-B 继续处理指标、健康检查、告警和安全事件观测；M5.3-C 处理备份恢复、对象存储生命周期和 RC 门禁。

## 7. 验收标准

- 各限流 scope 达到上限后返回 429、Retry-After 和稳定错误码，窗口滚动后恢复。
- 并发请求不会超过允许计数；不同主体互不影响。
- 安装/导出幂等键重复和冲突请求均被拒绝，业务副作用只产生一次。
- 已消费授权、重复 eventId 和重复下载 token 的现有行为不回归。
- 非法 Origin 写请求在控制器前被拒绝；无 Origin 的 CLI 请求保持兼容。
- API 响应头全部存在且下载业务头仍然存在。
- 后端全量测试、前端全量测试和生产构建通过；真实 API 冒烟覆盖 429、403、幂等冲突和正常 200/202 路径。
