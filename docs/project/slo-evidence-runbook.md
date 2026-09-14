# SLO 测量证据运行手册

日期：2026-09-08

使用 `node scripts/slo-smoke.mjs` 对一个只读 HTTP(S) 入口做有界并发测量。工具使用 Node 内置 `fetch`，不发送请求正文、不携带认证头、不读取或记录响应正文；用户名和密码也不能出现在 URL 中。

示例：

```text
node scripts/slo-smoke.mjs \
  --url=https://skillcenter.example.internal/api/v1/skills \
  --duration-seconds=60 \
  --concurrency=8 \
  --request-timeout-ms=2000 \
  --p95-ms=500 \
  --min-success-rate=99.9 \
  --output=artifacts/slo-skill-list.json
```

输出包含请求总数、成功/失败数、HTTP 状态分布、P50/P95/P99、最大延迟和阈值结论。退出码为 `0` 表示通过阈值，`2` 表示完成测量但未达到阈值，`1` 表示参数或运行错误。工具限制测量时长最多 1 小时、并发最多 100、单请求超时最多 120 秒，防止误配置产生无界压力。

该结果只是目标环境的测量原始证据，不会自动更新平台生产证据台账或将平台 readiness 置为 `READY`。正式验收仍需由责任人确认测试窗口、负载模型、数据脱敏、SLO 阈值、告警、容量余量和回滚影响，并将脱敏报告通过 `DATABASE_CAPACITY_SLO` 或 `SLO_UAT` 台账流程登记。

完成外部证据登记后，可用只读验收脚本检查整体交付状态：

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-production-handoff.ps1 -Json -FailOnNotReady
```

脚本只读取 `/api/v1/admin/platform/readiness` 和 `/api/v1/admin/platform/evidence`，不会自动提交证据；退出码 `2` 表示仍有证据或 readiness 阻断，不能作为通过信号使用。

若要把该检查接入完整生命周期回归，可显式执行：

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-lifecycle.ps1 `
  -CheckProductionHandoff -ProductionHandoffBaseUrl https://skillcenter.example.internal
```

默认不启用该 gate，因此开发机不需要伪造生产证据即可运行常规 Web/API 回归。
