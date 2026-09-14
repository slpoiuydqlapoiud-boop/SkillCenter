# SkillCenter 环境依赖与 Workbuddy 搭建清单

更新时间：2026-09-09

> 当前默认交付目标已经收敛为“部门级 Windows 单机 + MySQL + 本地文件 + 基础鉴权”，请直接查看文末“部门版本地环境（当前默认）”。本文前面的企业级容器/云联调清单仅作为历史参考，不是部门版启动前置。

以下第 1—7 节保留历史企业级联调信息。

## 1. 本地开发必需

| 依赖 | 建议版本 | 用途 | 当前状态 |
| --- | --- | --- | --- |
| JDK | 21 | Spring Boot API 编译与运行 | 已具备 |
| Maven | 3.9+ | API 构建、Surefire 测试 | 已具备 |
| Node.js | 20 LTS 或更高 | React/Vite 前端 | 已具备（当前 26） |
| npm | 随 Node.js | 前端依赖、测试、生产构建 | 已具备 |
| Git | 2.4+ | 版本管理 | 已具备 |
| PowerShell | 7+ | Windows 验证脚本、部署辅助 | 已具备 |
| Python | 3.11+ | `tests/contract` 合约测试 | 已具备（3.12.10） |
| Python `jsonschema` | 4.23.0 | JSON Schema 合约校验 | 已具备（4.23.0） |

安装 Python 合约测试依赖：

```powershell
py -3.11 -m pip install -r requirements-dev.txt
py -3.11 -m unittest discover -s tests -p "test_*.py"
```

## 2. Workbuddy 必须搭建的基础设施

| 组件 | 建议版本 | 默认端口 | 用途 |
| --- | --- | ---: | --- |
| Docker Desktop | 当前稳定版（已具备 4.90.0，CLI 29.7.2） | — | Testcontainers、基础设施编排 |
| Docker Compose v2 | 随 Docker Desktop（已具备 v5.5.1） | — | 一键启动本地依赖 |
| PostgreSQL | 16+（当前已启动 16.4） | 5432 | 治理、质量、Benchmark、发布、执行环境等共享持久化 |
| Redis | 7+（当前已启动 7.4） | 6379 | 运行摘要、告警状态、分布式上传会话 |
| MinIO 或 S3-compatible 存储 | 当前稳定版（当前已启动） | 9000/9001 | Skill 制品与分片对象存储联调 |
| OpenSearch | 2.17.1（当前已启动） | 9200 | 外部 Skill 元数据搜索适配器联调；仅在显式选择 `opensearch` 且 readiness 探测通过后使用 |

生产部署还需要：

| 组件 | 作用 | 当前仓库状态 |
| --- | --- | --- |
| Huawei CCE Kubernetes 集群 | 运行 API/Web 多副本工作负载 | 未提供目标集群连接 |
| CCE Secrets Store CSI + DEW 插件 | 从 CSMS 挂载并同步运行时凭据 | 已提供 Helm 模板，需在 CCE 启用 |
| CCE Workload Identity + IAM 委托 | 无 AK/SK 访问 CSMS | 已提供 ServiceAccount 接口，需云侧绑定 |
| 华为云 CSMS | PostgreSQL、Redis、Provider、SSO 等凭据 | 需创建真实 Secret 对象 |
| SWR 私有镜像仓库 | 存放 API/Web 镜像 | 需提供仓库地址和拉取凭据 |
| Ingress/ELB 与 TLS | 暴露 HTTPS 入口 | 需提供域名、证书和 IngressClass |

Kubernetes 部署资产：`deploy/k8s/skillcenter/README.md`。该目录只负责可审计的
部署模板，不会自动创建 CCE、IAM、CSMS、数据库或外部 Provider。

数据库和 Redis 只绑定宿主机 loopback；密码、Access Key 和连接串通过 Workbuddy 的 Secret/环境变量注入，不提交仓库。

## 3. 生产联调外部系统

