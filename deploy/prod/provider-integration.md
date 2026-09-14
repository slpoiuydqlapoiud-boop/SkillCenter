# Provider 真实联调（OpenClaw / DeepEval / Langfuse）生产接入指南

> **目标**：把 4 类运行时核心 Provider 的真实 HTTP 适配层接进生产跑通：Runner（OpenClaw）→ Evaluation（DeepEval）→ Observability+Trace（Langfuse）。
> **范围**：仅生产接入配置与运维证据；代码层面 `apps/api/.../quality/*.java` 已经 100% 实现并通过单元/集成测试，本文档**不**改代码。
> **绑定证据 ID**：`PROVIDER_RUNTIME_GATEWAY` / `LLM_PROVIDER`（详见 §10 自检清单）。
> **适用版本**：apps/api 当前 main；ProductionConfigCheck.psm1 与 .env.prod.template 一一对应。

---

## 1. 三家 Provider 在 SkillCenter 中的角色

| 角色 | Provider | 调用方向 | 数据流 |
|---|---|---|---|
| **Runner**（执行） | OpenClaw Agent Runtime | SkillCenter → OpenClaw | POST 技能评估请求 → 真实执行 Skill（拉起 MCP Server、调 LLM） → 返回执行结果 |
| **Evaluation**（评估） | DeepEval | SkillCenter → DeepEval | POST runner 执行结果 → 评估 passed / score / reason |
| **Observability**（观测写） | Langfuse（observability endpoint） | SkillCenter → Langfuse | POST run summary → 落地 观测数据 |
| **Trace**（观测读） | Langfuse（trace endpoint） | Langfuse → SkillCenter | POST 窗口/过滤 → 返回 trace array（13 字段白名单） |

> 注意：Adapter 内部对四个角色各有独立超时（30s / 30s / 10s / 10s），运行时互不阻塞。Langfuse 的两个端点（observability + trace）由独立 Bean 装配，URL 必须分别配置。

---

## 2. OpenClaw 接入（SkillRunner）

### 2.1 请求契约（POST `${SKILL_CENTER_OPENCLAW_ENDPOINT}`）

```json
{
  "skillId": "<string>",
  "skillVersion": "<string>",
  "evaluationRunId": "<string>",
  "suiteId": "<string>",
  "caseId": "<string>",
  "timeoutMs": 60000,
  "scenario": "<string>",
  "runtimeId": "<string>",
  "mcpServerId": "<string>",
  "llmProviderId": "<string>"
}
```

### 2.2 响应契约

```json
{
  "status": "SUCCEEDED|FAILED|TIMEOUT|CANCELLED",
  "providerVersion": "<≤128 char, non-blank>",
  "durationMs": 0,
  "outputHash": "<≤256 char>",
  "errorCode": "<≤128 char>"
}
```

| 校验 | 规则 |
|---|---|
| HTTP 状态 | 2xx 通过；其他由 Status→ErrorCode 映射（见 §7） |
| status | 必填，枚举（见 `RunnerExecutionStatus`） |
| providerVersion | 1-128 字符，非空 |
| durationMs | 整数 ∈ [0, 120000]（最大 120s，超过视为不合格响应） |
| outputHash | ≤256 字符 |
| errorCode | 必填但可 "OK"；≤128 字符 |
| **隐式契约** | 1. 输出 **不能内嵌裸凭据**；2. Adapter 不信任响应中的任何凭据字段；3. outputHash 仅为哈希摘要，**不**含原输出 |

### 2.3 调用超时与限流

- 单请求超时 = `request.timeoutMs`（默认 30s，由 EvaluationRun 调用方注入；最大不超过 120s）
- 429 → 抛 `ProviderUnavailableException("RATE_LIMITED")`
- 2xx 但 schema 不合规 → 抛 `ProviderUnavailableException("UPSTREAM_INVALID_RESPONSE")`
- Provider 不可达 → 同上
- **不在 adapter 层重试**——失败立即返回给上层 runtime，由 EvaluationRun 决定 retry / 降级到 mock

### 2.4 健康检查（Probe）

- 探针：`GET ${endpoint}` （来自 `HttpProviderProbeTransport.probe`）
- 超时 2000ms；结果写入 `ProviderHealth.up()` / `NOT_CONFIGURED` / `CONTRACT_ONLY`
- 强制要求生产 `mode=http` 才视为 `up()`，否则是 `CONTRACT_ONLY`（adapter 仅跑契约校验不真发请求）

