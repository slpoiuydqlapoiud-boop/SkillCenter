# Immutable Skill Artifact Integrity Implementation Plan

- [x] 先写测试覆盖有效 ZIP、缺失路径、非 ZIP 内容和 SHA-256 不匹配。
- [x] 实现共享 `ArtifactIntegrityVerifier`，统一存在性、符号链接、ZIP 可读性和哈希校验。
- [x] 接入 `ArtifactPackageService.metadata`，绑定制品失效时 fail-closed，不生成替代包。
- [x] 接入 `ArtifactDownloadService`，下载前重新验证内容并返回实际验证后的摘要。
- [x] 保持无绑定制品历史种子的确定性生成兼容路径。
- [ ] 目标环境接入对象存储、签名 URL、复制/保留、备份恢复和容量/SLO 验收。
