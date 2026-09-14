# 审核、发布与分发准入统一设计

## 目标

把审核通过、质量门禁、发布批次和分发准入连接为一条可审计的 Skill 生命周期主链路，同时兼容当前已经发布的历史版本。

本阶段不改变 `SkillVersion.status=published` 的历史语义，也不伪造真实 Runtime/CD 部署。`ReleaseRecord` 继续记录环境晋级事实；新增的准入策略负责决定某个版本是否可以进入安装和制品下载流程。

## 当前断点

- `ReviewService.approve` 在质量门禁通过后直接把版本写成 `published`。
- `DistributionService` 与 `ArtifactDownloadService` 当前主要依据 `published/deprecated` 状态判断可分发性。
- `ReleaseRecord` 已能记录 STAGING/PRODUCTION 审批、晋级和回滚，但分发链路尚未读取它。
- 直接把所有历史版本切换到强制生产晋级会破坏已有种子数据和本地兼容性，因此必须有显式迁移模式。

## 设计

### 1. 发布准入模式

新增 `ReleaseAdmissionMode`：

- `LEGACY_COMPATIBLE`：默认迁移模式。没有 PRODUCTION 发布批次的历史版本保持现有分发行为；一旦某版本存在 PRODUCTION 批次，则必须存在同一 Skill/版本/制品哈希的 `PROMOTED` 批次。
- `CONTROLLED`：所有可分发版本都必须有匹配的 PRODUCTION `PROMOTED` 批次；没有发布批次、批次失败、审批中或制品哈希不一致均 fail-closed。

配置项：`skill-center.release-admission-mode`，默认 `LEGACY_COMPATIBLE`。企业部署完成历史版本基线登记后切换为 `CONTROLLED`。

### 2. 统一准入服务

新增 `ReleaseAdmissionService`，只读查询 `GovernanceStore` 和 `ReleaseRecordStore`，输出受控的 `ReleaseAdmissionDecision`：

- `allowed`、`mode`、`reasonCode`、`releaseId`、`legacyCompatible`；
- 只允许稳定原因码，不返回外部异常、凭据或业务正文。

`DistributionService.createManifest` 和 `ArtifactDownloadService.download` 在返回安装授权或读取 ZIP 前调用同一准入服务。拒绝使用稳定错误码 `RELEASE_ADMISSION_REQUIRED`、`RELEASE_NOT_PROMOTED` 或 `RELEASE_ARTIFACT_MISMATCH`。

### 3. 审核完成后的发布登记

审核最终批准并写入 `published` 后，`ReviewService` 使用同一次质量门禁快照向 `ReleaseService` 登记一个 STAGING `REQUESTED` 批次：

- 幂等键为 `review:{reviewId}:staging`；
- 请求上下文使用审核通过的 Skill、版本和制品哈希；
- 登记失败写入 `RELEASE_STAGING_ENROLLMENT_FAILED` 审计事件，不回滚已经提交的审核结果；
- `CONTROLLED` 模式下没有 PRODUCTION 晋级记录时，分发准入仍然 fail-closed；`LEGACY_COMPATIBLE` 模式下历史兼容行为保持不变。

这样审核不再是生命周期终点，而是自动进入可追踪的发布队列；STAGING/PRODUCTION 的人工审批、晋级和回滚仍由现有 Release Control Plane 完成。

### 4. 安全和兼容边界

- 只比较 Skill ID、版本、SHA-256、环境和发布状态。
- 不保存或返回 Prompt、输入输出、Trace 正文、工具参数、Token、密码或 Provider 异常正文。
- `withdrawn` 仍由现有生命周期策略拒绝；准入策略不能恢复已撤回版本。
- 不自动回滚、不自动提升环境、不调用真实外部部署系统。

## 可验证结果

1. 审核通过后能看到对应 STAGING 发布批次，重复审核回调不会产生第二个批次。
2. 没有 PRODUCTION 批次时，迁移模式行为与历史分发兼容。
3. 一旦存在 PRODUCTION 批次，未晋级、失败、拒绝或哈希不匹配的版本不能创建安装授权，也不能下载制品。
4. PRODUCTION `PROMOTED` 且哈希匹配时，安装和下载均允许。
5. `CONTROLLED` 模式下无发布记录的历史版本明确返回 `RELEASE_ADMISSION_REQUIRED`。
6. Web/API 全量回归、敏感字段扫描和文档中的迁移边界均通过。
