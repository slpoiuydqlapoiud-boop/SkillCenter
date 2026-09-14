# SkillCenter 本地依赖环境

部门版默认直接运行在 Windows 本机：MySQL 是唯一数据库，本地目录保存 Skill 包；不需要 Docker。

## 部门版（默认）

准备 MySQL Server 8.0+ 后，复制 `deploy/local/.env.department.example` 为本机私有配置（或在系统环境变量中设置同名变量）；如使用仓库提供的初始化助手，还需要 MySQL Client 的 `mysql.exe`，然后执行：

```powershell
.\scripts\bootstrap-department-mysql.ps1
.\scripts\start-local.ps1
```

初始化助手会交互式读取 MySQL 管理员密码，创建 `skillcenter` 数据库和仅限该库的 `skillcenter` 应用账号；应用密码从本地 `.env` 的 `SKILL_CENTER_MYSQL_PASSWORD` 读取，不会写回仓库。

该入口只检查 Java 21、Maven 3.9+、Node.js 20+ 和 `127.0.0.1:3306`，启动 API `8080` 和 Web `5173`，不会调用 Docker、Kubernetes、Redis、OpenSearch 或 MinIO。

验证部门版环境：

```powershell
.\scripts\verify-department-local.ps1 -FailOnMissing
```

环境通过后，可执行只读功能 smoke（目录、详情、内容、质量视图）：

```powershell
.\scripts\smoke-department-local.ps1
```

需要额外提交一次 `smoke` 评测并验证质量证据重读时，显式加 `-IncludeEvaluation`；该模式会提示本地管理员密码，并保留评测记录供审计。

启动脚本只会复用已通过 guest 登录和 `/api/v1/skills` 检查的部门版 API；如果 8080 被旧实例占用，会明确报端口冲突，不会把旧实例当作部门版服务。

旧的 `integration` profile 和 `compose.yaml` 只用于历史企业级联调，不是部门版前置条件。

## 历史容器联调

下面内容保留给需要复现旧适配器测试的场景；它不改变部门版默认路径，也不会被新的启动脚本调用。

## 启动

```powershell
Copy-Item .\deploy\local\.env.example .\deploy\local\.env
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml up -d
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml ps
```

也可以从仓库根目录使用一键联调入口。默认 `integration` 会复用已运行的服务，启动或等待四个依赖，并在 API 就绪后自动执行 OpenSearch 与 MinIO 制品存储管理探测，避免探测 TTL 过期导致技能目录或制品链路误报不可用：

```powershell
.\scripts\start-local.ps1 -Profile integration
```

只检查将执行的动作而不改变运行环境：

```powershell
.\scripts\start-local.ps1 -Profile integration -DryRun
```

启动后可用统一环境检查器验证依赖和观测组件：

```powershell
.\scripts\verify-environment.ps1 -CheckServices -CheckObservability -FailOnMissing
```

`-Profile default` 仍使用 API 的 JSON/Mock/单实例默认配置并绑定 8080；`integration` 使用本文件下方的 PostgreSQL、Redis、OpenSearch、MinIO 联调配置并绑定 8081。该入口只适用于本地环境，不会停止或清理已有进程、容器和数据卷。

需要运行运营观测闭环时，可显式增加 `-WithObservability`。它会额外启动 Prometheus、Alertmanager 和 Grafana，并将 Prometheus 指向本机 8081 集成 API 的受保护指标端点：

```powershell
.\scripts\start-local.ps1 -Profile integration -WithObservability
```

观测组件地址：Prometheus `http://127.0.0.1:9090`、Grafana `http://127.0.0.1:3000`（默认账号 `admin`，本地密码 `local-dev-only`）、Alertmanager `http://127.0.0.1:9093`。指标 Token 只在运行时写入系统临时目录，不进入仓库；Alertmanager 的通知地址仍是模板中的企业网关占位地址，不代表真实通知渠道已接通。

服务地址：

- PostgreSQL：`jdbc:postgresql://127.0.0.1:5432/skillcenter`
- Redis：`redis://127.0.0.1:6379`
- OpenSearch：`http://127.0.0.1:9200`（本地单节点、已关闭 Security 插件，仅用于联调）
- MinIO API：`http://127.0.0.1:9000`
- MinIO Console：`http://127.0.0.1:9001`
- 默认 bucket：`skill-packages`

## API 联调配置

先只启用需要验收的后端 selector。PostgreSQL 关系型事实源要求对应 Flyway migration 和 readiness 均通过；Redis、对象存储和真实 Provider 不能因为容器可达就自动切换，必须由管理员显式配置并完成平台 readiness。

```yaml
skill-center:
  persistence:
    backend: postgresql
    postgresql:
      url: jdbc:postgresql://127.0.0.1:5432/skillcenter
      username: ${SKILL_CENTER_POSTGRES_USERNAME}
      password: ${SKILL_CENTER_POSTGRES_PASSWORD}
  runtime-summary-backend: redis
  runtime-summary-redis-key: skill-center:runtime:summaries
  search-index-backend: opensearch
  search-index:
    mode: http
    endpoint: http://127.0.0.1:9200
    index: skillcenter-skills-v1
    credential-ref: ""
  search-index-events:
    enabled: true
    consumer-id: workbuddy-api-1
  artifact-storage-backend: object-storage
  artifact-storage:
    mode: http
    endpoint: http://127.0.0.1:9000
    bucket: skill-packages
    region: us-east-1
```

启用跨实例搜索刷新时，每个 API 实例必须配置不同但稳定的
`SKILL_CENTER_SEARCH_INDEX_EVENTS_CONSUMER_ID`；该模式依赖 Flyway V17/V18/V20，且平台 readiness
未通过前不会被视为生产可用。

可选的日志清理由 `SKILL_CENTER_SEARCH_INDEX_EVENTS_RETENTION_SCHEDULER_ENABLED=true` 显式开启，依赖
Flyway V20。每次清理只删除同时满足“早于 `retention-days` cutoff”和“事件序号不超过所有仍活跃
consumer 的保护水位”的事件；stale consumer 只有在已追平 cutoff 内全部事件时才会被安全排除，
否则仍会阻塞清理，避免删除它尚未消费的事件。下线实例应通过管理员控制 API 显式 retire 其身份；
retire 不会自动恢复，实例重新上线时必须显式 activate。

不要把这段配置直接用于生产：MinIO 开发凭据、未加密 OpenSearch HTTP endpoint、单节点 OpenSearch/PostgreSQL/Redis 和 named volume 都不满足生产高可用、TLS、密钥管理、备份/PITR 或容量/SLO 要求。启用 OpenSearch 后还必须先执行管理员搜索索引探测，并通过平台 readiness；应用不会自动回退到 JSON。

## 停止与清理

```powershell
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml down
```

默认 `down` 保留 named volumes。确认不再需要本地数据后再显式执行 `down -v`；该操作会删除本地 PostgreSQL、Redis、OpenSearch 和 MinIO 数据。
