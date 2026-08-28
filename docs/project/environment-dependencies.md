# SkillCenter 环境依赖与 Workbuddy 搭建清单

更新时间：2026-08-28

## 1. 本地开发必需

| 依赖 | 建议版本 | 用途 | 当前状态 |
| --- | --- | --- | --- |
| JDK | 21 | Spring Boot API 编译与运行 | 已具备 |
| Maven | 3.9+ | API 构建、Surefire 测试 | 已具备 |
| Node.js | 20 LTS 或更高 | React/Vite 前端 | 已具备（当前 26） |
| npm | 随 Node.js | 前端依赖、测试、生产构建 | 已具备 |
| Git | 2.4+ | 版本管理 | 已具备 |
| PowerShell | 7+ | Windows 验证脚本、部署辅助 | 已具备 |
| Python | 3.11+ | `tests/contract` 合约测试 | 建议安装 |
| Python `jsonschema` | 4.23.0 | JSON Schema 合约校验 | `requirements-dev.txt` |

安装 Python 合约测试依赖：

```powershell
py -3.11 -m pip install -r requirements-dev.txt
py -3.11 -m unittest discover -s tests -p "test_*.py"
```

## 2. Workbuddy 必须搭建的基础设施

| 组件 | 建议版本 | 默认端口 | 用途 |
| --- | --- | ---: | --- |
| Docker Desktop | 当前稳定版 | — | Testcontainers、基础设施编排 |
| Docker Compose v2 | 随 Docker Desktop | — | 一键启动本地依赖 |
| PostgreSQL | 16+ | 5432 | 治理、质量、Benchmark、发布、执行环境等共享持久化 |
| Redis | 7+ | 6379 | 运行摘要、告警状态、分布式上传会话 |
| MinIO 或 S3-compatible 存储 | 当前稳定版 | 9000/9001 | Skill 制品与分片对象存储联调 |

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
- 跨实例搜索刷新：`SKILL_CENTER_SEARCH_INDEX_EVENTS_ENABLED=true`（需同时开启 PostgreSQL 搜索后端；启用后由 V17 journal + poller 提供 at-least-once 重放）
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

当前本机未安装 Docker，因此 PostgreSQL/Redis 集成项尚未形成真实环境证据；平台侧 API/Web 自动化回归通过不等价于生产环境就绪。
