# M5.3-B 运行可观测性设计规格

日期：2026-08-18

## 1. 目标

为华为开发部门内部 Skill 平台补充最小可用的运行可观测性：管理员能够查看 API 运行健康、请求量、错误分布、延迟和安全边界事件；指标不包含请求体、Token、用户输入、完整查询参数或个人明细。

本阶段只实现进程内、可替换的指标采集和管理查询契约，不接入 Redis、Prometheus、外部告警平台或 SSO。这样可以先固定数据结构和验收口径，再在 M5.3-C 接入多实例聚合和生产监控。

## 2. 范围

### 2.1 指标采集

- 对 `/api/**` 请求采集请求总数、2xx/3xx 成功数、4xx 数、5xx 数和耗时直方图。
- 以固定 1 分钟桶保存最近 60 分钟数据，超出窗口的桶自动清理。
- 只记录聚合计数；不记录请求体、响应体、Token、用户 ID、Origin 值、查询参数或完整 URL。
- 延迟使用固定分桶计算近似 P50/P95，分桶为 `<10ms`、`10–49ms`、`50–99ms`、`100–249ms`、`250–499ms`、`500–999ms`、`1000–1999ms`、`>=2000ms`。

### 2.2 安全事件

统一计数以下事件：

| 事件 | 产生位置 |
|---|---|
| `RATE_LIMITED` | RateLimitFilter 拒绝请求 |
| `CSRF_ORIGIN_REJECTED` | OriginGuardFilter 拒绝请求 |
| `IDEMPOTENCY_REPLAY` | GlobalExceptionHandler 处理幂等重放 |
| `IDEMPOTENCY_CONFLICT` | GlobalExceptionHandler 处理幂等冲突 |
| `EXPORT_QUEUE_FULL` | ExportException 映射为队列满 |

### 2.3 管理查询 API

提供 `GET /api/v1/admin/operations/metrics?window=15m`：

- 仅 `admin` 可访问；其他角色返回现有 `403 FORBIDDEN` 错误契约。
- `window` 支持 `5m`、`15m`、`60m`，默认 `15m`；非法值返回 `400 INVALID_REQUEST`。
- 返回 `window`、`generatedAt`、`health`、`requests`、`latency`、`securityEvents`。
- `requests` 返回 `total`、`successes`、`clientErrors`、`serverErrors`。
- `latency` 返回 `p50Ms`、`p95Ms`、`maxMs`。
- `health` 返回 `status`、`packageStorage`、`invocationPersistence`；本阶段只报告已配置能力，不执行破坏性探测。

示例：

```json
{
  "data": {
    "window": "15m",
    "generatedAt": "2026-08-18T06:00:00Z",
    "health": {"status": "UP", "packageStorage": "CONFIGURED", "invocationPersistence": "ENABLED"},
    "requests": {"total": 120, "successes": 112, "clientErrors": 6, "serverErrors": 2},
    "latency": {"p50Ms": 20, "p95Ms": 250, "maxMs": 1800},
    "securityEvents": {"RATE_LIMITED": 3, "CSRF_ORIGIN_REJECTED": 1}
  },
  "requestId": "..."
}
```

## 3. 前端体验

- 管理员侧栏增加“运行监控”，只对 admin 展示。
- 页面展示健康状态、请求量、错误数、P50/P95、最大延迟和安全事件列表。
- 支持 5 分钟、15 分钟、60 分钟切换，切换后重新请求 API。
- viewer/reviewer/maintainer 不展示入口；被直接访问时展示后端 403 提示。
- 空指标展示“当前窗口暂无 API 请求”，不使用模拟数据。

## 4. 非目标

- 不引入 Actuator、Micrometer、Redis、Prometheus、Grafana 或外部告警平台。
- 不做跨实例聚合、历史长期存储、用户级审计、请求明细追踪和自动告警。
- 不改变现有业务 API 的响应字段和权限模型。

## 5. 验收标准

- 进程内指标服务能够在并发请求下正确聚合且不会暴露敏感字段。
- API 请求状态和延迟统计符合固定桶口径，窗口过期数据不会计入结果。
- 五类安全事件分别可被计数。
- admin 查询 200，非 admin 查询 403，非法窗口查询 400。
- 前端运行监控页仅 admin 可见，能展示真实 API 数据。
- 后端全量测试、前端全量测试、生产构建和真实 API 联调通过。