---

## 3. DeepEval 接入（EvaluationProvider）

### 3.1 请求契约

DeepEval 收到的是 **runner 执行结果**（已包含评估用例 ID）：

```json
{
  "caseId": "<string>",
  "status": "<SUCCEEDED|FAILED|...>",
  "providerId": "openclaw-runner",
  "providerVersion": "<string>",
  "dataSource": "production",
  "durationMs": 60000,
  "outputHash": "<string>",
  "errorCode": "<string or empty>"
}
```

> 请求体**不含**原始 prompt / completion / 敏感负载——只传执行结果与哈希。DeepEval 本身应基于 `outputHash` + 元数据计算得分，不应回查原输出。

### 3.2 响应契约

```json
{
  "passed": true,
  "score": 87,
  "reason": "<string ≤512 char, no control chars (\\x00-\\x1F except \\n\\r\\t)>"
}
```

| 校验 | 规则 |
|---|---|
| HTTP 状态 | 2xx 通过；其他见 §7 |
| passed | Boolean，必填 |
| score | 整数 ∈ [0, 100] |
| reason | 字符串 ≤512 字符；Adapter 会强制剔除控制字符（除 \\n/\\r/\\t），原文必须 UTF-8 文本 |

### 3.3 调用超时与限流

- 单请求超时 **30s**（adapter 内部硬编码，不可配）
- 429 → RATE_LIMITED；5xx → TEMPORARY_UNAVAILABLE；其他 4xx → UPSTREAM_REJECTED
- **失败 = 整次 EvaluationRun 标 FAILED**，由 governance 写 EVALUATION_RUN_xxx 审计事件

### 3.4 调用约束

- DeepEval **只**在 Runner 返回 SUCCEEDED 时被调用（runner 失败直接落评估失败，不重复评估）
- EvaluationProvider 的 `providerId = "deepeval-evaluation"`（固定）
- 同 OpenClaw，强制 `mode=http` 才视为 `up()`

---

## 4. Langfuse 接入（Observability + Trace）

> 两类端点对应**两个独立 Bean**，URL、凭据、超时都分开配置。

### 4.1 ObservabilityProvider（POST `${SKILL_CENTER_LANGFUSE_ENDPOINT}`）

| 项 | 值 |
|---|---|
| 用途 | 每次 Runner 执行成功后落一笔观测摘要 |
| 超时 | **10s**（adapter 内部硬编码） |
| 请求体 | `runId/skillId/skillVersion/status/durationMs/errorCode/dataSource/occurredAt/runtimeId/mcpServerId/llmProviderId`（11 字段，**严禁**带 raw prompt/completion） |
| 响应（可空 body） | `{"accepted": true}` —— 期望 `accepted` 为 true，否则视为失败 |
| 错误码 | 429 → RATE_LIMITED；5xx → TEMPORARY_UNAVAILABLE；其他 4xx → UPSTREAM_REJECTED |

### 4.2 TraceProvider（POST `${SKILL_CENTER_LANGFUSE_TRACE_ENDPOINT}`）

| 项 | 值 |
|---|---|
| 用途 | admin/runtime 查询观测数据（用于 trace 定位 / Operations 仪表盘） |
| 超时 | **10s**（adapter 内部硬编码） |
| 请求体 | `windowSeconds/now` 必填；`skillId/version/traceId/status/dataSource/runtimeId/mcpServerId/llmProviderId` 按需填 |
| 响应 | **JSON array**，每条对象必须包含 13 字段白名单：`traceId/spanId/skillId/version/operation/status/durationMs/errorCode/dataSource/occurredAt/runtimeId/mcpServerId/llmProviderId` |
| occurredAt | ISO 8601（`OffsetDateTime.parse`）；durationMs 必须整数 |
| 错误码 | 同 ObservabilityProvider |

> **返回字段 13 项白名单**——Adapter 会对每个返回对象检查 field names，发现任意**未知字段**（如 `rawPrompt`、`userEmail`、`authToken`）立即抛 `UPSTREAM_INVALID_RESPONSE`。Langfuse 真实集成时确保 trace payload **不包含**这些字段。

### 4.3 凭据与健康

- 三个 Langfuse 行为共享一个 `credentialRef`（`SKILL_CENTER_LANGFUSE_CREDENTIAL_REF`），但 token 用途相同所以统一注入
- Probe 健康状态与 OpenClaw/DeepEval 一致；强制 `mode=http`

---

