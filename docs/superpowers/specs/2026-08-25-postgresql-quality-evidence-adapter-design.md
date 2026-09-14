# A2 PostgreSQL/Flyway 与 Quality Evidence 适配器设计

日期：2026-08-25  
状态：设计基线，供实施计划评审

## 1. 目标

A2 第一阶段为 SkillCenter 增加可切换的 PostgreSQL/Flyway 持久化基础设施，并将质量证据域接入真实事务型适配器。该阶段必须保留 A1 的 JSON 默认行为、资产目录语义、启动门禁、状态码、数据源/执行环境上下文和 metadata-only 边界。

完成后，平台能够在显式配置 PostgreSQL 后：

- 由 Flyway 管理数据库 schema 版本，启动前验证迁移完成；
- 由 PostgreSQL 事务保存和恢复 `QualityEvidenceState`；
- 通过同一个 `QualityEvidenceRepository` 端口服务评测、质量快照、Benchmark 和兼容性矩阵；
- 在数据库不可用、迁移失败或配置不完整时稳定 `FAIL_CLOSED`，不回退到 JSON，也不伪装为生产可用；
- 保持 `json` 为默认后端，现有本地开发、测试和历史数据不需要数据库。

## 2. 当前边界证据

- `PersistenceControlProperties` 当前只接受 `json`，A1 的 `PersistenceArtifactCatalog` 将业务资产按 JSON 文件/目录登记。
- `QualityEvidenceRepository` 已提供 `load/save/update/clear` 端口，`QualityEvidenceStore` 已集中实现完整恢复校验、主键/外键关系校验、套件版本和兼容性矩阵不变量。
- Quality Center 的评测运行、质量快照、Benchmark、兼容性矩阵和优化闭环都依赖该端口，迁移该端口可以覆盖质量保障和持续演进主链路，而不改动控制器协议。
- 当前没有 JDBC、PostgreSQL driver 或 Flyway 依赖，也没有在线数据库切换逻辑；A2 必须显式新增这些能力，不能把 JSON 文件路径改名为数据库适配器。

## 3. 方案与取舍

### 3.1 采用：端口复用 + PostgreSQL 文档快照试点

新增 `JdbcQualityEvidenceStore`，复用已有 `QualityEvidenceRepository`。数据库以单个逻辑聚合行保存完整 JSONB 文档，同时保存可审计的 revision、更新时间和 schema 标识；领域层继续使用现有 `QualityEvidenceStore` 的恢复校验，数据库层负责事务、版本和并发一致性。

这不是最终所有领域的关系表模型，而是一个可回滚的迁移桥：先证明真实事务、迁移和启动门禁；后续可以在不改变 Repository 端口的情况下，把高频查询拆为投影表，并逐域迁移治理、发布和 Skill 资产。

### 3.2 不采用：一次性全量 JPA 关系化

当前 `GovernanceStore` 被大量服务和测试直接依赖，且同时承载审核、安装、授权、通知、审计、调用事件和配置。一次性拆成多个 JPA aggregate 会扩大事务边界和回归面，无法在本阶段稳定验证。

### 3.3 不采用：所有资产统一 JSONB 且没有领域端口

把所有 Store 直接写入通用 JSONB 表会绕过现有领域校验和接口，难以保证质量证据与 Skill 生命周期状态的一致性，也无法支持后续的细粒度查询/授权投影。

## 4. 配置与后端选择

保留现有配置：

```yaml
skill-center:
  persistence:
    backend: json
  quality-evidence-backend: json
```

新增允许值：

- `skill-center.persistence.backend`: `json` 或 `postgresql`；未知值启动时以稳定 `PERSISTENCE_CONTROL_PLANE_ERROR` 失败。
- `skill-center.quality-evidence-backend`: `json` 或 `postgresql`；未显式设置时为 `json`。
- PostgreSQL 连接使用 Spring Boot 标准 `spring.datasource.url/username/password` 和连接池配置；密码只由环境/密钥系统注入，不进入状态 API、日志或异常响应。