- 企业 SSO/JWT 或 JWKS endpoint，以及组织目录同步 endpoint；需要 TLS、撤销策略、团队 claim 约定和 Secret Manager。
- OpenClaw Agent Runtime、外部 MCP Server、LLM Provider、DeepEval 和 Langfuse；需要 endpoint、认证、超时/取消、脱敏、保留策略和故障注入环境。
- 恶意文件、敏感信息、依赖漏洞、许可证四类外部安全扫描能力；需要规则库、样本集、SLA 和凭据托管。
- Prometheus、Grafana、Alertmanager 或企业等价监控告警平台；通知 webhook 必须由企业网络和审批配置提供。
- 备份/PITR、灾备恢复、容量/SLO、渗透测试、UAT、上线审批和回滚演练环境。

## 4. API 侧配置切换原则

默认配置保持 JSON、Mock、local/contract-only，不依赖上述基础设施即可启动本地开发。切换到 PostgreSQL、Redis、对象存储或真实 Provider 前，必须同时满足对应 selector、迁移版本、readiness 和安全凭据条件；任何连接失败都应 fail-closed，不自动回退到 JSON/Mock。

关键配置位置：

- API 默认配置：`apps/api/src/main/resources/application.yml`
- 搜索共享后端 selector：`SKILL_CENTER_SEARCH_INDEX_BACKEND=postgresql`（需同时开启全局 PostgreSQL，并通过 V16/readiness）
- OpenSearch 搜索联调：`SKILL_CENTER_SEARCH_INDEX_BACKEND=opensearch`，并配置 `SKILL_CENTER_SEARCH_INDEX_ENDPOINT`、`SKILL_CENTER_SEARCH_INDEX_NAME`、可选 `SKILL_CENTER_SEARCH_INDEX_CREDENTIAL_REF`；必须先执行管理员搜索索引探测并通过 readiness，不可用时不会回退到 JSON
- 跨实例搜索刷新：`SKILL_CENTER_SEARCH_INDEX_EVENTS_ENABLED=true`（需同时开启 PostgreSQL 搜索后端；治理聚合和 Skill 范围写入会将 V17 metadata-only refresh outbox 与各自主事务绑定，V18 持久化 consumer cursor + poller 提供 at-least-once 重放，V20 增加 ACTIVE/RETIRED 生命周期和 heartbeat；`SKILL_CENTER_SEARCH_INDEX_EVENTS_CONSUMER_ID` 应为每个实例稳定且唯一的部署标识）
- 搜索刷新日志清理：`SKILL_CENTER_SEARCH_INDEX_EVENTS_RETENTION_SCHEDULER_ENABLED=true`（显式开启，需 V20；仅按 retention cutoff 和仍活跃 consumer 的保护水位清理；stale consumer 只有在已追平 cutoff 内全部事件时才会被安全排除，否则继续阻塞清理；下线实例应通过管理员控制 API 显式 retire）
- 本地依赖编排：`deploy/local/compose.yaml`，变量模板：`deploy/local/.env.example`
- 外部 Provider/Redis 联调手册：`docs/project/M11-external-integration-runbook.md`
- Prometheus/Grafana/Alertmanager 模板：`deploy/observability/`
- 开发依赖：`requirements-dev.txt`、`apps/api/pom.xml`、`apps/web/package.json`

## 5. 验证顺序

1. Workbuddy 启动 PostgreSQL、Redis、MinIO，并通过健康检查确认服务可达。
2. 执行 `mvn.cmd -q -DforkCount=0 test`；Docker 不可用时只能记录命名 `CAPABILITY_SKIP: Docker is unavailable`，不能把其他失败转为跳过。
3. 执行 `npm.cmd test` 和 `npm.cmd run build`。
4. 注入目标环境 Secret，先执行平台 readiness，再逐项打开 PostgreSQL/Redis/对象存储/Provider selector。
5. 完成多实例、故障转移、备份恢复、容量/SLO、脱敏和 UAT 证据后，才允许生产发布门禁变为 READY。

最近一次本机验收（2026-09-08）中，JDK/Maven/Node/npm/Python/jsonschema、Docker/Compose、PostgreSQL、Redis、MinIO、OpenSearch 及观测组件均为 `READY`。这只证明本地联调环境已具备，不等价于生产 HA、容量/SLO、备份恢复或外部 Provider 验收完成。

## 6. 一键环境验收