## 5. 凭据注入两种模式

### 模式 A：默认 Bean `EnvironmentProviderCredentialResolver`（推荐起步）

- 解析规则：credentialRef 必须以 `secret://env/` 开头，后跟**仅 `[A-Z_][A-Z0-9_]{0,127}` 的环境变量名**
- 模板默认：`secret://env/SKILL_CENTER_OPENCLAW_TOKEN` 等
- 部署时直接 `export SKILL_CENTER_OPENCLAW_TOKEN=xxx` 后注入容器即可
- **限制**：仅依赖环境变量，不轮转、不分区、不挂审计

### 模式 B：替换 Bean（生产推荐）

`ProviderAdapterConfiguration.providerCredentialResolver()` 当前默认返回 `EnvironmentProviderCredentialResolver`。生产推荐在 startup 替换为真正的 Secret Manager 客户端：

```java
@Bean
ProviderCredentialResolver providerCredentialResolver(SecretManagerClient client) {
    return reference -> {
        if (!reference.startsWith("secret://")) throw unavailable();
        String path = reference.substring("secret://".length());
        return client.read(path);   // 例 secret://skillcenter/prod/openclaw/token
    };
}
```

> ⚠️ **轮转策略**：方式 B 的 Secret Manager 客户端必须实现**后台热轮转**——返回的 token 失效时**自动重新拉取**，不需要重启 Spring。Adapter 每条请求都会调 `resolve(reference)`，没有缓存，轮转天然生效。

> ⚠️ **审计**：方式 B 强烈推荐每次 `resolve()` 调用写一次 Secret Manager 访问审计（who/when/path/hash-of-value），与 `actor` / `requestId` 关联。

---

## 6. 连接性探针与调度

| 探针字段 | 说明 |
|---|---|
| ProviderProbeService 启用条件 | 任何 provider 的 `mode` ≠ `mock` 时才会进入 HTTP 探针 |
| 调度器类 | `ProviderConnectivityProbeScheduler`（每 60s 拉一次所有 provider） |
| 单次超时 | 2000ms (`PROBE_TIMEOUT`) |
| 缓存 TTL | `probe-ttl-seconds`（默认 300s），过期自动标 STALE |
| 探针状态 | `REACHABLE`（2xx）/ `HTTP_ERROR` / `TIMEOUT` / `UNREACHABLE` / `FAILED` / `STALE` / `NOT_CONFIGURED` / `SKIPPED` |
| 审计 | 仅 admin 主动调用 `probe()` 写 `PROVIDER_CONNECTIVITY_PROBED` 事件到 GovernanceStore；调度器调用 `probeScheduled()` 不写审计 |
| 查询接口 | `GET /api/v1/admin/provider-probe`（仅 admin） |

### 生产推荐运行参数

```yaml
probe-ttl-seconds: 300                # 5 分钟
probe-scheduler-enabled: true
probe-scheduler-interval-ms: 60000    # 1 分钟
probe-scheduler-initial-delay-ms: 1000
```

- Admin 探针（手动）：每次发布后 + 故障时主动调
- Scheduler 探针（自动）：每隔 60s 静默写缓存；**不写审计**（避免审计风暴）
- STALE 处理：`probe-ttl-seconds` 过后查询自动标 `STALE`，5 分钟内一定有新数据

---

## 7. HTTP 错误码映射表

| 上游响应 | Adapter 抛出错误码 | 传播路径 |
|---|---|---|
| 2xx + schema 合法 | (正常返回) | 由 EvaluationRun 写出 |
| 2xx + schema 非法 | `UPSTREAM_INVALID_RESPONSE` | → 评估失败 / 观测丢弃 / trace 列表空 |
| 401/403 | `UPSTREAM_REJECTED` | → 凭据失效告警，**Provider 立即停止接受请求**直到凭据更新 |
| 429 | `RATE_LIMITED` | → 建议 LLM_PROVIDER 限流告警，**adapter 不重试**，由 runtime 决定退避 |
| 5xx | `TEMPORARY_UNAVAILABLE` | → 暂时性失败，runtime 可短退避重试 |
| ConnectTimeout / ReadTimeout | `UPSTREAM_UNREACHABLE`（probe）/ `UPSTREAM_INVALID_RESPONSE`（execute）| → 评估/观测都失败 |
| DNS / TCP refused | 同上 | → 通常伴随 `UNREACHABLE` 探针状态 |

