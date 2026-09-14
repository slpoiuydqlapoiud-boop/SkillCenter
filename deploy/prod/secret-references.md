# SkillCenter 生产 Secret Manager 引用约定

> 本文件定义 `deploy/prod/.env.prod.template` 中所有 `secret://...` 引用
> 的命名规范、轮转策略、注入路径。**所有凭据一律走 Secret Manager，禁止
> 明文出现在仓库、镜像、配置中心、CI 日志或聊天工具中。**

## 1. 命名规范

### 1.1 路径语法

```
secret://<tenant>/<env>/<service>/<key>
```

- `<tenant>`     固定 `skillcenter`
- `<env>`        `prod` / `staging` / `dr`（dr 用于灾备 Region）
- `<service>`    服务名（见下表）
- `<key>`        凭据内的具体字段

> `ProductionConfigCheck.psm1::Test-SecretReference` 校验正则
> `^secret://[A-Za-z0-9._/-]+$`，所以路径只能使用字母、数字、`.`、`_`、`/`、`-`。

### 1.2 服务目录（与 .env.prod.template 对齐）

| 服务 | `service` 段 | 典型 `<key>` |
|---|---|---|
| PostgreSQL | `postgres` | `username` / `password` / `read-only-password` |
| Redis HA | `redis` | `auth` / `tls-client-cert` |
| 对象存储 | `object-storage` | `access-key` / `secret-key` / `region` |
| 安全扫描 | `security-scanner` | `token` |
| OpenClaw | `openclaw` | `token` |
| DeepEval | `deepeval` | `token` |
| Langfuse | `langfuse` | `token` / `public-key` |
| 组织目录 | `org-directory` | `token` |
| 发布目标 | `release-target` | `token` |
| 告警通知 | `alerts` | `hmac-secret`（如需签名） |
| TLS | `tls` | 各服务证书 |

## 2. 注入路径

### 2.1 推荐：K8s + External Secrets Operator（ESO）

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: skillcenter-postgres
  namespace: skillcenter
spec:
  secretStoreRef: { name: aws-sm, kind: ClusterSecretStore }
  target: { name: skillcenter-postgres-env }
  data:
    - secretKey: SKILL_CENTER_POSTGRES_USERNAME
      remoteRef: { key: skillcenter/prod/postgres/username }
    - secretKey: SKILL_CENTER_POSTGRES_PASSWORD
      remoteRef: { key: skillcenter/prod/postgres/password }
```

API 进程用 `valueFrom.secretKeyRef` 直接读取 `SKILL_CENTER_POSTGRES_PASSWORD`，
代码层无需感知 Secret Manager 的存在。

### 2.2 备选：VM 编排 + 文件注入

不允许！原因：
1. 注入文件易被 `kubectl exec` / SSH 拷贝泄露；
2. 备份快照会包含明文凭据；
3. 审计链不闭环。

只能 K8s/容器编排侧通过 tmpfs / in-memory 注入。

## 3. 轮转策略

| 类型 | 轮转周期 | 通知前置 | 失效窗口 |
|---|---|---|---|
| PostgreSQL 密码 | 90 天 | 14 天告警 | 旧值在轮转后保留 24h 灰度 |
| Redis AUTH | 180 天 | 30 天告警 | 强制一次性切换 |
| 对象存储 Key | 90 天 | 14 天告警 | 双 Key 灰度 7 天 |
| 第三方 Token | 按供应商 SLA | 30 天 | 仅通知，无自动轮转 |
| TLS 证书 | 60 天前自动续期 | 14 天 | cert-manager 自动处理 |

所有轮转动作写入审计（who / when / target / old-version-ref / new-version-ref）。

## 4. 访问控制（最小权限）

| 角色 | 允许读取的路径前缀 | 说明 |
|---|---|---|
| `skillcenter-api-runtime` | `secret://skillcenter/prod/{postgres,redis,object-storage,openclaw,deepeval,langfuse,org-directory}` | API 进程运行时 |
| `skillcenter-release-operator` | `secret://skillcenter/prod/release-target` + 审计写入权限 | 发布审批人 |
| `skillcenter-security-scanner` | `secret://skillcenter/prod/security-scanner` | 扫描 Worker |
| `skillcenter-dr-runbook` | `secret://skillcenter/dr/*`（只读） | 灾备恢复演练 |
| `skillcenter-auditor` | 仅审计读取 | SOC2 / 等保合规审计 |

> 任何角色**不允许**写 `secret://skillcenter/prod/*` 直接值，必须通过
> Secret Manager 控制台/CLI 的版本化接口提交，留完整审计链。

## 5. 应急撤销（疑似泄露）

1. 在 Secret Manager 控制台**立即冻结**该路径（阻断所有 reader）；
2. 触发新值生成并轮转（双 Key 灰度机制立刻生效，旧 Key 失效）；
3. 审计日志导出并提交安全事件；
4. 24h 内完成全平台 `RELEASE_ADMISSION_MODE=CONTROLLED` 阻断性回归；
5. 影响面评估后决定是否触发 `ROLLBACK_DRILL`。

## 6. 审计与对账

- Secret Manager 每日变更对账：与 CI/CD  发布记录交叉验证
- 季度合规审计：导出所有 secret 路径的访问日志，由 `skillcenter-auditor`
  角色独立审查
- 证据汇总：`PROVIDER_SECURITY` 证据 ID 包含 Secret Manager 审计导出

## 7. 与 .env.prod.template 的对应检查

```powershell
# 验证所有 *_REF 走的是 secret:// 格式
pwsh scripts/verify-production-config.ps1 -Json
# 输出 status=INVALID, reasonCode=PRODUCTION_CONFIG_SECRET_REF_REQUIRED
# 即视为明文残留，必须修正为 secret://... 引用
```

任何 `SECRET_REF` 未填或填了明文值 → `verify-production-config` 必然失败，
部署流水线 fail-closed，不进入 `verify-production-handoff` 阶段。