# M5.3-A API 安全边界实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本项目无 Git 仓库，不执行 commit、reset 或 branch 命令。

**Goal:** 为高风险 API 增加可配置限流、幂等/重放拒绝、浏览器来源保护和统一安全响应头，并保持现有客户端兼容。

**Architecture:** 在 Spring Filter 链前置安全响应头、Origin 校验和按路由限流；在安装与导出控制器边界增加短 TTL 幂等键保护；复用现有一次性授权与 eventId 幂等逻辑。限流和幂等本阶段使用进程内适配器，接口保持可替换。

**Tech Stack:** Java 21、Spring Boot 3.4、Servlet Filter、Jackson、JUnit 5、MockMvc；前端使用现有 Node test runner 和 fetch API client。

**Spec:** `docs/superpowers/specs/2026-08-18-security-boundary-m53a-design.md`

## Global Constraints

- Skill 仍然只能从本地 ZIP 上传；本阶段不增加在线创建、编辑或草稿状态。
- 普通技能市场查询不限流；仅限制安装、授权消费、制品下载、调用事件和导出创建。
- 不接入 Redis、数据库、企业 SSO、对象存储或外部监控平台。
- 错误响应沿用 `ErrorEnvelope`，不得泄露 token、请求体、堆栈、绝对路径或用户输入原文。
- 每个行为先写一个能失败的测试，再写最小实现；完成后运行相邻回归测试。

---

### Task 1：安全配置、稳定错误输出和统一响应头

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/SecurityBoundaryProperties.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/SecurityErrorWriter.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/SecurityHeadersFilter.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/SecurityHeadersFilterTest.java`

**Interfaces:**
- `SecurityBoundaryProperties.allowedOrigins(): List<String>`、`idempotencyTtlSeconds()` 和 `rateLimit()` 为后续过滤器提供配置。
- `SecurityErrorWriter.write(HttpServletRequest, HttpServletResponse, int, String, String)` 输出现有 `ErrorEnvelope` 结构。
- `SecurityHeadersFilter` 对所有 API 响应写入五个安全头，并调用链继续执行。

- [ ] **Step 1: Write the failing test**

  在 `SecurityHeadersFilterTest` 中用 MockHttpServletRequest/Response 调用过滤器，断言响应包含 `nosniff`、`DENY`、`no-referrer`、CSP 和 Permissions-Policy；再断言 `SecurityErrorWriter` 输出 429、`error.code` 和 `requestId`。

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=SecurityHeadersFilterTest test`

  Expected: FAIL because the security package/classes do not exist.

- [ ] **Step 3: Write minimal implementation**

  创建 `@Component @ConfigurationProperties(prefix = "skill-center.security")` 配置类，给出设计规格中的默认值；实现错误 JSON 写出并设置 `Content-Type`、request ID 和状态码；实现 `OncePerRequestFilter`，仅对 `/api/` 请求写入安全头。

- [ ] **Step 4: Run test to verify it passes**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=SecurityHeadersFilterTest test`

  Expected: PASS。

---

### Task 2：固定窗口限流服务与 API 过滤器

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RateLimitRule.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RateLimitDecision.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RateLimitService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RateLimitFilter.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/RateLimitServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/RateLimitFilterTest.java`

**Interfaces:**
- `RateLimitService.check(String scope, String subject)` 返回 `RateLimitDecision(allowed, limit, remaining, retryAfterSeconds, resetEpochSeconds)`。
- `RateLimitService.clear()` 仅供测试清理计数器。
- `RateLimitFilter` 识别设计规格中的五个方法/路径 scope；不匹配时直接放行。

- [ ] **Step 1: Write the failing tests**

  为服务写“同主体第三次请求被拒绝、窗口过期恢复、不同主体互不影响、并发不超过上限”四个测试；为过滤器写“安装路径超限返回 429 和四个限流头、市场 GET 放行、主体优先使用 X-User-Id”三个测试。

- [ ] **Step 2: Run tests to verify they fail**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=RateLimitServiceTest,RateLimitFilterTest test`

  Expected: FAIL because rate-limit types and filter do not exist.

- [ ] **Step 3: Write minimal implementation**

  用 `ConcurrentHashMap` 和 `compute` 实现固定窗口计数；注入 `Clock` 的包级构造器供测试使用；过滤器按 URI 模板匹配 scope，调用 `SecurityErrorWriter` 返回 `RATE_LIMITED`，并设置 `Retry-After`、`X-RateLimit-*`。

- [ ] **Step 4: Run focused tests**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=RateLimitServiceTest,RateLimitFilterTest test`

  Expected: PASS。

- [ ] **Step 5: Run existing endpoint tests**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=DistributionControllerTest,InstallationEventControllerTest,AdminExportControllerTest test`

  Expected: PASS，说明过滤器没有改变正常请求契约。

---

### Task 3：Origin/CSRF 来源保护

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/OriginGuardFilter.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/OriginGuardFilterTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`（追加来源拒绝契约）

**Interfaces:**
- `OriginGuardFilter` 只处理 `/api/` 的写方法；无 Origin 或允许列表来源放行；其他来源调用 `SecurityErrorWriter` 返回 403 `CSRF_ORIGIN_REJECTED`。

