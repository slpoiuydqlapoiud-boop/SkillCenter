# Immutable Skill Artifact Integrity Design

日期：2026-08-25  
状态：平台侧已实现；对象存储和生产保留策略仍开放

## 背景

SkillVersion 保存发布时的 artifact path、SHA-256 和大小。此前 Manifest 查询在路径失效时会生成演示 ZIP，造成已发布版本的身份与实际制品脱钩；下载链路也只检查路径和扩展名，没有重新校验内容哈希。

## 规则

- `SkillVersion.artifactPath` 非空时，该路径代表不可替代的已绑定制品。
- Manifest 和下载都必须验证：普通文件、非符号链接、`.zip` 扩展名、可读取 ZIP、记录的 SHA-256。
- 任一检查失败返回稳定 `published artifact was not found` 或 `published artifact integrity check failed`，不得生成替代包、修改版本记录或消耗下载授权。
- 只有 artifact path 为空的历史种子 Skill 才允许使用确定性生成包兼容本地演示。
- 原始制品正文不进入日志、错误响应或治理元数据。

## 生产边界

当前共享校验器保护本地文件制品和 API 语义；真实对象存储、跨区域复制、生命周期保留、备份/PITR、签名 URL 和生产容量/SLO 仍需目标环境适配与验收。
