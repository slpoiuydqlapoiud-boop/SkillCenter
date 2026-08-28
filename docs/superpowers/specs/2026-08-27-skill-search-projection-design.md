# A4.1 权限感知 Skill 搜索投影设计

日期：2026-08-27  
状态：已实现（本地 JSON + PostgreSQL 共享投影 + PostgreSQL 事务绑定刷新 outbox；消息总线/外部搜索未接入）

实现证据（2026-08-28）：

- API 已接入 `GET /api/v1/admin/search/index/status` 与 `POST /api/v1/admin/search/index/rebuild`，默认 backend 为进程内 JSON；普通 `GET /api/v1/skills` 使用索引候选并在分页前执行 actor-aware 授权。
- 发布、废弃、下架和范围保存成功后发布有界 `SkillSearchRefreshEvent`；协调器保留上一版命中并标记 `STALE`。
- 新增显式 `search-index-backend=json|postgresql` 选择、V16 schema 和 `JdbcSkillSearchIndex`；PostgreSQL 使用事务内 advisory lock + 全量替换，故障以 `SEARCH_INDEX_PERSISTENCE_UNAVAILABLE` fail-closed，并纳入平台 readiness。
- 新增可选 V17 `skill_search_refresh_events` 刷新日志、V18 `skill_search_refresh_event_consumers` 消费位点、V19 retention 索引和 V20 consumer lifecycle：PostgreSQL 治理聚合和 Skill 范围写入会在各自主事务内先落治理事实与 metadata-only 刷新事件，再提交；启用轮询器的实例按序消费并在消费失败时保留位点，协调器对重复投递做有界幂等；消费者身份由部署配置注入，首次注册不会复活 RETIRED 身份，心跳维护 ACTIVE 实例的新鲜度，管理员可显式 activate/retire。可选 retention scheduler 只清理早于 cutoff 且不超过仍活跃 consumer 保护水位的事件；stale consumer 只有在已追平 cutoff 内全部事件时才会被安全排除，否则继续阻塞清理，避免丢失未消费事件。已有成功写入后的事件监听仍作为兼容性幂等补偿路径。
- 搜索后端/平台 readiness 聚焦回归通过；API 全量本轮报告汇总 1073 项，0 failure、0 error、43 capability skips；Web 163/163 通过，生产构建通过。
- 当前限制：本地环境未安装 Docker，因此 PostgreSQL/Redis 真实集成尚未形成证据；未接入 OpenSearch 或消息总线。当前 transactional outbox 已覆盖 PostgreSQL 治理聚合和 Skill 范围写入，但治理聚合与范围/审计等跨聚合写入仍不是单一数据库事务；retention scheduler 不负责自动退休停滞 consumer；尚未完成真实容量/性能压测或外部 Provider 验收。

## 1. 背景与目标

当前市场查询由 `SkillCatalogService` 每次请求读取全部可见 Skill，再执行进程内子串过滤。该实现适合 MVP，但没有独立的搜索边界：Skill ID 不能稳定参与搜索，相关度与命中字段没有契约，发布/生命周期/范围变更也没有可观测的索引刷新状态。在 Skill 数量、版本和多实例增加后，重复扫描和读取制品元数据会放大延迟，并使搜索结果的新鲜度无法判断。

本阶段建立可替换的权限感知搜索投影，目标是：

- 统一索引 Skill ID、名称、描述、标签、团队、分类、状态和风险等非敏感元数据；
- 在返回结果前执行现有 `SkillAuthorizationService`，搜索索引不成为授权旁路；
- 对同一查询提供确定性相关度、命中字段和排序；
- 支持显式重建、索引状态查询和失败诊断；
- 保持现有 `GET /api/v1/skills` 请求与响应兼容，不引入 OpenSearch 或第三方 SDK；
- 为后续 PostgreSQL/OpenSearch 适配器保留端口和刷新契约。

## 2. 当前事实源与边界

搜索文档由当前治理后的 `SkillRecord` 派生。Skill/版本生命周期仍由 `GovernanceStore`、`ReleaseRecordRepository` 和上传制品事实源负责，范围授权仍由 `SkillScopeRepository` 与 `SkillAuthorizationService` 负责。搜索索引是只读投影，不成为 Skill、版本或权限事实源。

索引绝不保存：`SKILL.md` 正文、Prompt、输入输出、工具参数、制品路径、凭据、Token、Trace 和原始异常。服务端即使在内部索引中保存 owner team，也必须在 API 返回前再次执行授权。

