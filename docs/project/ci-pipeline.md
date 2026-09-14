# CI 质量门禁

日期：2026-09-08

`.github/workflows/skillcenter-ci.yml` 是 SkillCenter 的持续验证骨架。每次 push、Pull Request 和手工触发都会并行执行四类独立检查：

- 契约测试：Python `jsonschema` 与 `tests/contract`；
- Telemetry SDK：SDK 单元测试与 `npm pack --dry-run` 发布边界；
- Web：全量 Node 测试与生产构建；
- API：Maven 全量回归，Linux runner 使用 Testcontainers 覆盖容器集成测试。

最后的 `CI quality gate` 只有在四类检查全部成功时才成功。工作流只读仓库内容，不部署应用、不写入生产系统、不注入真实凭据，也不把本地 Mock 或 Testcontainers 结果当作生产验收证据。

生产发布仍必须经过平台 readiness、外部证据台账、审批、容量/SLO、备份恢复、真实 Provider 和 UAT 门禁；这些责任不由本工作流替代。
