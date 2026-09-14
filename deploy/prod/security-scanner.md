# SkillCenter 外部安全扫描网关 生产接入指南

> **证据 ID**：`PROVIDER_SECURITY`
> **适配层**：`apps/api/src/main/java/com/huawei/skillcenter/packageupload`
> **设计文档**：[`docs/superpowers/specs/` 系列 — 见 §1.2](#12-相关设计文档)
> **健康探针**：`GET /api/v1/admin/package-security/readiness`（admin only）
> **自检清单**：见文末 [§9 PROVIDER_SECURITY 证据 ID 自检清单](#9-provider_security-证据-id-自检清单)

本指南面向**生产环境把外部安全扫描网关真实接通**的运维与安全工程师。平台侧的 HTTP 适配器、扫描协调器、本地/外部扫描协作均已 100% 实现并通过单元/集成测试，本指南只覆盖**接入真实扫描服务时的配置、验证、应急流程**。

---

## 1. 总览与适用边界

### 1.1 适配层能力

| 组件 | 路径 | 能力 |
|---|---|---|
| `PackageSecurityScanCapability` | `packageupload/PackageSecurityScanCapability.java` | 枚举 4 类能力：`MALWARE / SENSITIVE_INFORMATION / DEPENDENCY_VULNERABILITY / LICENSE` |
| `ExternalPackageSecurityScanner` | `packageupload/ExternalPackageSecurityScanner.java` | 接口：`scan(Path) / health() / capabilities() / scannerId()` |
| `HttpExternalPackageSecurityScanner` | `packageupload/HttpExternalPackageSecurityScanner.java` | HTTP 适配：POST zip + Bearer + SHA-256 header，解析 `package-security-scan.v1` 响应 |
| `ContractOnlyExternalPackageSecurityScanner` | `packageupload/ContractOnlyExternalPackageSecurityScanner.java` | 契约占位（`mode=disabled` 时返回 `EXTERNAL_SECURITY_SCANNER_CONTRACT_ONLY`） |
| `ExternalPackageSecurityScannerProperties` | `packageupload/ExternalPackageSecurityScannerProperties.java` | 配置绑定 + `@PostConstruct` 校验 |
| `PackageSecurityScanCoordinator` | `packageupload/PackageSecurityScanCoordinator.java` | 本地扫描 + 外部扫描协作；`mode=required` 时外部失败 → 上传拒绝 |
| `PackageSecurityReadinessController` | `packageupload/PackageSecurityReadinessController.java` | `GET /api/v1/admin/package-security/readiness` |
| `EnvironmentProviderCredentialResolver` | `quality/EnvironmentProviderCredentialResolver.java` | 凭据解析（**默认 Bean**，仅识别 `secret://env/<NAME>`） |
| `SecurityScanEvidence` | `governance/SecurityScanEvidence.java` | 证据持久化（JSON / PostgreSQL） |

### 1.2 相关设计文档

- `docs/superpowers/specs/` 下与外部扫描契约相关的设计文档（在仓库 docs 树内；本指南不直接引用以避免路径漂移）
- 接入前请先阅读 `PackageSecurityScanCoordinator.java` §5 中的"fail-closed 行为"

### 1.3 不在本指南范围

- ❌ 平台内部本地扫描器（`PackageSecurityScanService`）的策略细节（与生产外部扫描无关）
- ❌ 真实扫描服务的选型（不同厂商有独立 API；本指南规定**契约层**而不是厂商实现）
- ❌ 制品上传后端（`SKILL_CENTER_PACKAGE_UPLOAD_BACKEND=distributed`）的传输链路
- ❌ 制品签名 / SBOM 生成（独立模块）

---

## 2. HTTP 契约：`package-security-scan.v1`

### 2.1 请求

| 项 | 要求 |
|---|---|
| Method | `POST` |
| URL | `https://<endpoint>/<path>`（端点由 `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_ENDPOINT` 配置） |
| `Authorization` | `Bearer <token>`（token 通过 `secret://` 引用解析） |
| `Content-Type` | `application/zip` |
| `X-Skill-Package-Sha256` | 包的 SHA-256 哈希（十六进制小写） |
| Body | 整个 zip 包原始字节 |
| Max Request | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MAX_REQUEST_BYTES`（默认 20MB，合法 4KB-50MB） |
| Connect Timeout | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CONNECT_TIMEOUT_MS`（100-10000ms） |
| Request Timeout | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_REQUEST_TIMEOUT_MS`（100-30000ms） |

### 2.2 响应（`package-security-scan.v1`）

**顶层字段 allowlist**（任何未知字段 → `EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE`）：

```json
{
  "schemaVersion": "package-security-scan.v1",
  "status": "PASSED",
  "scannerId": "vendor-x-scanner",
  "scannerVersion": "2026.09.08",
  "capabilities": ["MALWARE", "SENSITIVE_INFORMATION", "DEPENDENCY_VULNERABILITY", "LICENSE"],
  "findings": [
    {
      "code": "MALWARE.SUSPICIOUS_BINARY",
      "path": "bin/agent.dll",
      "severity": "CRITICAL"
    }
  ]
}
```

| 字段 | 必填 | 约束 |
|---|---|---|
| `schemaVersion` | ✅ | 字符串，必须等于 `"package-security-scan.v1"` |
| `status` | ✅ | 枚举：`PASSED / BLOCKED / NOT_SCANNED` |
| `scannerId` | ✅ | 字符串，1-128 字符，无控制字符 |
| `scannerVersion` | ✅ | 字符串，1-64 字符 |
| `capabilities[]` | ✅ | 数组，每个元素枚举：`MALWARE / SENSITIVE_INFORMATION / DEPENDENCY_VULNERABILITY / LICENSE`；**必须包含平台配置的 4 类全集** |
| `findings[]` | ✅ | 数组，最多 1000 条 |
| `findings[].code` | ✅ | 字符串，1-96 字符，无控制字符 |
| `findings[].path` | ✅ | 字符串，1-256 字符，无控制字符 |
| `findings[].severity` | ✅ | 枚举：`LOW / MEDIUM / HIGH / CRITICAL` |

### 2.3 状态语义（platform → scanner → upload decision）

| `status` | 含义 | 平台处置 |
|---|---|---|
| `PASSED` | 扫描通过，无阻断发现 | 上传放行 |
| `BLOCKED` | 扫描阻断（即便 findings 为空） | 上传拒绝 |
| `NOT_SCANNED` | 扫描未完成（如服务降级或配置错误） | **上传拒绝**（fail-closed） |

> ⚠️ 即使 `findings[]` 为空但 `status=BLOCKED`，平台会补一条 `EXTERNAL_SECURITY_SCAN_BLOCKED (HIGH)` finding 以保审计可读。

---

## 3. 四类扫描能力契约

### 3.1 能力清单与典型检测项

| 能力 | 含义 | 典型发现 `code` 示例 |
|---|---|---|
| `MALWARE` | 恶意文件检测 | `MALWARE.SUSPICIOUS_BINARY`, `MALWARE.TROJAN_HEURISTIC`, `MALWARE.PACKED_BINARY` |
| `SENSITIVE_INFORMATION` | 敏感信息泄漏 | `SENSITIVE.AWS_ACCESS_KEY`, `SENSITIVE.PRIVATE_KEY`, `SENSITIVE.JWT_TOKEN`, `SENSITIVE.PASSWORD` |
| `DEPENDENCY_VULNERABILITY` | 依赖漏洞 | `CVE-2024-12345.HIGH`, `NVD.CRITICAL_OPENSSL`, `GHSA-xxxx-yyyy-zzzz` |
| `LICENSE` | 许可证合规 | `LICENSE.GPL_INCOMPATIBLE`, `LICENSE.UNKNOWN`, `LICENSE.COMMERCIAL_RESTRICTION` |

### 3.2 能力全集校验

```java
Set<String> required = Set.of(
    "MALWARE",
    "SENSITIVE_INFORMATION",
    "DEPENDENCY_VULNERABILITY",
    "LICENSE"
);
if (!declared.containsAll(required)) {
    throw new IllegalArgumentException("external scanner capabilities are incomplete");
}
```

**生产部署必须四项齐全**，缺一会：
- `verify-production-config.ps1` → `NOT_READY`（`package-security.capabilities INVALID`）
- `/api/v1/admin/package-security/readiness` → `status=DEGRADED, reasonCode=EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE`
- 任何上传请求 → 拒绝

### 3.3 扫描范围约定

- **包格式**：zip（与其他上传组件一致）；`mode=distributed` 时通过对象存储中转
- **大小上限**：默认 20MB；与 `SKILL_CENTER_PACKAGE_MAX_BYTES=20971520` 对齐
- **解压上限**：默认 100MB（`SKILL_CENTER_PACKAGE_MAX_UNCOMPRESSED_BYTES`）——防 zip bomb
- **分块上传**：扫描网关读取整个包，不感知分块（chunk 已在上传服务层聚合）

---

## 4. 配置矩阵（变量 → 文件 → 默认值）

所有变量已在 `deploy/prod/.env.prod.template` 第 5 节标注，本节说明**取值依据**。

### 4.1 第 5 节：制品上传与外部安全扫描

| 变量 | 必填 | 合法范围 | 推荐生产取值 | 取值依据 |
|---|---|---|---|---|
| `SKILL_CENTER_PACKAGE_UPLOAD_BACKEND` | ✅ | `local / distributed` | `distributed` | ProductionConfigCheck 强制 `distributed` |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MODE` | ✅ | `disabled / required` | `required` | ProductionConfigCheck 强制 `required` |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_ENDPOINT` | ✅* | HTTPS URL | 企业扫描服务 URL | ProductionConfigCheck 强制 HTTPS |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF` | ✅* | `secret://` 引用 | 见 §5 | ProductionConfigCheck 强制 secret 格式 |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CAPABILITIES` | ✅ | 4 类逗号分隔 | `MALWARE,SENSITIVE_INFORMATION,DEPENDENCY_VULNERABILITY,LICENSE` | ProductionConfigCheck 强制全集 |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CONNECT_TIMEOUT_MS` | 可选 | 100-10000 | `2000` | TLS 握手 + 路由 |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_REQUEST_TIMEOUT_MS` | 可选 | 100-30000 | `30000` | 50MB 包 + 多能力扫描 |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MAX_RESPONSE_BYTES` | 可选 | 4096-4194304 | `1048576` | findings ≤ 1000 × ~1KB |
| `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MAX_REQUEST_BYTES` | 可选 | 4096-52428800 | `20971520` | 与 `PACKAGE_MAX_BYTES` 对齐 |

`*` 当 `mode=required` 时强制

### 4.2 凭据注入（Secret Manager 路径）

**两条路径**（取决于部署期 Bean 是否替换）：

| 路径 | 适用场景 | 模板示例 |
|---|---|---|
| `secret://env/<ENV_VAR_NAME>`（**默认 Bean**） | 容器环境直接注入 token | `secret://env/SKILL_CENTER_SCANNER_TOKEN` |
| `secret://<vault-path>`（**自定义 Bean**） | 替换 `EnvironmentProviderCredentialResolver` 后 | `secret://skillcenter/prod/security-scanner/token` |

详见 [`deploy/prod/secret-references.md`](./secret-references.md) 第 3 节。

---

## 5. 凭据注入（两种生产部署模式）

### 5.1 默认 Bean（开发/测试/应急）

`EnvironmentProviderCredentialResolver` 接受 `secret://env/<NAME>` 格式，从 `System.getenv()` 解析。

**生产示例**（K8s Deployment + Secret）：

```yaml
env:
  - name: SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF
    value: "secret://env/SKILL_CENTER_SCANNER_TOKEN"
  - name: SKILL_CENTER_SCANNER_TOKEN
    valueFrom:
      secretKeyRef:
        name: skillcenter-scanner
        key: api-token
```

**应急场景**：扫描网关临时不可用，可降级为本地扫描（`mode=disabled`）—— 但这意味着外部 4 类能力失效，**禁止在生产默认使用**。

### 5.2 自定义 Bean（生产推荐）

替换默认 Bean 为真正的 Secret Manager 实现（HashiCorp Vault / AWS Secrets Manager / Azure Key Vault / 阿里云 KMS 等）：

```java
@Bean
@Primary
ProviderCredentialResolver secretManagerScannerCredentialResolver(SecretManagerClient client) {
    return reference -> {
        // 路径如 secret://skillcenter/prod/security-scanner/token
        return client.resolveSecret(reference.substring("secret://".length()));
    };
}
```

替换后即可使用：
```bash
SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF=secret://skillcenter/prod/security-scanner/token
```

> 路径约束 `^secret://[A-Za-z0-9._/-]+$` 在两种模式下都满足（`ProductionConfigCheck` 校验）。

### 5.3 凭据轮转

- **短期 Bearer token**（如 1h JWT）：随 token 自然过期；平台无需重启
- **长期 API Key**：建议季度轮转；通过 Secret Manager 同步，平台下次调用即生效（无需重启）
- **撤销**：在 Secret Manager 侧吊销 → 平台下一次请求 → `EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE` → 上传拒绝

---

## 6. 故障与降级

### 6.1 错误码映射（HTTP 适配器抛出 → 协调器处置）

| 错误码 | 含义 | 协调器处置 | 上传结果 |
|---|---|---|---|
| `EXTERNAL_SECURITY_SCANNER_NOT_CONFIGURED` | `mode != required` | 不调用外部 | 走本地扫描 |
| `EXTERNAL_SECURITY_SCANNER_CONFIGURED` | 健康检查正常 | 正常扫描 | 由 scanner 决定 |
| `EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE` | Secret 解析失败 | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_PACKAGE_INVALID` | 包路径不是 regular file | failure() | **拒绝** |
| `EXTERNAL_SECURITY_PACKAGE_TOO_LARGE` | 包超过 MAX_REQUEST_BYTES | failure() | **拒绝**（客户端先压缩） |
| `EXTERNAL_SECURITY_SCANNER_HTTP_STATUS` | 扫描服务返回非 200 | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_TIMEOUT` | 拉取超时 | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_INTERRUPTED` | 线程中断 | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_UNAVAILABLE` | 网络/DNS/TLS 异常 | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_RESPONSE_TOO_LARGE` | 响应超过 MAX_RESPONSE_BYTES | failure() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE` | schema/字段非法 | failure() | **拒绝** |
| `EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE` | 响应 capabilities 缺项 | unavailable(INCOMPLETE_CODE) | **拒绝** |
| `EXTERNAL_SECURITY_SCAN_UNAVAILABLE` | 协调器捕获 RuntimeException | unavailable() | **拒绝** |
| `EXTERNAL_SECURITY_SCAN_BLOCKED` | 扫描返回 BLOCKED（补 finding） | 见 §2.3 | **拒绝** |

> **核心原则**：外部扫描的任何失败都映射为上传拒绝（fail-closed）；无降级路径。

### 6.2 灾难场景处置

| 现象 | 自动行为 | 运维处置 |
|---|---|---|
| 扫描服务完全不可用 | 所有上传被拒（`NOT_SCANNED`） | 暂停上传入口（运维）或降级 `mode=disabled`（**仅紧急**，需安全团队批准并写入审计） |
| 扫描服务响应慢 | 请求超时 → 拒绝 | 调高 `REQUEST_TIMEOUT_MS` 或扩容扫描服务 |
| 凭据泄露 | 立即在 Secret Manager 撤销 | 平台下次请求 → `CREDENTIAL_UNAVAILABLE` → 拒绝；新凭据注入后自动恢复 |
| 误报（合规但被 BLOCKED） | 上传被拒 | 业务方修复扫描规则或调整 findings；禁止修改平台 fail-closed 行为 |
| 漏报（应被 BLOCKED 但 PASSED） | 上传放行 | 立即下线该 scanner；触发厂商安全事件响应流程 |
| Scanner 返回未知 `status` | 拒绝（`NOT_SCANNED`） | 升级厂商，要求严格按 §2.2 实现 |

### 6.3 应急降级开关（**慎用**）

```bash
# 1. 暂停所有上传（推荐）
#    在网关层（K8s Ingress / API 网关）临时拒绝 /api/v1/packages POST
# 2. 紧急降级为本地扫描（需安全团队批准 + 写入变更记录）
SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MODE=disabled
# 注意：此时 4 类外部能力完全失效，仅本地扫描生效
```

---

## 7. 网络与白名单

| 出口 | 协议 | 端口 | 凭据 | 备注 |
|---|---|---|---|---|
| `ENDPOINT` | HTTPS | 443 | Bearer | 不跟随 redirect（HTTP 适配器实现） |

**网络白名单**：
- 出向：API 节点 → 企业安全扫描服务（建议 VPC 内网或专线）
- 证书：由平台 cert-manager 管理；企业内部 CA 需提前注入 truststore
- 速率：扫描服务通常按厂商配额限流；高 QPS 场景需企业版授权

---

## 8. 上线前 8 步 Checklist

| # | 步骤 | 责任方 | 证据 |
|---|---|---|---|
| 1 | 扫描服务满足 §2/§3 契约（schemaVersion / capabilities 全集 / status 枚举） | 安全 + 平台 | 厂商契约文档 + 平台启动日志 |
| 2 | 扫描服务 HTTPS 端点可达 + TLS 证书有效 | 运维 | `curl -I <endpoint>` 输出 |
| 3 | `deploy/prod/.env.prod.cluster-a` 第 5 节按 §4 替换占位 | 运维 | git diff（仅模板）+ 红线检查 |
| 4 | Secret Manager 注入 `skillcenter/prod/security-scanner/token`（或临时用 §5.1 环境变量路径） | 安全 + 运维 | ESO 同步状态 |
| 5 | K8s NetworkPolicy / 防火墙放行 §7 出口 | 网络 | 策略 diff |
| 6 | `pwsh scripts/verify-production-config.ps1 -Json` 4 项全部 READY | 运维 | JSON 输出 |
| 7 | `GET /api/v1/admin/package-security/readiness` 返回 `READY` + `missingCapabilities=[]` | 运维 + 平台 | readiness JSON |
| 8 | UAT：上传一个真实包（PASSED + BLOCKED 两种 fixture） | QA | UAT 报告 + 证据归档 |

---

## 9. PROVIDER_SECURITY 证据 ID 自检清单

> 每条对应 ProductionConfigCheck 或运行时验证点；上线 UAT 时逐项打勾。

| # | 验证项 | 验证方式 | 通过判据 |
|---|---|---|---|
| 1 | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MODE == required` | `verify-production-config.ps1` | `package-security.mode` READY |
| 2 | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_ENDPOINT` 为 HTTPS | 同上 | `package-security.endpoint` READY |
| 3 | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF` 为 `secret://` 引用 | 同上 | `package-security.credential-ref` READY |
| 4 | `SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CAPABILITIES` 含 4 类全集 | 同上 | `package-security.capabilities` READY |
| 5 | `SKILL_CENTER_PACKAGE_UPLOAD_BACKEND == distributed` | 同上 | `package-upload.backend` READY |
| 6 | 扫描服务 HTTPS 端点可达 | `curl -I -k <endpoint>` | 200 + `Content-Type: application/json` |
| 7 | 扫描服务返回 `schemaVersion=package-security-scan.v1` | `curl -X POST --data-binary @pkg.zip` | 响应符合 §2.2 |
| 8 | 扫描服务响应 capabilities 含 4 类全集 | 同上 | 响应 `capabilities[]` 全集 |
| 9 | `GET /api/v1/admin/package-security/readiness` 返回 `status=READY` | admin Token + curl | `missingCapabilities=[]` |
| 10 | 扫描通过包 → 上传成功（201） | UIAT fixture: clean.zip | 返回 `packageId` |
| 11 | 扫描阻断包 → 上传被拒（4xx + BLOCKED finding） | UAT fixture: malware.zip | 响应含 `EXTERNAL_SECURITY_SCAN_BLOCKED` |
| 12 | 扫描不可达 → 上传被拒（`EXTERNAL_SECURITY_SCANNER_UNAVAILABLE`） | 断开扫描服务网络后上传 | 4xx + finding code |
| 13 | 凭据吊销 → 上传被拒（`EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE`） | 撤销后上传 | 4xx + finding code |
| 14 | 响应 capabilities 缺类 → 上传被拒（`CAPABILITIES_INCOMPLETE`） | Mock 响应 | 4xx + finding code |
| 15 | 响应 schema 非法 → 上传被拒（`INVALID_RESPONSE`） | Mock 响应（多字段） | 4xx + finding code |
| 16 | 响应超过 `MAX_RESPONSE_BYTES` → 拒绝 | Mock 大响应 | 4xx |
| 17 | 请求超过 `MAX_REQUEST_BYTES` → 客户端拒绝（413） | 上传超大包 | 客户端提前拒绝 |
| 18 | 4 类能力差异发现码（CRITICAL/HIGH/MEDIUM/LOW）均能透传到证据 | UAT fixture × 4 | `SecurityScanEvidence` 含 severity |
| 19 | 扫描响应 SHA-256 校验：客户端 header 与扫描服务接收到的一致 | 抓包 / 日志 | `X-Skill-Package-Sha256` 匹配 |
| 20 | 扫描审计日志（每次扫描 success/failure 带 actor、packageId、scannerId、status） | 日志平台检索 | 含全字段 |

> **证据归档**：上线后把上述每条验证项的截图/JSON 输出归档到 `docs/operations/security-scanner-uat-evidence-YYYYMMDD/PROVIDER_SECURITY-{01..20}.md`。

---

## 10. 故障排查速查

| 现象 | 根因 | 排查路径 |
|---|---|---|
| 所有上传返回 4xx `EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE` | Secret 路径错或 ESO 同步失败 | `kubectl get externalsecret` / 检查环境变量 |
| 所有上传返回 `EXTERNAL_SECURITY_SCANNER_HTTP_STATUS` | 扫描服务返回非 200 | 看 readiness `status` 字段 + 厂商日志 |
| 所有上传返回 `EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE` | 厂商 schema 漂移 | 对比 §2.2 + 抓包 |
| 上传返回 `EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE` | 厂商关闭了某个能力 | readiness `missingCapabilities` → 联系厂商 |
| 上传返回 `EXTERNAL_SECURITY_SCANNER_RESPONSE_TOO_LARGE` | findings 超过 1000 或单条过长 | 调大 `MAX_RESPONSE_BYTES` 或拆分扫描 |
| 上传返回 `EXTERNAL_SECURITY_PACKAGE_TOO_LARGE` | 客户端未压缩 | 客户端预压缩（zip / tar.gz） |
| `/readiness` 返回 `DEGRADED` | 启动期健康检查未完成或 schema 解析失败 | 等 30s 后重试；仍失败则查应用日志 |
| 启动失败 "external scanner capabilities are incomplete" | `CAPABILITIES` 环境变量缺类 | 改为全集 `MALWARE,SENSITIVE_INFORMATION,DEPENDENCY_VULNERABILITY,LICENSE` |

---

## 11. 与其他适配器的关系

| 适配器 | 关系 | 说明 |
|---|---|---|
| `JwksKeySetProvider` | 独立 | 扫描网关无需 JWT 校验 |
| `HttpOrganizationDirectoryClient` | 独立 | 但都属"外部 HTTP 适配器"模式，可复用错误处理风格 |
| `OpenClaw / DeepEval / Langfuse Provider` | 同模式 | `ProviderCredentialResolver` 是共同入口；凭据注入策略一致 |
| `ObjectStorage` | 间接 | `mode=distributed` 时扫描包先传对象存储再喂扫描器 |

---

**版本**：v1.0（2026-09-08）  
**责任人**：平台架构组 + 安全团队  
**变更历史**：首版（外部安全扫描网关生产接入指南）