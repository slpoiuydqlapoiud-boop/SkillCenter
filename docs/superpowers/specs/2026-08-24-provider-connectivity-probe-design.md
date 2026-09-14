# Provider 连接探测设计

日期：2026-08-24

## 目标

为 OpenClaw Runner、DeepEval EvaluationProvider 和 Langfuse ObservabilityProvider 增加一个可审计的控制面连接性信号，帮助管理员定位 endpoint、网络和配置缺口，同时保持真实适配器尚未完成时的 fail-closed 边界。

## 非目标

- 不执行 Skill、评测用例或观测写入。
- 不读取或发送业务正文、Prompt、工具参数或 `credential-ref` 原文。
- 不因 HTTP 探测成功而改变 `ProviderReadinessSummary` 的 `PARTIAL`/`CONTRACT_ONLY` 语义。

## 契约

`POST /api/v1/admin/quality/provider-readiness/probe` 仅管理员可调用，可通过 `providerId` 选择单个 Provider；未提供时按 Provider ID 排序返回全部目标。结果只包含 `providerId`、`kind`、`status`、`reason`、`httpStatus`、`latencyMs` 和 `checkedAt`。

服务端使用固定 2 秒超时的可替换传输端口发送无正文 GET。状态分为 `SKIPPED`、`NOT_CONFIGURED`、`REACHABLE`、`HTTP_ERROR`、`TIMEOUT`、`UNREACHABLE` 和 `FAILED`。每次探测写入不含 endpoint/凭据的 `PROVIDER_CONNECTIVITY_PROBED` 审计事件。

## 后续门禁

连接探测通过后，仍需按 Provider 完成认证协议、请求/响应映射、取消/重试、脱敏、容量和回滚演练，才能将契约适配器推进为真实执行 Provider。