Workbuddy 完成安装和启动后，在仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-environment.ps1 -CheckServices
```

脚本只读检查 JDK/Maven/Node/npm/Python、`jsonschema`、Docker/Compose，以及 PostgreSQL、Redis、MinIO、OpenSearch 的本机可达性；不会安装、启动、删除或修改服务。结果状态为 `READY`、`MISSING` 或 `UNAVAILABLE`。

需要同时确认应用进程是否已启动时追加 `-CheckRuntime`。它只检查 Web、默认 API、integration API 和 integration readiness 的 HTTP 响应，不输出接口响应体或敏感信息：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-environment.ps1 -CheckServices -CheckObservability -CheckRuntime -FailOnMissing -Json
```

`-CheckRuntime` 的 `READY` 表示本机 HTTP 入口可访问；integration readiness 仍可能因外部 Provider、安全扫描、发布目标或生产验收证据缺失而返回业务层 `NOT_READY`，这属于平台准入状态，不等于应用进程未启动。

供自动化门禁使用时追加 `-FailOnMissing`，任何未就绪项都会以退出码 1 结束；供 Workbuddy 解析时追加 `-Json` 输出机器可读报告：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-environment.ps1 -CheckServices -FailOnMissing -Json
```

## 7. 生产配置前置检查

环境和容器安装完成后，可在注入生产配置的同一进程环境中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-production-config.ps1 -Json
```

该脚本只读取 64 项生产选择器、端点格式和 Secret 引用配置；输出变量名、状态、稳定原因码和修复提示，不输出 Secret 值、Token、连接串凭据或上游正文。`-FailOnNotReady` 在配置缺失或非法时返回退出码 2。检查通过只代表应用配置满足生产安全契约，不代表 PostgreSQL、Redis、Provider、扫描器或 SSO 已真实连通；仍必须继续执行平台 readiness、外部证据、容量、备份和 UAT 验收。

脚本会要求生产显式选择 PostgreSQL/Redis、HTTPS endpoint、`secret://` 引用、企业 JWT/JWKS、真实 Provider、外部安全扫描、受控发布和跨实例搜索刷新；默认 JSON/Mock/local 配置会保持 `NOT_READY`，不会被误判为生产配置。
# 部门版本地环境（当前默认）

SkillCenter 当前默认交付目标是 Windows 单机、≤100 用户。只需要安装以下开发和运行依赖：

| 依赖 | 版本 | 用途 | 是否启动前置 |
|---|---:|---|---|
| JDK | 21+ | Spring Boot API | 是 |
| Maven | 3.9+ | API 构建和启动 | 是 |
| Node.js | 20+ | Web 构建和启动 | 是 |
| MySQL Server | 8.0+ | 部门平台唯一数据库（治理、质量、发布、优化和验收证据），127.0.0.1:3306 | 是 |
| MySQL Client (`mysql.exe`) | 8.0+ | 仅用于执行本地数据库初始化助手；也可由 Workbuddy/管理员预先创建数据库后不安装 | 否 |
| Windows PowerShell | 5.1+ | 启动、迁移和验收脚本 | 是 |

部门版默认不需要 Docker Desktop、WSL2、PostgreSQL、Redis、OpenSearch、MinIO、Prometheus、Grafana、Alertmanager、Kubernetes 或云端 Secret Manager。旧的企业级依赖清单保留在本文后续历史章节中。

初始化和启动入口：

```powershell
Copy-Item .\deploy\local\.env.department.example .\deploy\local\.env
# 编辑 .env，至少设置 SKILL_CENTER_MYSQL_PASSWORD 和 SKILL_CENTER_LOCAL_ADMIN_PASSWORD_HASH；可用 `scripts/hash-local-password.ps1 -Password '你的密码'` 生成哈希
.\scripts\bootstrap-department-mysql.ps1
.\scripts\start-local.ps1
.\scripts\verify-department-local.ps1 -FailOnMissing
# 环境通过后执行只读功能 smoke；需要质量证据写入时再显式加 -IncludeEvaluation
.\scripts\smoke-department-local.ps1
```

`bootstrap-department-mysql.ps1` 只在显式执行时使用本机 `mysql.exe`，交互式读取管理员密码，创建 `skillcenter` 数据库及仅限该库的应用账号；它不会安装、启动或删除服务，也不会写入 `.env`。如只需查看动作，可执行 `-DryRun`。
