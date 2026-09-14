# 部门级 Windows + MySQL SkillCenter 设计

## 背景

当前仓库已经形成了较完整的 Skill 生命周期领域能力，但默认联调和生产设计包含 PostgreSQL、Redis、OpenSearch、MinIO、Docker、Kubernetes、云密钥、SSO/JWKS 和外部扫描等企业级依赖。目标调整为部门级本地平台后，这些依赖会显著增加安装和收敛成本，并非 ≤100 用户场景的必要条件。

## 目标

让部门用户在一台 Windows 机器上通过 Java 21、Node.js 和 MySQL 运行 SkillCenter，继续覆盖资产沉淀、版本/审核、质量评测、调用运营和持续优化闭环；外部执行环境（Agent Runtime、MCP、LLM）仍通过现有 provider contract 接入，但不要求部署外部平台。

## 方案

- API：现有 Spring Boot 模块化单体，8080 端口。
- Web：现有 Vite 应用，开发端口 5173；可构建为静态目录。
- 数据库：MySQL 8.0+，Flyway 负责 schema 迁移；JSON 文档字段使用 MySQL JSON。
- 文件：`data/packages` 保存 Skill 包，`data/backups` 保存逻辑备份/导出；写入使用临时文件后原子替换。
- 缓存/搜索/事件：进程内实现；关闭 Redis、OpenSearch、MinIO 和消息总线的默认依赖。
- 鉴权：本地账号/角色；默认提供可配置的初始管理员，不在仓库保存真实密码。
- 运行：PowerShell 启动脚本负责检查 Java、Maven、Node、MySQL、迁移和健康端点，不执行 Docker 命令。

## 数据流

用户登录后获得本地会话，前端请求 API；治理、质量、Benchmark、发布、执行环境、优化闭环和生产验收证据写入 MySQL JSON 文档表；Skill 包写入本地目录并记录 hash/路径；搜索投影和运行时聚合保持进程内，Provider 未配置时明确显示 contract-only，不阻塞目录和管理流程。

## 失败处理

- MySQL 不可达或 migration 失败：API 启动失败并给出可操作的连接/迁移错误，不回退到另一个数据库。
- 本地包目录不可写或路径越界：拒绝上传，保留稳定业务错误码。
- Provider 未配置：功能状态显示 `CONTRACT_ONLY`，基础 Skill 管理不受影响。
- 登录失败：返回通用错误，不泄露账号是否存在；审计只记录 actor、结果和时间。

## 测试策略

1. TDD：先为配置选择、启动依赖检查和 MySQL schema 合同写失败测试。
2. 单元/领域回归：保留现有 API 和 Web 测试。
3. MySQL smoke：使用本机 MySQL，执行 Flyway 后验证核心 Skill 生命周期写读。
4. 启动验收：验证无 Docker 命令、API 8080、Web 5173、登录和健康检查。

## 明确不做

本阶段不实现 Kubernetes/CCE、Secret Manager、Redis HA、OpenSearch 集群、S3、Kafka、多实例一致性、SSO/JWKS、复杂 RBAC、PITR 和跨地域灾备。