> 任何错误码映射都通过 `ProviderUnavailableException` 抛出，由 `ProviderUnavailableException` 的 reason 字段带出。**禁止** adapter 吞掉异常降级到 mock——这是 fail-closed 原则。

---

## 8. 上线 Checklist（9 步）

按下面顺序逐步完成，每步可独立回滚：

1. **Secret Manager 准备**
   - 创建 3 个 secret：`skillcenter/prod/openclaw/token`、`skillcenter/prod/deepeval/token`、`skillcenter/prod/langfuse/token`
   - 给 runtime ServiceAccount 最小读权限（read-only）
2. **替换 `ProviderCredentialResolver` Bean**（如走模式 B）
   - 在 startup 模块里 `@Bean` 替换为 `SecretManagerProviderCredentialResolver`
   - 单元测试：明确断言 `secret://env/BAD-NAME` 抛 `EXTERNAL_ADAPTER_NOT_CONFIGURED`
3. **生成 `.env.prod.<cluster>`**
   - 复制模板，按本集群真实值填空
   - `secret://env/SKILL_CENTER_OPENCLAW_TOKEN` 路径与注入 secret 名一致
4. **首次 dry-run（staging 环境跑一遍）**
   - `verify-production-config.ps1` 必须 0 failure
   - `GET /api/v1/admin/provider-probe` 返回 3 个 `REACHABLE`
   - 跑一次冒烟：发起一个 Skill evaluation → 收到 OpenClaw 执行 → DeepEval 评分 → Langfuse 收观测
5. **生产部署**
   - ConfigMap/Secret 替换 → 重启 API
   - 日志关键词：`started ProviderConnectivityProbeService`、`probe result for openclaw-runner: REACHABLE`
6. **上线后 1 小时**
   - admin 调 `probe()` 写 3 条 `PROVIDER_CONNECTIVITY_PROBED` 审计
   - 在 Langfuse UI 验证一笔观测已落地
7. **上线后 24 小时**
   - scheduler 跑了 ~1440 次（如果 60s 一次）；自查 admin probe 接口的 STALE 计数
   - 拉一次 trace 查询（`GET /api/v1/admin/operations/observations`）验证 13 字段不漏
8. **故障演练（建议上线 72h 内）**
   - 故意停掉一家 Provider → probe 应变 `UNREACHABLE`/`TIMEOUT` → 业务降级到 mock 还是 hard fail？确认策略
9. **证据归档**
   - 4 个文件：`docs/operations/provider-uat-evidence-YYYYMMDD/` 下放 20 项自检清单产物（§10）

---

## 9. 故障场景速查

### 9.1 OpenClaw 全部失败

- 探针：`UNREACHABLE`
- Adapter：每条 evaluation 都抛 `UPSTREAM_UNREACHABLE` / `TEMPORARY_UNAVAILABLE`
- 业务影响：EvaluationRun 全部 FAILED，但同时 mock fallback **不应启动**——fail-closed
- 行动：检查 OpenClaw 集群 → 检查网络 ACL → 与 OpenClaw 团队确认

### 9.2 DeepEval 持续 5xx

- 探针：`HTTP_ERROR`（5xx 不算 UNREACHABLE，是能拿到 status）
- 业务影响：EvaluationRun 的 EVALUATION 阶段 FAILED，Observability 仍正常
- 行动：DeepEval 后端有 bug？metrics 看 5xx 错误率，与 DeepEval 团队联调

### 9.3 Langfuse Observability 慢但 Trace 正常

- 探针 observability：`TIMEOUT`（>10s）
- 探针 trace：`REACHABLE`
- 业务影响：观测数据**可能丢失**（10s 超时会让 Adapter 直接抛），trace 查询不受影响
- 行动：检查 Langfuse 写入队列；可临时把 Langfuse observability **降级**（置 provider 不存在），但**绝不**降级 trace

### 9.4 凭据突然失效（rotation 中）

- Probe 探针先变 `HTTP_ERROR`（401）→ `ProviderHealth.down()`（文档未明示 down，但配置层视为不可用）
- Adapter 401 → `UPSTREAM_REJECTED`
- 行动：Secret Manager 重新签发 → 重启 API（模式 A）或等待 adapter 下次 resolve（模式 B）

### 9.5 Trace 返回含未知字段

- Adapter 抛 `UPSTREAM_INVALID_RESPONSE`
- 原因：Langfuse payload 含 `rawPrompt` / `userEmail` 等 13 字段之外内容
- 行动：检查 Langfuse 集成侧的 scrubbing 配置；让 Langfuse 团队确认数据脱敏层已开启

