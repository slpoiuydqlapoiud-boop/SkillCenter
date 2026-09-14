# ReleaseRecord PostgreSQL Transaction Adapter Design

## 目标

将受控发布批次 `ReleaseRecord` 从仅本地 JSON 存储推进到可切换的事务持久化边界，支撑 Skill 版本的审批、晋级、失败和回滚记录在企业部署中的一致性、幂等性和可追溯性。默认仍使用 JSON；PostgreSQL 只有在全局 persistence backend 和 release selector 同时显式启用时才生效。

## 范围与非目标

本阶段只迁移 ReleaseRecord，不迁移 GovernanceStore、Skill 事实源或质量证据，不实现运行时双写、在线切换、自动 JSON fallback、外部 ReleaseTarget 或生产数据库供应。现有 API 响应、状态机、审计事件和 `ReleaseAdmissionService` 语义必须保持不变。

## 架构

新增 `ReleaseRecordRepository` 领域端口。现有 `ReleaseRecordStore` 保留为 JSON 实现并实现该端口；新增条件化 `JdbcReleaseRecordStore` 实现 PostgreSQL 适配。`ReleaseService`、`ReleaseAdmissionService`、生命周期投影事实源和关系影响查询只依赖端口，不感知具体后端。

PostgreSQL 使用单表行模型：发布记录的稳定字段使用列保存，`gate_snapshot` 使用 JSONB 保存，状态更新在事务中完成。数据库约束与应用校验共同保证 `release_id`、`idempotency_key` 唯一，以及同一 Skill/版本/环境最多一个非终态批次；替换操作锁定当前行并再次校验不可变上下文。

## 配置与故障语义

新增 `skill-center.release-backend`，默认 `json`，可选 `postgresql`。选择 PostgreSQL 时必须同时满足 `skill-center.persistence.backend=postgresql`，否则启动状态为 fail-closed。Flyway schema 不存在、迁移失败、连接配置不完整或仓储 wiring 缺失均返回稳定错误码，不向 API、日志或审计暴露 JDBC URL、密码、SQL 或异常正文。

发布文件 artifact 在 PostgreSQL 模式下标记为 `postgresql`，不再把缺失 JSON 文件误判为损坏；A1 JSON 快照对该后端返回明确 unsupported。默认 JSON 模式不创建 DataSource，既有测试构造器和本地启动行为保持兼容。

## 验证

测试先行覆盖端口实现的 create/replace/query、幂等与活动键冲突、重启恢复、不可变上下文、JSON 默认选择、配置 fail-closed、敏感信息脱敏和 Flyway schema。Docker 可用时运行真实 PostgreSQL 集成测试；Docker 不可用时仅报告命名 capability skip。最终执行 API 全量、Web 全量/构建、生命周期 verifier 和 `git diff --check`。
