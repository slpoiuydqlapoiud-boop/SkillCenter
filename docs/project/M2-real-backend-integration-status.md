# M2 真实后端接入状态

日期：2026-08-17

## 交付范围

M2 已完成 Spring Boot API、React API 适配层和本地联调闭环。实现范围是管理与分发优先，并接入调用数据统计；仍然不提供在线创建/编辑 Skill 或草稿状态。

### 后端

- `apps/api`：Spring Boot 3.4.5 / Java 21 / Maven。
- 统一成功响应 `{data, requestId}` 和错误响应 `{error:{code,message,details},requestId}`。
- Skill 目录从 classpath `skills.json` 加载，支持关键字、分类、状态、风险和分页。
- ZIP 上传先检查压缩包大小、单一根目录、路径穿越、嵌套 ZIP、解压大小、`skill.json` schema、`SKILL.md` frontmatter，再以 SHA-256 命名元数据落盘。
- 安装接口返回 24 小时有效的 manifest，包含 Skill 版本、兼容性、权限、依赖、artifact URL 和 64 位小写 SHA-256。
- 调用事件只接收 M0 allow-list 字段；未知字段（包括 prompt、output、文件内容、token）返回 `EVENT_SCHEMA_INVALID`；按 `eventId` 幂等去重。
- 分析接口返回 KPI、最近 7 日序列和热门 Skill；未产生事件时使用目录指标作为可见性基线，事件进入后切换为真实事件聚合。

### 前端

- `src/api/client.js`：10 秒超时、JSON 错误 envelope、multipart 上传。
- `src/api/skillApi.js`：目录、详情、上传、安装、统计、事件六类请求。
- `App.jsx`：市场、详情、上传、安装、统计改为 API 驱动，并提供 loading/error 状态；保留角色导航和上传本地 ZIP 的交互。
- `vite.config.mjs`：开发环境 `/api` 代理到 `127.0.0.1:8080`。

## 验证证据

| 检查 | 命令/请求 | 结果 |
| --- | --- | --- |
| 后端单元与 MockMvc | `mvn -B -f apps/api/pom.xml test` | 13 tests passed |
| 前端单元 | `npm.cmd test`（`apps/web`） | 10 tests passed |
| 前端构建 | `npm.cmd run build`（`apps/web`） | Vite build passed，生成 `dist/client` 与 Sites server 包 |
| M0 合同回归 | `python -m unittest discover -s tests -p 'test_*.py' -v` | 11 tests passed |
| HTTP 目录/详情 | `GET /api/v1/skills?page=1`、`GET /api/v1/skills/eox-query` | 12 条目录项，详情 id=`eox-query` |
| HTTP 安装清单 | `POST /api/v1/skills/eox-query/installations` | 201，manifestId、64 位 sha256、compatibility 均存在 |
| HTTP 统计 | `GET /api/v1/analytics/overview` | 7 日序列、5 个热门 Skill |
| 事件幂等 | 同一 `eventId` POST 两次 | 首次 202/duplicate=false，重复 200/duplicate=true |
| 敏感字段拦截 | 事件增加 `prompt` 字段 | 400 / `EVENT_SCHEMA_INVALID` |
| Web 代理 | `GET http://127.0.0.1:5173/api/v1/skills?page=1&pageSize=12` | 代理返回真实 Skill 数据 |

## 当前边界与 M3 建议

- M2 使用内存目录和本地文件存储；生产环境需替换 PostgreSQL、对象存储、审计日志和消息队列适配器。
- 当前 artifact URL 是内部配置生成的下载地址，M3 需要接入真实对象存储签名下载和客户端回调。
- 当前角色由前端本地选择器模拟；M3 接入 Huawei SSO、组织目录和后端 RBAC。
- 安装、收藏、审核、标签管理的业务写接口可在 M3 按同一错误 envelope 扩展。