---

## 10. 证据 ID 自检清单：PROVIDER_RUNTIME_GATEWAY + LLM_PROVIDER

> 上线 UAT 时把以下 20 项产物归档到：
> `docs/operations/provider-uat-evidence-YYYYMMDD/PROVIDER_RUNTIME_GATEWAY-{01..15}.md` + `LLM_PROVIDER-{16..20}.md`

### 配置层（5 项）

| # | 校验 | 通过 | 产物 |
|---|---|---|---|
| 01 | ProductionConfigCheck（17 个 Provider 变量 + 4 个 Probe）全部 0 failure | ☐ | `verify-production-config-output.txt` |
| 02 | `.env.prod.<cluster>` 与 template 100% 对齐 | ☐ | `diff-env-vs-template.patch` |
| 03 | `application-prod.yml` 注入后 `mode=http` 全开 | ☐ | `application-prod.rendered.yml` |
| 04 | credentialRef 全部 `secret://` 前缀，无明文 | ☐ | `grep-secret-ref.txt` |
| 05 | 三个 endpoint 全部 HTTPS（含 `https-uri` 校验通过）| ☐ | `verify-https-uri.sh.output` |

### 运行时契约（5 项）

| # | 校验 | 通过 | 产物 |
|---|---|---|---|
| 06 | OpenClaw 真实执行返回正确 schema（durationMs ∈ [0,120000]、providerVersion 1-128） | ☐ | `openclaw-sample-response.json` |
| 07 | DeepEval 响应无控制字符、score ∈ [0,100] | ☐ | `deepeval-sample-response.json` |
| 08 | Langfuse Observability `accepted:true` | ☐ | `langfuse-ack.json` |
| 09 | Langfuse Trace 数组 + 13 字段白名单（无 rawPrompt/userEmail） | ☐ | `langfuse-trace-sample.json` |
| 10 | 错误码映射表符合 §7（429→RATE_LIMITED 等）| ☐ | `error-mapping-table.xlsx` |

### 探针与健康（3 项）

| # | 校验 | 通过 | 产物 |
|---|---|---|---|
| 11 | scheduler 60s/次正常工作 24 小时 | ☐ | `scheduler-24h-log.txt` |
| 12 | admin probe 写入 3 条 `PROVIDER_CONNECTIVITY_PROBED` 审计事件 | ☐ | `audit-events-export.json` |
| 13 | 探针结果 STALE/REACHABLE 转换在 TTL=300s 内 | ☐ | `probe-ttl-trace.png` |

### 故障演练（4 项）

| # | 校验 | 通过 | 产物 |
|---|---|---|---|
| 14 | 故意停 OpenClaw → probe UNREACHABLE → EvaluationRun 不降级到 mock（fail-closed） | ☐ | `fail-closed-drill.md` |
| 15 | 故意返回 DeepEval 502 → 业务硬 FAIL + metrics 告警 5xx | ☐ | `deepeval-5xx-drill.md` |
| 16 | 凭据轮转演练（停 → 重发 → 不重启 API → 下次请求自动成功） | ☐ | `secret-rotation-drill.md` |
| 17 | Trace 字段越界测试（Langfuse 加字段 → Adapter 拒绝 + UPSTREAM_INVALID_RESPONSE）| ☐ | `trace-schema-reject.md` |

### 上线门禁（3 项）

| # | 校验 | 通过 | 产物 |
|---|---|---|---|
| 18 | 业务指标：SLO 评估器跑一周 0 异常 | ☐ | `slo-week1-report.md` |
| 19 | LLM Provider 限流演练：OpenClaw 上游 429 → runtime 退避重试 ≤3 次 | ☐ | `llm-rate-limit-drill.md` |
| 20 | 上线签字：业务方 + 平台 SRE + 安全三方共同签字 | ☐ | `RELEASE_APPROVAL-Provider.md` |

---

## 附录 A：与现有 ProductionConfigCheck 的对应

