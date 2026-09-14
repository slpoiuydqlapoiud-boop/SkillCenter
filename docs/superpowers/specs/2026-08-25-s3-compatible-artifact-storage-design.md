# S3-compatible Skill 制品存储设计

## 目标

为 `ArtifactStorage` 增加可替换的 S3-compatible HTTP 适配器，使 Skill 制品可以从单机本地文件系统迁移到企业对象存储，同时保持内容寻址、不可变引用、SHA-256 完整性校验和 fail-closed 生命周期语义。

## 边界

- `skill-center.artifact-storage-backend=local` 继续作为开发默认值。
- `object-storage` 默认仍为 `contract` 模式；未显式配置 `http` 时只装配契约占位适配器，不回退本地。
- `object-storage/http` 使用标准 Java `HttpClient` 和 AWS Signature V4 风格签名，兼容 S3/OBS 等实现；不引入云厂商 SDK，避免把凭据或供应商模型耦合到领域端口。
- 适配器只保存 `sha256/<digest>.zip` 对象；调用方只持久化 `s3://` opaque reference，不解析 bucket、key 或 endpoint。
- 凭据只接受 `secret://...` 引用，默认从环境变量 resolver 读取；日志、错误和 readiness 不返回凭据、签名、endpoint、bucket 或原始供应商响应。
- 真实 S3/OBS 的网络、容量、复制、保留、备份/PITR 和 SLA 验收仍属于目标环境交付，不由本地测试替代。

## 数据流和一致性

1. 上传端先计算源文件 SHA-256 和大小。
2. 适配器对内容寻址 key 发起带 `If-None-Match: *` 的 PUT，并附带 SHA-256 metadata。
3. 已存在对象通过 HEAD 检查记录的 SHA-256 和大小；缺失或不匹配时返回稳定冲突/完整性错误，不覆盖对象。
4. Manifest、目录和下载通过 `inspect/open` 读取对象；GET 返回的字节再次计算 SHA-256，随后才交给 ZIP 读取或下载响应。
5. HTTP 非成功响应转换为不泄露正文的稳定 `ArtifactStorageUnavailableException`，404 转为 `ArtifactNotFoundException`。

## 就绪度

- `local` 返回 `DEGRADED/ARTIFACT_STORAGE_LOCAL_ONLY`。
- `object-storage/contract` 返回 `NOT_READY/ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED`。
- `object-storage/http` 只有 endpoint、bucket、region 和凭据引用完整时才报告 `DEGRADED/ARTIFACT_STORAGE_HTTP_CONFIGURED`；真实网络探测和生产证据仍由目标环境完成。

## 验证

- 单元测试覆盖 endpoint/凭据引用校验、canonical request/signature、PUT 幂等冲突、HEAD metadata、GET SHA-256/ZIP 完整性、404/5xx 稳定错误和 readiness。
- 使用本地 JDK HTTP stub 验证请求方法、路径、签名头和请求体，不连接真实云服务。
- 完成后运行 API 全量、Web 全量、生产构建、生命周期 verifier 和 `git diff --check`。