## 3. 方案与取舍

### 3.1 采用：可替换端口 + 可重建 JSON 索引

新增 `SkillSearchIndex` 端口，定义文档写入、全量重建、查询和状态读取；新增独立的 `SkillSearchDocumentSource` 负责把当前治理后的目录事实转换成搜索文档，避免索引与目录服务相互调用。默认 `JsonSkillSearchIndex` 使用内存倒排/规范化字段，启动时由 `SkillSearchRefreshCoordinator` 从当前已发布/已废弃的治理记录重建；索引丢失时可安全重建，不影响事实源。

`SkillCatalogService` 只依赖端口，不再直接承担文本匹配和相关度计算。查询得到有界的候选 Skill ID、分数和命中字段后，再通过同一 `SkillSearchDocumentSource.findRecord(skillId)` 读取展示对象，并调用 `SkillAuthorizationService` 执行最终可见性校验；授权过滤和总数计算完成后才分页。索引与事实源短暂不一致时，继续使用上一版候选并记录安全的索引状态/刷新原因，不暴露隐藏资源；索引尚未初始化时只允许一次受控重建，不把全量扫描放回每个请求。

### 3.2 不采用：请求时全量扫描

继续在每次请求中遍历全部 Skill 无法形成可观测刷新边界，也不能稳定支持相关度和命中字段。它保留为无索引初始化或灾备重建路径，但不再作为正常查询路径。

### 3.3 不采用：本阶段直接接入 OpenSearch

OpenSearch 适合更大规模和中文分词，但会新增集群、网络、索引模板、权限和运维依赖。先冻结平台端口与文档契约，后续可在不改变 Controller 和授权语义的前提下替换实现。

## 4. 文档与查询契约

内部搜索文档 `SkillSearchDocument` 包含：

- `skillId`、`name`、`description`、`tags`、`team`、`category`；
- `status`、`risk`、`lastUpdated`、`publishedAt`、`latestVersion`；
- `visibility` 与 `ownerTeamId` 仅作为服务端候选过滤元数据；
- `sourceRevision`、`sourceHash` 和 `indexedAt` 用于状态诊断。

文本字段在写入时做 Unicode 小写、空白归一和长度上限；标签/ID 使用完整 token 与前缀 token，避免只匹配名称。查询文本按空白切词，所有 token 必须匹配至少一个可搜索字段；字段权重固定为 `skillId > name > tags > description > team/category`。相同分数按 `status、lastUpdated、skillId` 稳定排序。索引查询返回未分页但最多 5000 个候选，目录服务在授权过滤后分页，避免隐藏 Skill 影响公开总数和页内结果。

端口返回 `SkillSearchHit(skillId, score, matchedFields)`，`matchedFields` 只允许 `id/name/tags/description/team/category`，最多 6 项。对外 `SkillSummary` 增加可选 `search` 元数据时使用兼容的重载构造器，并仅在有查询文本且命中时序列化；旧客户端可忽略该字段，无查询文本时不返回命中字段和分数。

## 5. 刷新与一致性

`SkillSearchIndex` 提供：

```java
IndexStatus status();
IndexRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash);
void invalidate(String reasonCode);
List<SkillSearchHit> search(SkillSearchQuery query);
```

`SkillSearchQuery` 只包含搜索文本、分类、状态、风险和排序，不包含公开分页参数；`SkillSearchDocumentSnapshot` 提供 `documents()`、`sourceHash()` 和 `sourceRevision()`；`SkillSearchDocumentSource` 额外提供 `Optional<SkillRecord> findRecord(String skillId)`，用于按候选 ID 读取目录展示对象。`SkillSearchRefreshCoordinator.ensureReady()` 在索引尚未初始化或收到刷新失效后执行受控 snapshot/rebuild；相同 source hash 仍保持幂等。

索引 revision 单调递增；相同 `sourceHash` 重建幂等，不增加 revision。重建采用“新快照全部构建成功后一次替换”，失败时保留上一版可读索引并报告 `SEARCH_INDEX_REBUILD_FAILED`，不留下半成品。

