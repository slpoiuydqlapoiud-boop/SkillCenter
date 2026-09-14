# CCE CSMS Kubernetes Deployment Design

## Goal

为 SkillCenter 增加可审计、可复用的 Kubernetes 生产部署层，使用华为云 CCE 原生 CSMS 密钥管理插件和 Workload Identity 管理运行时凭据。

## Decision

采用 Helm Chart + CCE `SecretProviderClass`。不伪造 ESO 的华为云 Provider，也不把真实凭据、AK/SK、生产域名或云资源 ID 写入仓库。

## Scope

- API 和 Web 的可重复镜像构建入口。
- Helm 模板：ServiceAccount、SecretProviderClass、ConfigMap、Deployment、Service、Ingress、HPA、PDB、NetworkPolicy、迁移 Job、ServiceMonitor。
- 生产示例 values，所有外部地址和 CSMS 对象名使用显式替换值。
- Helm/YAML/安全边界校验脚本和部署运行手册。

## Secret flow

1. CCE ServiceAccount 由平台侧绑定最小权限 IAM 委托。
2. CCE DEW 插件通过 `useWorkloadCred: "true"` 读取 CSMS 的 `latest` 版本。
3. `SecretProviderClass.secretObjects` 将必要字段同步为命名空间内的 Kubernetes Secret。
4. API Deployment 通过 `envFrom.secretRef` 注入敏感环境变量；非敏感配置来自 ConfigMap。
5. CSMS 轮转后由运维控制器或发布流程执行受控滚动重启，使环境变量重新加载。

## Deployment boundaries

- Chart 不创建 CCE 集群、IAM 委托、CSMS Secret、PostgreSQL、Redis、OpenSearch、OBS 或第三方 Provider。
- Chart 不允许 Secret 明文进入 values、ConfigMap、镜像层或 Git 历史。
- 应用使用 `prod` profile，默认拒绝本地/Mock Provider；平台 readiness 和生产 handoff 继续 fail-closed。
- Ingress 终止 TLS，API 只在集群内通过 Service 暴露；NetworkPolicy 限制入口和外部出口。

## Verification

- PowerShell 资产测试检查必需模板、镜像指令、敏感字段隔离和生产占位符。
- Helm `lint`/`template`（若本机安装 Helm）和 Kubernetes schema 校验（若工具可用）。
- API Maven tests、Web tests/build、`verify-production-config` 和 `verify-production-handoff` 继续作为上线前门禁。
- 真实 CCE/CSMS/IAM/外部 Provider/UAT 证据必须在目标环境完成，不能用本地结果替代。

