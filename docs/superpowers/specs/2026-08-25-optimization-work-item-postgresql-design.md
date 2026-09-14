# 优化工作项 PostgreSQL 持久化设计

## 目标

将持续优化闭环中的 `OptimizationWorkItem` 从 JSON-only 控制面推进为可显式切换的持久化端口，使多实例部署能够共享工作项、状态流转和并发唯一性。默认开发配置继续使用 JSON；PostgreSQL 未完整配置、迁移未完成或读写失败时必须 fail-closed，不得静默回退 JSON。

## 范围与不做事项

- 本轮覆盖工作项的创建、查询、单条读取和状态/证据替换。
- 保留现有 `OptimizationWorkItem` 校验、状态机、证据上下文和审计语义。
- PostgreSQL 使用 JSONB 保存完整领域对象，同时用受控列支持筛选和唯一约束。
- 不改变公开 API，不自动发布、回滚或完成工作项。
- 不声称已完成真实 PostgreSQL HA、容量、备份/PITR 或生产验收；这些仍由目标环境负责。

## 端口与选择

新增 `OptimizationWorkItemRepository`，提供 `findAll`、`find`、`create`、`replace`。现有 JSON `OptimizationWorkItemStore` 实现该端口；新增 `JdbcOptimizationWorkItemStore` 实现 PostgreSQL 版本。

通过 `skill-center.optimization-work-item-backend` 选择 `json` 或 `postgresql`，默认 `json`。选择 PostgreSQL 必须同时满足全局 `skill-center.persistence.backend=postgresql`、有效 `JdbcTemplate`、Flyway 迁移和仓储 Bean；任一条件不满足都不能装配为运行实现。

## 数据与并发

Flyway 新增 `skill_optimization_work_items` 表。`work_item_id` 为主键；`skill_id`、`source_version`、`suggestion_id`、`status`、创建/更新时间作为查询列；其余字段通过 JSONB payload 保留完整对象。对非终态工作项建立 `(skill_id, source_version, suggestion_id)` 部分唯一索引，终态仍允许后续重新打开新的工作项。

JDBC 创建在数据库唯一约束下实现并发冲突；仓储将主键冲突与活动业务键冲突转换为现有领域冲突语义。替换操作在事务中按主键更新，并依赖同一部分唯一索引拒绝非法并发状态。

## 错误与安全边界

- JSONB 解析、校验、SQL、迁移和连接异常统一转换为 `OptimizationWorkItemPersistenceException`，由现有 503 错误契约处理。
- 不返回 SQL、JDBC URL、数据库凭据、文件路径或原始异常正文。
- 查询只返回经过 `OptimizationWorkItem` 构造校验的对象，拒绝损坏或未知字段 payload。

## 验证

- RED 测试证明默认选择 JSON、PostgreSQL 选择需要全局 PostgreSQL，以及活动业务键数据库约束存在。
- 仓储单元测试覆盖 JSONB 序列化/反序列化、空查询、冲突映射、替换不存在和持久化异常。
- 保留现有工作项服务、并发幂等、评估/实验和控制器回归。
- 最终执行 API 全量、Web 全量/构建、生命周期 verifier 和 `git diff --check`。
