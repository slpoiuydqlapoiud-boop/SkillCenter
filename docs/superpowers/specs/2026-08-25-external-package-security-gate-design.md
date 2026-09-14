# External Package Security Gate

## Goal

为 Skill 包上传链路建立可替换的外部安全扫描 contract、生产 readiness 和 fail-closed gate，使目标环境可以接入批准的恶意文件/依赖/许可证扫描服务，而本地开发不会伪造生产安全能力。

## Scope

现有本地确定性扫描始终保留并先执行。新增 `disabled`/`required` 外部扫描模式、外部 scanner 端口、contract-only 默认适配器、合并安全结果、管理员只读 readiness API，以及配置和测试边界。默认模式不改变当前本地行为；`required` 模式没有真实外部适配器时必须阻断上传。

本次不实现任何具体厂商的恶意文件引擎、依赖漏洞数据库、许可证数据库或生产凭据管理；不把 contract-only、连接可达或本地扫描结果解释为外部生产验收。

## Contract

`ExternalPackageSecurityScanner` 提供 scanner id/version、只读 `health()`、覆盖能力声明和按 ZIP 路径执行的 `scan()`。生产 required gate 的覆盖能力固定为 `MALWARE`、`SENSITIVE_INFORMATION`、`DEPENDENCY_VULNERABILITY`、`LICENSE` 四类；缺少任一能力时即使健康为 `READY` 也必须 fail-closed。结果只允许 `PASSED`、`BLOCKED`、`NOT_SCANNED`，finding 只保存稳定 code、脱敏 path、severity；原始响应、reason 正文、包内容和 credential-ref 不进入结果。

`PackageSecurityScanCoordinator` 先运行本地扫描：本地阻断立即阻断且不调用外部服务；本地通过且模式为 `disabled` 时保持 local scanner provenance；模式为 `required` 时必须得到外部 `PASSED` 才能进入审核。外部阻断合并 safe findings，外部未配置、contract-only、超时、异常或非法结果统一为 `NOT_SCANNED` + 稳定 unavailable finding，验证结果无效。

## Configuration and readiness

- `skill-center.package-security.external.mode=disabled|required`，默认 `disabled`。
- `required` 模式只接受通过 Spring 注入的真实 scanner；默认 contract-only adapter 明确返回 `CONTRACT_ONLY`，不发起网络请求。
- `GET /api/v1/admin/package-security/readiness` 只返回 mode、status、scanner id/version、稳定 reason code 和检查时间；不返回 endpoint、credential-ref、原始异常或响应正文。
- readiness 同时返回已声明与缺失的四类安全覆盖能力；健康 `READY` 但覆盖不完整时状态为 `DEGRADED`、原因固定为 `EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE`。
- readiness `READY` 只代表实际 scanner adapter 已提供生产实现并报告健康，不代表扫描结果本身通过；`CONTRACT_ONLY`、`NOT_CONFIGURED`、`DEGRADED` 均不能放行 required 上传。

## Evidence and compatibility

`PackageValidationResult` 增加 scanner provenance，旧构造器继续使用 local/legacy 默认值。现有 `SecurityScanEvidence`、ReviewTask、SkillVersion 和 lifecycle projection 复用该 provenance；审核和投影链路不再丢失外部 scanner 身份。默认 disabled 结果与现有本地上传 API 兼容。

## Verification

- 单元测试覆盖 disabled、required 未配置/contract-only、external passed/blocked/timeout、local blocked short-circuit、safe result redaction。
- Controller 测试覆盖管理员权限、readiness 状态和敏感字段不泄露。
- PackageValidationService 和 ReviewService 回归验证外部 scanner provenance 可持久化。
- 统一 verifier 必须保持 0 failures/errors；外部真实服务和生产凭据仍记录为外部交付开放项。
