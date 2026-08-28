# A4.2 企业级 Skill 搜索适配器设计

日期：2026-08-28
状态：已批准，待实现

## 1. 目标

在现有 `SkillSearchIndex` 端口后增加 OpenSearch/Elasticsearch 兼容的 HTTP 实现，使企业部署可以把 Skill 非敏感元数据投影到共享搜索集群，同时保留 JSON 和 PostgreSQL 的现有默认行为。适配器必须服从现有授权二次校验、刷新 outbox、source hash、稳定错误码和平台 readiness 语义。

## 2. 方案

增加 `HttpSkillSearchIndex`，使用 JDK `HttpClient` 和现有 `ObjectMapper`，不引入 OpenSearch 厂商 SDK。适配器通过配置选择：

```yaml
skill-center:
  search-index-backend: opensearch
  search-index:
    mode: http
    endpoint: https://search.example.internal
    index: skillcenter-skills-v1
    credential-ref: secret://env/SKILL_CENTER_SEARCH_TOKEN
    connect-timeout-ms: 1000
    request-timeout-ms: 5000
    max-response-bytes: 262144
    probe-ttl-seconds: 300
```

JSON 和 PostgreSQL 选择器保持不变；未配置或配置非法时不静默回退。默认 `json` 仍可在没有外部服务的环境启动。

## 3. 数据边界

适配器只发送 `SkillSearchDocument` 的非敏感元数据：Skill ID、名称、描述、标签、团队、分类、状态、风险、版本和时间字段，以及授权候选所需的 visibility/ownerTeamId。不得发送 SKILL.md、Prompt、输入输出、工具参数、制品路径、凭据、Token、Trace 或原始异常。

重建使用 `_bulk` metadata index 请求，一次请求的文档数和请求字节数有界；查询使用 `_search`，只接受 `hits.hits[]._id`、`_score` 和 allowlist 的 matched fields。未知字段、缺失字段、非法分数、未知 Skill ID、超出候选上限和 malformed JSON 均 fail-closed。查询结果最多 5000 个候选，继续由目录服务按候选 ID读取并执行授权。

## 4. 状态、探测与错误

`status()` 只返回现有 `SkillSearchIndexStatus` 的安全字段。HTTP 后端在最近一次成功 probe 未过期前保持 `NOT_READY`，probe 只访问集群的 metadata endpoint，不读取 Skill 内容；成功结果恢复自治理域的脱敏审计时必须匹配 endpoint 配置指纹、稳定 adapter ID、状态和 TTL。错误映射为稳定 code，例如 `SEARCH_INDEX_ENDPOINT_NOT_CONFIGURED`、`SEARCH_INDEX_INVALID_ENDPOINT`、`SEARCH_INDEX_PROBE_EXPIRED`、`SEARCH_INDEX_UPSTREAM_UNAVAILABLE`、`SEARCH_INDEX_RESPONSE_TOO_LARGE` 和 `SEARCH_INDEX_RESPONSE_INVALID`，不得回显 endpoint、body、凭据或异常文本。

重建采用远端 alias/index 的单次 bounded 请求边界：请求失败或响应不合法时，本地状态保持上一版可读状态并标记 `DEGRADED`；不会将半成品标记为 READY。现有 `SkillSearchRefreshCoordinator` 负责 source snapshot 和失效后的重建，适配器不自行扫描目录或改变事实源。

## 5. 测试与验收

- 选择器：默认 JSON、PostgreSQL 和显式 OpenSearch 的装配互斥；缺失/非法配置 fail-closed，不回退。
- 请求：重建 JSON 只含 allowlist 字段，bulk 内容有界；查询过滤、排序、候选上限和授权输入稳定。
- 响应：有效结果可转换为 `SkillSearchHit`；未知字段、缺失字段、负分数、超大 body 和非法 JSON 均拒绝。
- 网络：超时、非 2xx、429、5xx、重定向和连接失败只返回稳定错误码。
- 状态：probe TTL、配置指纹恢复、过期证据、未来时间戳和错误状态均保持 fail-closed。
- 安全：任何 API/审计/日志不包含 endpoint、credential-ref 的 secret value、Prompt、正文或上游异常。
- 回归：API 全量、Web 全量、生产构建、Compose 校验和 `git diff --check` 通过；未安装 OpenSearch 不影响默认模式。

## 6. 非目标

本阶段不引入 OpenSearch SDK、中文分词调优、Kafka/消息总线、搜索集群创建脚本、跨聚合事务或生产集群验收。Workbuddy 后续负责真实集群、Secret Manager、TLS、容量/SLO、故障转移和 UAT。
