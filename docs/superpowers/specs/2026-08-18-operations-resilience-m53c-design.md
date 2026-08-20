# M5.3-C 运行韧性与发布门禁设计

日期：2026-08-18

## 目标

在 M5.3-B 进程内运行指标基础上，补齐可恢复的指标存储、Prometheus 兼容输出、阈值告警和发布候选（RC）自动门禁，同时保持当前项目无需 Redis、Prometheus Server、Grafana、Alertmanager 或 SSO 即可运行。

## 范围与边界

本阶段只处理运行指标和平台内部告警，不引入新的外部基础设施。指标只保留最近 60 分钟的聚合桶，不记录请求正文、Token、用户 ID、完整 URL 参数或包内容。默认使用本地 JSON 文件存储；通过存储接口为未来 Redis/集中式聚合预留替换点，但本阶段不宣称已实现多实例共享。

## 方案

### 指标存储

`OperationsMetricsService` 通过 `OperationsMetricsStore` 读写固定 1 分钟桶。默认实现为 `JsonOperationsMetricsStore`：启动时读取最多 60 个桶，写入时使用同目录临时文件并原子替换目标文件；文件损坏或不可读时记录降级状态并从空桶启动，不能阻断 API 启动。测试构造器继续使用内存存储，避免测试污染本地数据。

存储文件只包含聚合计数：桶时间戳、HTTP 状态分类、延迟直方图、最大延迟和允许的安全事件代码。健康状态增加 `metricsPersistence` 字段，取值为 `ENABLED`、`DISABLED` 或 `DEGRADED`。

### Prometheus 输出

新增 `GET /internal/metrics` 文本接口。接口使用配置项 `skill-center.operations.metrics-token` 校验 `X-Metrics-Token`，未配置 Token 时默认关闭并返回 404；Token 不出现在响应或日志。输出固定名称和标签集合：请求总量/成功/4xx/5xx、P95/最大延迟、安全事件计数和持久化状态。指标值均为聚合数值，不能包含主体、请求路径、查询参数或敏感字段。

### 告警

新增三类阈值规则：

- `P95_LATENCY`: P95 延迟大于等于配置阈值（默认 1000ms）。
- `SERVER_ERROR_RATE`: 5xx / 总请求数大于等于配置阈值（默认 5%，至少 10 个请求才评估）。
- `SECURITY_EVENT_COUNT`: 选定安全事件计数大于等于配置阈值（默认 10）。

`OperationsAlertService` 根据选定窗口生成规则快照，维护 active/resolved 状态和首次/最近触发时间。状态只保留当前进程内的告警状态，指标持久化恢复后下一次评估即可重新计算；不会发送外部通知。管理员接口 `GET /api/v1/admin/operations/alerts?window=15m` 返回规则、当前值、阈值、状态和评估时间。

### RC 门禁

新增 `OperationsReleaseGate`，在测试配置下检查：JSON 存储可写并能恢复、Prometheus 输出包含固定指标且无敏感字段、告警能从 active 转为 resolved、指标健康状态不为 `DEGRADED`。门禁以 JUnit 测试形式运行，不改变生产启动流程。

## API 契约

### Prometheus

`GET /internal/metrics`

- `200 text/plain; version=0.0.4`：有效 Token。
- `401 application/json`：配置了 Token 但请求缺失或不匹配。
- `404 application/json`：未配置 Token，接口关闭。

### 告警

`GET /api/v1/admin/operations/alerts?window=5m|15m|60m`

复用现有 `ApiResponse` 包装和 `requestId`，仅 admin 可访问；非法窗口返回 `400 INVALID_REQUEST`。

## 前端

运行监控页在现有指标卡片下增加告警摘要：告警数量、规则名称、当前值/阈值、active/resolved 状态和最近评估时间；继续使用当前窗口选择器和 admin-only 权限，不展示 Token 或内部文件路径。

## 验收标准

1. API 重启后，最近 60 分钟内的聚合桶可从 JSON 文件恢复；损坏文件不阻断启动并显示 `DEGRADED`。
2. Prometheus 接口只接受有效 Token，输出可被 Prometheus 文本解析器读取的固定指标，且不含敏感字段。
3. P95、5xx 比例和安全事件三类规则能产生 active/resolved 状态，管理员接口和前端可见。
4. 非管理员不能读取告警接口；非法窗口和错误 Token 均返回稳定契约。
5. RC 门禁测试覆盖持久化恢复、脱敏、告警状态转换和健康状态，前后端全量测试与构建通过。

## 后续替换点

Redis 适配器只需实现 `OperationsMetricsStore`；外部告警只需实现 `OperationsAlertSink`。两者均不属于本阶段交付。
