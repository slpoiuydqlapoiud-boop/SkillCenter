# `@skillcenter/telemetry`

SkillCenter 安装与调用事件的最小参考客户端。它只发送现有 `event-batch.schema.json` 允许的元数据，不接受或持久化 Prompt、输入输出正文、Token 或凭据字段。

## 能力

- 安装事件和调用事件分别进入对应批量 API；单批最多 100 条。
- 使用可注入的 `storage` 保存离线队列，浏览器默认使用 `localStorage`，Node 可传入自己的存储实现。
- 网络错误、408、429 和 5xx 使用有界指数退避；`Retry-After` 也受 `maxDelayMs` 限制。
- 成功和重复回执从队列移除；部分失败只移除已处理事件，传输失败保留队列等待下次 flush。
- 客户端启动恢复队列时重新执行当前 Schema/字段白名单校验，旧格式、篡改项和敏感字段会被丢弃，不会被发送。
- 默认队列最多 500 条，HTTP 认证头由调用方注入且不会写入队列。
- 包含 `index.d.ts` 类型声明；调用事件允许 `gateway` 客户端，安装事件严格遵循 V1 Schema 仅允许客户端/Agent 类型。

## 使用

```js
import {
  createInstallationEvent,
  createInvocationEvent,
  createTelemetryClient,
} from "@skillcenter/telemetry";

const telemetry = createTelemetryClient({
  endpoint: "https://skill-center.example",
  headers: { Authorization: "Bearer <machine-token>" },
});

telemetry.enqueueInvocation(createInvocationEvent({
  occurredAt: new Date().toISOString(),
  skillId: "summarize-release-notes",
  version: "1.0.0",
  subject: { userId: "alice", teamId: "platform" },
  client: { type: "codex", version: "1.2.3" },
  sessionId: "session-1234567890",
  status: "success",
  durationMs: 42,
}));

await telemetry.flush();
```

不要把长期机器凭据硬编码到浏览器；生产客户端应通过网关或短期凭据注入 `headers`。
