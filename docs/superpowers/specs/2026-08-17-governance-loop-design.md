# M3 Skill 治理闭环设计

日期：2026-08-17

## 1. 目标

在 M2 的目录、上传、安装清单和调用统计基础上，补齐 Skill 平台的可持续管理闭环：上传包可持久化、版本有明确状态、审核动作可追踪、安装记录可查询、权限边界可验证、关键操作有审计记录。Skill 源文件仍然只能从本地 ZIP 上传，网站不创建或编辑 Skill，也不引入草稿状态。

## 2. 范围与非目标

本阶段包含：

- 本地 JSON 文件持久化，重启后恢复治理数据。
- Skill 版本状态：`pending_review`、`security_review`、`published`、`rejected`、`withdrawn`。
- 上传创建审核任务；审核通过后才进入公开市场。
- reviewer/admin 审核通过或驳回，并记录原因和操作者。
- developer、admin 两种产品角色的接口级 RBAC；`viewer`、`maintainer`、`reviewer` 仅作为既有客户端兼容别名。
- 安装记录查询和安装动作审计。
- 审计记录查询，记录动作、资源、操作者、时间、requestId 和脱敏元数据。
- 前端待审核列表、审核操作、安装记录列表和角色请求头。

本阶段不包含：SSO/LDAP、PostgreSQL、对象存储签名 URL、客户端安装回调、标签 CRUD、复杂工作流编排和通知中心。

## 3. 状态与数据模型

治理状态文件位于 `skill-center.governance-storage` 配置路径，默认 `./data/governance/state.json`。文件内容包括四个集合：

### 3.1 SkillVersion

```json
{
  "packageId": "uuid",
  "skillId": "eox-query",
  "version": "1.2.0",
  "status": "published",
  "sha256": "64 lowercase hex characters",
  "sizeBytes": 1502,
  "artifactPath": "data/packages/{packageId}.zip",
  "uploadedBy": "user-id",
  "uploadedAt": "RFC3339 timestamp",
  "publishedBy": "reviewer-id",
  "publishedAt": "RFC3339 timestamp",
  "reviewId": "uuid",
  "riskLevel": "low|medium|high"
}
```

### 3.2 ReviewTask

```json
{
  "reviewId": "uuid",
  "packageId": "uuid",
  "skillId": "eox-query",
  "version": "1.2.0",
  "status": "pending_review|security_review|approved|rejected",
  "submittedBy": "user-id",
  "submittedAt": "RFC3339 timestamp",
  "reviewedBy": "reviewer-id",
  "reviewedAt": "RFC3339 timestamp",
  "reason": "optional ordinary review reason",
  "riskLevel": "low|medium|high",
  "securityReviewedBy": "security-reviewer-id",
  "securityReviewedAt": "RFC3339 timestamp",
  "securityReason": "optional security review reason"
}
```

### 3.3 InstallationRecord

```json
{
  "installationId": "uuid",
  "manifestId": "uuid",
  "skillId": "eox-query",
  "version": "1.2.0",
  "clientType": "codex",
  "clientVersion": "1.0.0",
  "requestedBy": "user-id",
  "status": "requested|installed|failed",
  "requestedAt": "RFC3339 timestamp",
  "updatedAt": "RFC3339 timestamp"
}
```

### 3.4 AuditEvent

```json
{
  "auditId": "uuid",
  "action": "PACKAGE_UPLOADED|REVIEW_APPROVED|REVIEW_REJECTED|INSTALLATION_CREATED",
  "resourceType": "skill-package|review|installation",
  "resourceId": "resource-id",
  "actorId": "user-id",
  "actorRole": "maintainer|reviewer|admin|viewer",
  "requestId": "request-id",
  "occurredAt": "RFC3339 timestamp",
  "metadata": {"skillId": "eox-query", "version": "1.2.0"}
}
```

`metadata` 只允许固定的标识和状态字段，不允许 prompt、output、文件内容、凭据、token 或任意请求体透传。

## 4. 持久化与一致性

`GovernanceStore` 对外提供线程安全的读取和变更方法。每次变更执行以下步骤：读取当前内存快照、复制并修改、写入同目录临时文件、调用 `move` 替换正式文件；替换失败时保留原文件并返回统一 `PERSISTENCE_FAILED`。启动时若文件不存在，则从现有 `skills.json` 生成 published 基线版本，避免 M2 的 12 条目录消失。

上传包先通过 M2 校验并写入包存储，再在治理快照中写入 `pending_review` 版本和 `ReviewTask`；任一步失败都不创建可见的审核任务。公开目录继续只读 published 记录，因此 pending/rejected 版本不会被普通用户看到。

## 5. API 与权限

角色由 `X-User-Role` 请求头传递。M3 本地开发在请求头缺失时默认 `admin`，生产接入 SSO 时替换为认证过滤器；非法角色返回 `FORBIDDEN`。高风险安全复核继续使用 `admin`，不新增 `security_reviewer` 登录角色。

| API | viewer | maintainer | reviewer | admin |
| --- | --- | --- | --- | --- |
| `GET /skills`、详情、版本公开信息 | ✓ | ✓ | ✓ | ✓ |
| `POST /skill-packages` |  | ✓ |  | ✓ |
| `GET /admin/reviews` |  |  | ✓ | ✓ |
| `POST /admin/reviews/{id}/approve`（普通审核） |  |  | ✓ | ✓ |
| `POST /admin/reviews/{id}/approve`（安全复核） |  |  |  | ✓ |
| `POST /admin/reviews/{id}/reject` |  |  | ✓ | ✓ |
| `POST /skills/{id}/installations` | ✓ | ✓ | ✓ | ✓ |
| `GET /installations` | ✓（本人） | ✓（本人） | ✓（团队） | ✓（全部） |
| `GET /audit` |  |  |  | ✓ |

审核接口必须校验状态转换：普通审核只能处理 `pending_review`；高风险普通审核通过后进入 `security_review`，安全复核只能由不同的 `admin` 处理；安全审核通过后才进入 `published`。已处理任务、同人双审和自审返回 `REVIEW_STATE_CONFLICT`；每次动作写入审计事件。

安装接口继续只允许 published 版本，生成 manifest 后同步创建 `InstallationRecord(status=requested)` 并写入审计事件。新增 `GET /api/v1/installations` 支持 `status`、`skillId`、分页；新增 `GET /api/v1/skills/{skillId}/versions` 返回版本状态和时间线。

## 6. 前端行为

- 上传成功提示改为“已进入审核”，市场不会立即出现该包。
- “待审核技能”显示 pending 列表，审核员可以通过或驳回并填写原因。
- “安装记录”显示 Skill、版本、客户端、操作者、状态和时间。
- 角色选择器除了控制导航，还为 API client 注入 `X-User-Role`，viewer 操作受限时展示错误 toast。
- API 失败、状态冲突和权限拒绝沿用 M2 的 error envelope，不展示后端堆栈或敏感字段。

## 7. 测试与验收

后端先写失败测试，再实现：

- Store 写入、重启加载、原子替换和 seed 基线。
- 上传创建 pending 版本和 review task。
- viewer 上传/审核返回 403；reviewer approve/reject 的合法和非法状态转换。
- approved 版本进入公开目录，pending/rejected 不进入。
- 安装生成记录并可按角色查询；审计记录不含敏感字段。
- 既有 M0 合同、M2 目录、上传校验、安装清单、事件统计测试全部保持通过。

前端增加 API client 的 role header、review、installation 方法测试，并完成 build。最后通过 HTTP 重启验证：上传 pending → reviewer approve → 公开目录可见 → 安装记录和审计可查，重启后状态仍存在。