选择规则：

1. `json` 模式只创建现有 JSON bean，不创建 JDBC 适配器；现有构造器和 focused tests 保持可用。
2. 只有当全局 `skill-center.persistence.backend=postgresql` 时，`skill-center.quality-evidence-backend=postgresql` 才合法；PostgreSQL 模式必须同时具备 JDBC、Flyway 和 `JdbcQualityEvidenceStore`，任一缺失都阻止 Spring 以 `READY` 启动。
3. PostgreSQL 模式不允许自动回退 JSON，不允许双写，不允许在请求期间隐式执行 schema 迁移。
4. A1 `PersistenceControlService` 继续是共享启动状态来源；数据库后端报告的迁移/连接错误映射为稳定原因码，不暴露 SQL、连接串、用户名以外的敏感信息或异常 cause。

## 5. 组件设计

### 5.1 后端状态端口

新增 `PersistenceBackend` 和不可变 `PersistenceBackendStatus`：

```java
public interface PersistenceBackend {
    String backendId();
    PersistenceBackendStatus status();
}

public record PersistenceBackendStatus(
        String backendId,
        String state,
        String reasonCode,
        String schemaVersion) {
}
```

`state` 只允许 `READY`、`DEGRADED`、`FAIL_CLOSED`；`reasonCode` 只允许稳定码。JSON 实现返回 A1 当前 backend 状态；PostgreSQL 实现只读取连接和 Flyway 状态，不写业务数据。

### 5.2 PostgreSQL/Flyway 配置

新增 Spring 配置类负责：

- 在 `backend=postgresql` 时提供 `JdbcTemplate`、事务管理器和 Flyway bean；
- 在 `backend=json` 时不要求数据库连接；
- 将 Flyway 迁移执行纳入启动顺序，并将失败转换为 `PERSISTENCE_MIGRATION_REQUIRED`、`PERSISTENCE_MIGRATION_UNSUPPORTED` 或 `PERSISTENCE_CONTROL_PLANE_ERROR`；
- 暴露只读 `PersistenceBackendStatus` 给 `PersistenceControlService`。

禁止业务 Controller 直接注入 `JdbcTemplate` 或 Flyway；数据库操作只能通过领域 Repository 或 persistence backend 组件进行。

### 5.3 Quality Evidence PostgreSQL 适配器

新增 `JdbcQualityEvidenceStore implements QualityEvidenceRepository`，以 `JdbcTemplate` 和 `ObjectMapper` 为依赖。其行为：

- `load()`：读取逻辑键 `quality-evidence` 的最新文档；不存在返回与 JSON Store 一致的空状态；读取后执行与 JSON Store 相同的完整状态校验。
- `save(state)`：先执行完整领域校验，再在一个事务中写入 JSONB payload、revision、updated_at 和 `document_schema_version`；不允许部分集合写入。
- `update(update)`：在事务中锁定逻辑键行，读取并校验当前状态，执行 `StateUpdate`，校验新状态后一次性更新 revision；并发更新不能静默覆盖。
- `clear()`：以事务方式保存空状态，不删除 schema 或迁移记录。
- 所有 SQL 使用参数绑定；不把 Prompt、输入输出、Trace 正文、工具参数、Token、凭据或原始异常写入 backend status 或错误响应。

数据库行模型：

```text
skill_quality_evidence_state
  aggregate_key              text primary key
  document_schema_version    integer not null
  revision                   bigint not null
  payload                    jsonb not null
  updated_at                 timestamptz not null
```

约束：`aggregate_key = 'quality-evidence'`、`revision >= 0`、`jsonb_typeof(payload) = 'object'`。JSON payload 的领域不变量仍由 Java 校验负责，数据库约束负责最小结构和唯一性。

## 6. A1 控制面与快照边界

