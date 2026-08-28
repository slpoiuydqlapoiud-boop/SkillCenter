# SkillCenter 本地依赖环境

该编排只启动本地联调依赖，不启动 API/Web，也不会改变应用默认的 JSON、local、Mock 和 contract-only 配置。

## 启动

```powershell
Copy-Item .\deploy\local\.env.example .\deploy\local\.env
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml up -d
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml ps
```

服务地址：

- PostgreSQL：`jdbc:postgresql://127.0.0.1:5432/skillcenter`
- Redis：`redis://127.0.0.1:6379`
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
  artifact-storage-backend: object-storage
  artifact-storage:
    mode: http
    endpoint: http://127.0.0.1:9000
    bucket: skill-packages
    region: us-east-1
```

不要把这段配置直接用于生产：MinIO 开发凭据、HTTP endpoint、单节点 PostgreSQL/Redis 和 named volume 都不满足生产高可用、TLS、密钥管理、备份/PITR 或容量/SLO 要求。

## 停止与清理

```powershell
docker compose --env-file .\deploy\local\.env -f .\deploy\local\compose.yaml down
```

默认 `down` 保留 named volumes。确认不再需要本地数据后再显式执行 `down -v`；该操作会删除本地 PostgreSQL、Redis 和 MinIO 数据。
