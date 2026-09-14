# Provider 契约一致性校验设计

日期：2026-08-24

## 目标

在 Agent Runtime、MCP Server、LLM Provider 和观测 Provider 真实联调前，自动比较外部契约目录与当前注册适配器的版本、能力声明和注册状态，提前发现契约漂移。

## 边界

- 校验是本地、只读、无业务正文和无网络调用的诊断能力。
- `MATCHED` 只表示契约元数据一致，不等价于 Provider readiness 或真实执行可用。
- `CONTRACT_ONLY`、`NOT_CONFIGURED` 和未注册状态继续由 readiness 负责表达，不被校验结果覆盖。
- 缺失/多余能力和版本不一致统一返回稳定原因码 `PROVIDER_CONTRACT_MISMATCH`。
- 响应不得返回 endpoint、credential-ref 原文、Token、Prompt、输入输出或工具参数。

## API 与界面

```text
GET /api/v1/admin/quality/provider-contract-verification
```

质量中心同时展示契约校验状态、当前适配器状态、版本差异和能力差异；管理员可据此进入真实 Provider 联调门禁。该接口不改变 Provider 选择、不触发执行、不修改 readiness。

## 验收

- 已注册且版本/能力一致的 Provider 返回 `MATCHED`。
- 已注册但版本或能力漂移返回 `MISMATCH`，并列出稳定排序的缺失/多余能力。
- 未注册的外部契约返回 `NOT_REGISTERED`。
- `CONTRACT_ONLY` 的一致适配器返回 `PROVIDER_CONTRACT_MATCHED_NOT_ENABLED`。
- API 仅管理员可访问，响应不含密钥或业务正文。