| ProductionConfig 校验 ID | 模板中变量 | 取值约束 |
|---|---|---|
| provider.runner | `SKILL_CENTER_PROVIDERS_RUNNER` | `equals: openclaw` |
| provider.evaluation | `SKILL_CENTER_PROVIDERS_EVALUATION` | `equals: deepeval` |
| provider.observability | `SKILL_CENTER_PROVIDERS_OBSERVABILITY` | `equals: langfuse` |
| provider.trace | `SKILL_CENTER_PROVIDERS_TRACE` | `equals: langfuse` |
| provider.openclaw-mode | `SKILL_CENTER_OPENCLAW_MODE` | `equals: http` |
| provider.deepeval-mode | `SKILL_CENTER_DEEPEVAL_MODE` | `equals: http` |
| provider.langfuse-mode | `SKILL_CENTER_LANGFUSE_MODE` | `equals: http` |
| provider.openclaw-endpoint | `SKILL_CENTER_OPENCLAW_ENDPOINT` | `https-uri` |
| provider.openclaw-credential-ref | `SKILL_CENTER_OPENCLAW_CREDENTIAL_REF` | `secret-ref` |
| provider.deepeval-endpoint | `SKILL_CENTER_DEEPEVAL_ENDPOINT` | `https-uri` |
| provider.deepeval-credential-ref | `SKILL_CENTER_DEEPEVAL_CREDENTIAL_REF` | `secret-ref` |
| provider.langfuse-endpoint | `SKILL_CENTER_LANGFUSE_ENDPOINT` | `https-uri` |
| provider.langfuse-trace-endpoint | `SKILL_CENTER_LANGFUSE_TRACE_ENDPOINT` | `https-uri` |
| provider.langfuse-credential-ref | `SKILL_CENTER_LANGFUSE_CREDENTIAL_REF` | `secret-ref` |

> 4 个 `PROVIDERS_PROBE_*` 不在 ProductionConfigCheck 内（默认即可），但生产 profile 显式声明。

## 附录 B：相关源文件清单

```
apps/api/src/main/java/com/huawei/skillcenter/quality/
├── SkillRunner.java                       # Runner 接口（含 capabilities/health）
├── EvaluationProvider.java                 # Evaluation 接口
├── ObservabilityProvider.java              # Observability 接口
├── TraceProvider.java                      # Trace 接口 + TraceQuery/Observation/Window
├── ProviderAdapterConfig.java              # 配置 record（模式 + endpoint + credentialRef）
├── ProviderAdapterConfiguration.java       # 4 个 Bean 自动装配（@ConditionalOnProperty）
├── EnvironmentProviderCredentialResolver.java # 默认 Bean（secret://env/<NAME>）
├── ProviderCredentialResolver.java         # 凭据解析接口（生产替换点）
├── ProviderHttpTransport.java              # HTTP transport 接口
├── JavaHttpProviderTransport.java          # 默认 JDK HttpClient transport
├── ProviderHttpRequest.java                # 请求 record
├── ProviderHttpResponse.java               # 响应 record
├── ProviderHealth.java                     # 三态 UP / NOT_CONFIGURED / CONTRACT_ONLY
├── ProviderUnavailableException.java       # 三类错误码 + reason
├── OpenClawRunnerAdapter.java              # OpenClaw 适配
├── DeepEvalEvaluationAdapter.java          # DeepEval 适配
├── LangfuseObservabilityAdapter.java       # Langfuse 观测写入适配
├── ProviderConnectivityProbeService.java   # 三家统一探针（admin only）
└── ProviderConnectivityProbeScheduler.java # scheduler @Scheduled 触发
apps/api/src/main/java/com/huawei/skillcenter/operations/
└── LangfuseTraceProviderAdapter.java       # Langfuse 观测查询适配
```

> **禁止改动**上述任何文件以满足本指南；如发现 bug/缺口请在 docs 下写 RFC 后再做代码变更。

---

## 附录 C：与已交付 4 个 P0 文档的关系

| 本指南章节 | 关联文档 |
|---|---|
| §5 凭据两种模式 | `deploy/prod/secret-references.md`（Secret Manager 命名表） |
| §5 凭据两种模式 | `sso-integration.md`（凭据注入模式同源） |
| §6 探针与调度 | 与 `security-scanner.md` §6、`redis-ha.md` §6 的 readiness 模式一致 |
| §7 错误码映射 | 与 `security-scanner.md` §5 错误码体系同源（一致 5 类） |
| §10 自检清单 | 与 `sso-integration.md` §8、`security-scanner.md` §9、`redis-ha.md` §8 同结构（20 项） |

至此 P0 上线硬门槛已完成 **5 / 5**（SSO_ORGANIZATION / PROVIDER_SECURITY / REDIS_HA / OBJECT_STORAGE 模板就位 / **PROVIDER_RUNTIME_GATEWAY** 本步骤）。
