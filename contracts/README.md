# Skill Center V1 契约

本目录是 M0 阶段冻结的机器可读契约。所有日期时间使用 RFC 3339，业务统计以 `Asia/Shanghai` 作为日界线；所有版本号使用 SemVer。

## 契约文件

| 文件 | 生产者 | 消费者 | 用途 |
| --- | --- | --- | --- |
| `schemas/v1/skill.schema.json` | Skill 维护者 | 上传校验、审核、市场、客户端 | 描述部门治理所需的 Skill 元数据。 |
| `schemas/v1/install-manifest.schema.json` | 分发服务 | Agent/Codex 客户端 | 传递短期下载、哈希、权限、依赖和兼容性。 |
| `schemas/v1/installation-event.schema.json` | Agent/Codex 客户端 | 事件接入服务 | 上报安装、升级、降级和卸载结果。 |
| `schemas/v1/invocation-event.schema.json` | Agent/Codex 客户端/网关 | 事件接入服务 | 上报脱敏调用状态、耗时和错误码。 |

`examples/valid/` 中的文件必须通过 Schema；`examples/invalid/` 中的文件代表必须被拒绝的典型错误。

## Skill 包约定

ZIP 解压后只能有一个根目录，根目录名必须等于 `skill.json.id`。必需文件为 `SKILL.md` 和 `skill.json`；可选目录为 `scripts/`、`references/`、`assets/` 和 `agents/`。

`SKILL.md` 遵循 OpenAI/Agent Skills 兼容格式：YAML 前置元数据至少包含 `name` 与 `description`，正文承载 Agent 指令。部门治理字段由 `skill.json` 承担，避免改变宿主读取 `SKILL.md` 的方式。官方依据：[Build skills](https://learn.chatgpt.com/docs/build-skills)。

禁止 ZIP 含绝对路径、父路径穿越、软链接、嵌套归档、版本控制目录、凭据或策略禁止的可执行文件。具体资源阈值由平台安全配置管理，不能通过 `skill.json` 覆盖。

## 隐私边界

调用事件采用严格白名单，任何未声明字段都会被拒绝。V1 明确不接收提示词、对话、输入/输出正文、文件内容、代码、工具参数正文、客户数据、令牌和凭据。可选 `usage` 仅含模型标识和 Token 计数，默认不开启。

## 兼容与演进

- V1 Schema 采用 `additionalProperties: false`，防止客户端无意采集敏感内容。
- 在 V1 中新增字段视为不兼容变更；应发布 V2 Schema 或先升级所有消费方。
- 不得修改已发布的 V1 Schema 语义。勘误必须保留变更记录并重新运行契约测试。
- 客户端每次上报携带 `schemaVersion`；服务端在停止旧版本前必须公布迁移期。

## 本地验证

```powershell
$python = 'C:\Users\admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $python -m pip install --target .\.vendor -r .\requirements-dev.txt
$env:PYTHONPATH = (Resolve-Path .\.vendor).Path
& $python -m unittest discover -s tests\contract -p 'test_*.py' -v
```

重新生成示例 ZIP：

```powershell
& $python .\scripts\build_example_package.py
```