以下事实变化必须触发失效或重建：版本发布、版本废弃、版本下架、Skill 范围保存、Skill 元数据更新以及管理员显式 rebuild。发布/生命周期/范围服务构造内部 `SkillSearchRefreshEvent`；启用 PostgreSQL V17/V18/V20 时，治理聚合或范围写入会把事件与对应事实写入同一数据库事务，随后由应用内 `SkillSearchRefreshCoordinator` 调用失效。事件日志以 `skillId/sourceRevision/reasonCode` 生成稳定 key，轮询器按 `event_seq` 顺序以至少一次语义投递到各实例，消费成功后持久化该实例的 consumer cursor，协调器使用有界去重集合吸收重复事件。可选 V20 retention scheduler 以仍活跃 consumer 保护水位为删除上界；stale consumer 只有在已追平 cutoff 内全部事件时才会被安全排除，管理员通过 activate/retire 控制生命周期。非 PostgreSQL/事件关闭的兼容实现仍由成功写入后的内部事件监听刷新；后续接入消息平台时，事件仍只携带 Skill ID、source revision 和稳定动作码，不携带正文。

索引状态至少包括 `READY`、`STALE`、`REBUILDING`、`DEGRADED`、`NOT_READY`、`sourceRevision`、`documentCount`、`indexedAt`、`reasonCode`，不包含异常正文或路径。

## 6. 授权与安全

- 查询端点仍由 `ActorResolver` 解析身份；搜索端口不接受客户端传入的 owner、visibility 或 SQL 条件；
- 先用索引做候选过滤，再对每个候选调用 `SkillAuthorizationService.requireVisible(..., CATALOG)`；
- 隐藏 Skill 的数量、ID、命中字段和索引状态不得从错误或分页总数中推断；
- 索引状态接口仅管理员可用，普通市场查询不暴露索引 source hash 的完整值；
- refresh/rebuild 审计只记录 actor、requestId、revision、文档数量、source hash 摘要和稳定结果码；
- 旧客户端不识别搜索元数据时仍能按既有 `items/page/pageSize/total` 渲染。

## 7. 管理 API

新增管理员只读状态接口和显式重建接口：

- `GET /api/v1/admin/search/index/status`；
- `POST /api/v1/admin/search/index/rebuild`。

重建接口只接受 `expectedSourceHash` 可选字段和 requestId，不接受文档正文、字段映射、数据库表名或分词配置。source hash 不匹配返回 `SEARCH_INDEX_SOURCE_CONFLICT`；重建期间重复请求按 source hash 幂等。

## 8. 测试策略

必须先 RED 后 GREEN：

- Skill ID、名称、标签、描述、团队和分类均可命中；当前查询不再遗漏 Skill ID；
- 字段权重、命中字段、同分稳定排序和分页确定性；
- published/deprecated 可见，withdrawn 不进入目录索引；
- TEAM/RESTRICTED 候选经授权二次校验，隐藏资源不泄露 ID、总数或错误细节；
- 空查询、中文/大小写/多空格、超长 token、特殊字符和无结果边界；
- 相同 source hash 重建幂等，重建失败保留旧索引，失效/恢复状态可观测；
- 发布、生命周期变更和范围变更触发 refresh coordinator；重复 refresh 不产生重复文档；
- 首次索引初始化只发生一次受控重建，正常分页查询不重复读取全部目录或制品内容；
- 管理 API 权限、requestId、稳定错误码和敏感字段脱敏；
- 既有 SkillController、前端 API client、全量 API/Web 回归保持通过；
- 后续 PostgreSQL/OpenSearch 适配器可复用同一端口契约，不把当前 JSON 实现细节写入 API。

## 9. 非目标与后续阶段

本阶段不实现中文专业分词、OpenSearch 集群、搜索结果自动推荐、Prompt/正文全文索引、搜索索引成为主数据、完整跨聚合事务或 Skill 在线编辑。

后续 A4.2 可在 Skill 元数据 PostgreSQL 主数据迁移完成后继续增强 PostgreSQL 搜索实现；后续阶段接入消息平台，补齐消息保留/清理、跨聚合事务边界、跨实例容量验收和真实环境验证。

## 10. 验收标准

1. 正常市场搜索不再读取并扫描全量目录；查询通过 `SkillSearchIndex` 完成有界候选检索，再按候选 ID 读取目录记录。
2. ID、名称、标签、描述、团队和分类搜索结果与授权边界一致。
3. 同一索引源和查询输入产生相同分数、命中字段、排序和分页。
4. 重建失败不破坏上一版索引；状态和审计只返回稳定安全元数据。
5. 现有市场 API 与旧客户端兼容，真实 Skill 正文和敏感信息不进入索引。
6. 全量回归、搜索 focused 测试和 `git diff --check` 通过；未安装外部搜索服务不阻塞默认 JSON 模式。
