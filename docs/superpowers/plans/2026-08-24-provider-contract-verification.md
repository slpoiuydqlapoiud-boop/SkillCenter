# Provider 契约一致性校验实施计划

## 任务

为真实 OpenClaw、DeepEval、Langfuse 等 Provider 联调增加契约漂移检测，并在质量中心展示诊断结果。

## 实施步骤

1. 新增 `ProviderContractVerification` 和 `ProviderContractVerificationService`，比较契约目录与 `ProviderRegistry` 的版本/能力。
2. 增加管理员只读 API `/api/v1/admin/quality/provider-contract-verification`。
3. 在 Web API 客户端和 Quality Center 增加契约一致性面板，明确匹配不等于可执行。
4. 增加服务、Controller、客户端和页面回归测试，验证稳定排序、权限和脱敏。
5. 运行 API/Web 全量测试、生产构建和差异检查。