- [ ] **Step 1: Write the failing tests**

  覆盖允许来源放行、无来源 CLI 放行、恶意来源在控制器前被拒绝、GET 查询不受影响，并断言失败响应不包含请求体内容。

- [ ] **Step 2: Run tests to verify they fail**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=OriginGuardFilterTest,ApiErrorContractTest test`

  Expected: FAIL because the filter is absent。

- [ ] **Step 3: Write minimal implementation**

  从 `SecurityBoundaryProperties` 读取 allowlist，精确匹配 scheme/host/port 字符串；仅拦 `/api/` 写请求；确保过滤器顺序在控制器之前且不依赖 Cookie 会话。

- [ ] **Step 4: Run focused tests**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=OriginGuardFilterTest,ApiErrorContractTest test`

  Expected: PASS。

---

### Task 4：安装与导出幂等键保护

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/IdempotencyEntry.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/IdempotencyService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/IdempotencyException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/AdminExportController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/IdempotencyServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/DistributionControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/AdminExportControllerTest.java`

**Interfaces:**
- `IdempotencyService.claim(String scope, String actor, String key, String fingerprint)`；新键成功占用，重复相同指纹抛 `IDEMPOTENCY_REPLAY`，不同指纹抛 `IDEMPOTENCY_CONFLICT`。
- `IdempotencyService.release(...)` 在业务失败时释放；`purgeExpired()` 清理 TTL 条目。
- 安装与导出控制器读取 `Idempotency-Key`，缺失时沿用旧行为；成功业务保留键。

- [ ] **Step 1: Write the failing tests**

  先测试键格式校验、同主体同 scope 同指纹重复、不同指纹冲突、TTL 过期后可重用；MockMvc 测试安装和导出重复请求只执行一次副作用并返回 409。

- [ ] **Step 2: Run tests to verify they fail**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=IdempotencyServiceTest,DistributionControllerTest,AdminExportControllerTest test`

  Expected: 新增幂等测试 FAIL，现有兼容测试保持可运行。

- [ ] **Step 3: Write minimal implementation**

  实现 15 分钟默认 TTL 的并发安全内存表；用 `MessageDigest` 计算请求指纹，不记录原始请求体；控制器在业务调用前 claim，业务异常 release；异常映射返回 400/409 稳定错误码。

- [ ] **Step 4: Run focused tests**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=IdempotencyServiceTest,DistributionControllerTest,AdminExportControllerTest test`

  Expected: PASS。

---

### Task 5：前端 429/403 错误体验与契约测试

**Files:**
- Modify: `apps/web/src/api/client.js`
- Test: `apps/web/tests/api-client.test.mjs`

**Interfaces:**
- `ApiError` 增加 `retryAfterSeconds`，对 `429 RATE_LIMITED` 显示可重试提示；`403 CSRF_ORIGIN_REJECTED` 显示来源校验提示；其他错误行为不变。

- [ ] **Step 1: Write the failing tests**

  构造 429/403 响应，断言 `ApiError.code`、`retryAfterSeconds` 和中文用户提示；断言普通 4xx 仍保留服务端消息。

- [ ] **Step 2: Run test to verify it fails**

  Run: `node --test apps/web/tests/api-client.test.mjs`

  Expected: 新增断言 FAIL，因为客户端尚未读取 `Retry-After` 或映射安全错误。

- [ ] **Step 3: Write minimal implementation**

  在 fetch response 解析后读取 `Retry-After`，给 `ApiError` 保存秒数；仅对两个安全错误码替换为简洁中文提示，不改写其他业务错误。

- [ ] **Step 4: Run frontend tests**

  Run: `npm.cmd test --prefix apps/web`

  Expected: PASS。

---

### Task 6：端到端联调、状态文档和路线图收口

**Files:**
- Create: `docs/project/M5.3-A-security-boundary-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/SecurityBoundarySmokeTest.java`

- [ ] **Step 1: Write the failing smoke test**

  用 MockMvc 或启动后的真实 API 覆盖正常安装、Origin 拒绝、限流 429、导出幂等冲突和安全响应头；断言现有一次性授权和 eventId 重复路径仍符合原错误码。

- [ ] **Step 2: Run it to verify the missing scenarios**

  Run: `mvn -B -q -f apps/api/pom.xml -Dtest=SecurityBoundarySmokeTest test`

  Expected: 在集成完成前至少有安全边界断言失败。

- [ ] **Step 3: Write the smoke test and status report**

  完成真实请求路径与错误契约断言；状态报告记录默认限额、allowlist、幂等 TTL、过滤器顺序、验证命令和进程内存储限制。

- [ ] **Step 4: Update roadmap**

  只勾选 M5.3-A 已实际交付的限流、Origin 防护、幂等/重放拒绝和安全响应头；保留 Redis、对象存储、监控告警、备份恢复和 RC 门禁为 M5.3-B/C 未完成。

- [ ] **Step 5: Run full verification**

  Run: `mvn -B -q -f apps/api/pom.xml test`; `npm.cmd test --prefix apps/web`; `npm.cmd run build --prefix apps/web`。

  Expected: 后端、前端全绿，生产构建成功。
