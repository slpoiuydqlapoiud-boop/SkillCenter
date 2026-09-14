# ADR-0002：部门级 Windows + MySQL 平台基线

- 状态：替代 ADR-0001 的默认部署基线
- 日期：2026-09-09
- 决策范围：SkillCenter 部门级本地部署（不超过 100 用户）

## 决策

SkillCenter 采用 Windows 单机模块化单体部署：Spring Boot API 直接运行在本机，Web 构建为静态文件并由本机 Web 服务或 Spring Boot 静态资源提供；唯一外部数据库为 MySQL 8.0+。Skill 包和导入导出文件保存在本机受控目录，短期缓存和搜索使用进程内实现。

默认运行不依赖 Docker、Kubernetes、PostgreSQL、Redis、OpenSearch、MinIO、Kafka、SSO、JWKS 或外部安全扫描服务。现有这些适配器和云原生资产保留为历史/未来扩展，不进入部门版启动门槛。

## 部门版功能边界

- 必须保留：Skill 目录、版本、导入导出、审核、分发、质量评测、Benchmark、运行统计、Trace 摘要、失败定位和优化工作项。
- 数据库事实源：MySQL；Skill 包二进制：本地文件系统。治理、质量证据、Benchmark、发布、执行环境、Scope/Relation、优化闭环和生产验收证据均使用 MySQL JSON 文档表；不引入第二种外部数据库。
- 搜索：单进程内存索引、字段筛选和受限 LIKE 查询；不引入独立搜索集群。
- 运营：单进程内存聚合；可持久化的治理/质量/发布/优化证据写入 MySQL，搜索投影保持进程内有界实现；不要求跨实例消息总线。
- 鉴权：本地账号、密码、角色（ADMIN、MEMBER、VIEWER）和会话/JWT 任选一种本地实现；不接入企业 SSO/JWKS。
- 容量：单实例、≤100 用户；数据库连接池默认 2–10，上传单包和分页沿用现有安全上限。

## 运行结构

```text
Windows
├─ MySQL 8.0+ (127.0.0.1:3306)
├─ SkillCenter API (Java 21 / Spring Boot / 8080)
│  ├─ MySQL governance core + local auxiliary state
│  ├─ local package directory
│  └─ in-process cache/search/metrics
└─ SkillCenter Web (Vite build output, 5173 or API static resources)
```

## 迁移策略

1. 新增 MySQL 驱动和 Flyway MySQL 支持，并提供独立的 MySQL migration location。
2. 部门版默认配置使用 `mysql` 选择器；旧 PostgreSQL 选择器仅保留兼容测试和历史参考。搜索索引为进程内投影，Skill 包及运行时临时文件仍使用本地文件系统，不会连接 PostgreSQL。
3. JSON 字段使用 MySQL `JSON` 类型；PostgreSQL 专有 cast、advisory lock 和 `ON CONFLICT` 替换为 MySQL 等价实现或应用层条件更新。
4. 本地文件目录必须做规范化路径检查、包大小限制和原子写入；不把数据库密码或会话密钥写入仓库。

## 非目标

- 不建设多实例、高可用、跨区灾备、消息平台、独立搜索集群、云密钥系统和企业级 SSO。
- 不删除已有企业级设计文档和 Kubernetes 资产；这些内容标记为历史参考，不能阻塞部门版本地启动。

## 验收

- Windows 上只安装 Java 21、Maven、Node.js、MySQL 8.0+ 即可启动。
- 新机器执行本地启动脚本后，Flyway 迁移、API 健康检查、Web 首页和基础登录均通过。
- API/Web/契约测试通过，MySQL smoke test 验证 Skill 创建、版本读取、审核和质量记录可持久化。
