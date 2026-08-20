# Skill Center Web

M2 已将 M1 页面从演示 mock 状态切换为真实 Skill Center API。Skill 仍然只允许从本地 ZIP 上传，平台不提供在线创建或编辑 Skill 源文件，也没有草稿状态。

## 本地运行

先启动 API：

```powershell
mvn -f apps/api/pom.xml spring-boot:run
```

再启动 Web：

```powershell
cd apps/web
npm.cmd install --prefer-offline --no-audit --no-fund
npm.cmd run dev -- --host 127.0.0.1
```

访问 `http://127.0.0.1:5173/`。Vite 会把 `/api` 请求代理到 `http://127.0.0.1:8080`。

## 已接入的 API 流程

- `GET /api/v1/skills`：市场目录、关键字和分类筛选。
- `GET /api/v1/skills/{id}`：Skill 详情。
- `POST /api/v1/skill-packages`：本地 ZIP 上传、结构检查、schema 校验、SHA-256 计算和本地落盘。
- `POST /api/v1/skills/{id}/installations`：生成带兼容性、权限、下载地址和哈希的安装清单。
- `POST /api/v1/events/invocations`：接收 M0 白名单调用事件并按 `eventId` 幂等去重。
- `GET /api/v1/analytics/overview`：返回 7 日调用序列和热门 Skill 聚合。

## 验证

```powershell
npm.cmd test
npm.cmd run build
```

后端和合同回归命令见 `docs/project/M2-real-backend-integration-status.md`。

## M3 治理闭环

M3 在保持“Skill 只能从本地 ZIP 上传、网站不在线创建或编辑 Skill”边界的前提下，补齐上传审核、发布、安装记录和审计查询。前端角色选择器会把 `X-User-Id` 与 `X-User-Role` 发送给 API，便于本地联调。

主要接口：

- `GET /api/v1/admin/reviews` 与 `POST /api/v1/admin/reviews/{id}/approve|reject`
- `GET /api/v1/skills/{id}/versions`
- `GET /api/v1/installations`
- `GET /api/v1/audit`（仅管理员）

治理状态默认原子持久化到 `apps/api/data/governance/state.json`。验收证据与生产环境适配边界见 `docs/project/M3-governance-loop-status.md`。