- `PersistenceArtifactCatalog` 为 `quality-evidence` 增加后端来源信息；PostgreSQL 模式下不能把不存在的 `quality-evidence.json` 误报为业务损坏。
- PostgreSQL 模式下，A1 status 必须同时检查 backend 状态和 Quality Evidence schema/revision；控制面或数据库不可用时 `FAIL_CLOSED`。
- A1 第一阶段的文件复制快照只覆盖 JSON 文件/目录。对 PostgreSQL 质量证据，快照创建必须返回稳定 `PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED`，直到 A2 第二阶段提供数据库一致性备份/恢复适配器；不能把数据库状态伪装成文件副本。
- `restore-preflight` 继续只读；在线 restore、数据库覆盖、自动回滚和跨后端双写不在本阶段实现。
- JSON 和 PostgreSQL 后端的 metadata-only 管理 API 路径保持一致，响应不返回数据库连接信息或 payload。

## 7. 数据迁移与切换

本阶段采用显式离线导入，不做运行时双写：

1. 在隔离环境启动 PostgreSQL/Flyway，确认 schema 和 backend status 为 `READY`。
2. 停止写入质量证据，读取 JSON Store 并执行现有完整校验。
3. 通过受控导入命令/运维脚本以事务写入 `quality-evidence` 聚合，并记录来源文件 SHA-256、目标 revision 和 requestId 元数据；不写入业务正文到日志。
4. 重新读取两种后端，比较稳定的数量、ID、版本、数据源、执行环境、门禁和状态字段；不比较 JSON 字段顺序。
5. 通过 A1 startup gate 后，显式将 `quality-evidence-backend=postgresql` 作为部署配置发布。
6. 失败时保持 JSON 配置和原始文件不变；禁止自动回退或覆盖源文件。

导入命令和生产切换审批属于后续运维交付；本阶段先提供 Repository、schema、状态和测试契约。

## 8. 错误与安全边界

- 未配置 JDBC URL、连接失败、Flyway 未完成、schema 版本不支持：`FAIL_CLOSED`。
- JSON 模式下不连接数据库；数据库不可用不影响默认本地开发。
- PostgreSQL 密码不出现在配置示例、日志、API response、异常 message 或快照 metadata。
- 数据库状态接口只返回 backend、state、schemaVersion、revision（如适用）和稳定 reasonCode。
- SQL 注入、任意表名/列名、任意 migration location 和用户输入拼接均禁止。
- 连接超时、事务超时和池耗尽必须有明确的配置上限；错误响应不能回显 JDBC URL 或数据库异常 cause。

## 9. 测试策略

必须先 RED 后 GREEN：

- 后端选择测试：JSON 默认不需要 JDBC；PostgreSQL 未配置时 fail-closed；未知 backend 稳定失败。
- Flyway migration tests：schema 可重复启动、版本记录唯一、未完成迁移阻断 READY。
- JDBC Repository tests：空状态、save/load round-trip、完整校验拒绝、revision 增长、并发更新不覆盖、事务失败保留旧状态、clear 幂等。
- A1 集成测试：PostgreSQL backend status 进入共享 `PersistenceControlService`；snapshot 对 PostgreSQL 返回明确 unsupported，不复制错误文件。
- 兼容性回归：JSON QualityEvidenceStore、现有质量服务、质量控制器和 Web API 保持原有行为。
- 测试环境优先使用真实 PostgreSQL（Testcontainers 或项目现有数据库服务）；若环境没有数据库，必须保留清晰的 capability skip，不能用 H2 的宽松行为冒充 PostgreSQL 验收。

## 10. 非目标与后续阶段

本阶段不实现：

- GovernanceStore、Skill 目录、发布记录、关系和权限的全量关系化；
- PostgreSQL 数据库在线覆盖或自动双写；
- 数据库快照、PITR、对象存储备份和灾备演练；
- 生产 SSO/JWT、真实 Runner/Evaluation/Observability Provider、Redis HA；
- 以本地 Mock 或 H2 测试声称生产 PostgreSQL 已验收。

A2 第二阶段将提供数据库一致性快照/恢复适配器；之后按治理、发布、Skill 资产优先级逐域迁移，并保持同一 Repository/控制面契约。
