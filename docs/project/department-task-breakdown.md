# 部门级 Skill 生命周期平台：当前活动任务分解

更新时间：2026-09-09

本文是当前目标的活动任务基线。旧的企业云原生、HA、多实例和生产外部接入清单仍保留在历史状态页中，用于追溯，不计入部门版完成率。

## 目标边界

- 用户规模：小于 100 人。
- 部署：本地 Windows 单机，默认单实例。
- 数据库：MySQL 8.0+，唯一外部数据库。
- 文件：Skill 包和运行所需制品保存于本地目录。
- 鉴权：本地账号、guest 会话和基本角色控制；不引入企业 SSO/JWKS 前置。
- 可选 Provider：Agent Runtime、MCP、LLM、评测和 Trace 适配器保留契约与 Mock，不阻断本地启动。

## 活动任务

| 编号 | 工作流 | 当前状态 | 验收证据 |
| --- | --- | --- | --- |
| D1 | Skill 资产、版本、复用共享、生命周期治理 | 已完成 | Skill/版本/Scope/Relation、发布和审计 API 及回归测试 |
| D2 | 质量保障：评测套件、Benchmark、版本对比、质量门禁 | 已完成 | Quality Center、质量证据、Benchmark、发布准入测试 |
| D3 | 运行运营：Runtime 聚合、Trace、失败定位和运营查询 | 已完成（单机内存摘要） | Operations Center、Memory/JSON 运行摘要、Trace 查询和安全投影测试 |
| D4 | 生态集成：Agent Runtime/MCP/LLM/评测/观测适配器 | 已完成（契约/Mock） | Provider catalog、readiness、契约验证和 Mock 适配器 |
| D5 | 持续优化：工作项 → 实验 → Benchmark → 决策 → 发布后观察/评估 → 后续工作项 | 已完成（MySQL 路径） | MySQL store、调度器 MySQL 门禁回归、Quality Center 闭环测试 |
| D6 | 部门本地交付：MySQL 初始化、启动、鉴权和验收脚本 | 编码完成，运行验收待执行 | `bootstrap-department-mysql.ps1`、`start-department-local.ps1`、`verify-department-local.ps1`、`smoke-department-local.ps1` |

## 当前剩余验收项

只有 D6 的真实运行态验收尚未完成：

1. 安装并启动 MySQL Server 8.0+，监听 `127.0.0.1:3306`。
2. 在 `deploy/local/.env` 设置 `SKILL_CENTER_MYSQL_PASSWORD` 和本地管理员 PBKDF2 hash。
3. 停止占用 8080 的旧 API，或确保 8080 返回部门版 guest 登录和 `/api/v1/skills`。
4. 执行 `bootstrap-department-mysql.ps1`、`start-local.ps1` 和 `verify-department-local.ps1 -FailOnMissing`。
5. 完成一次 Skill 核心生命周期写入、读取、质量证据保存和重启恢复 smoke。

在上述外部状态就绪前，不能把 Testcontainers 的 MySQL 证据冒充为本机 MySQL 运行验收；但它已证明 MySQL 驱动、Flyway V1/V2、完整部门 Spring 上下文和核心 JSON 文档事务路径可执行。

## 不计入当前活动任务的历史项

CCE/Kubernetes、Docker 运行时、PostgreSQL、Redis、OpenSearch、MinIO、企业 SSO/JWKS、生产 HA、云端 Secret Manager、真实外部 Provider、容量/SLO、备份/PITR、渗透测试和生产 UAT 均不属于部门版启动前置。相关代码和文档只作为未来可替换适配器或历史联调资产保留。
