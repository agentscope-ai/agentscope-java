# API 与事件版本政策

Issue-first API 在首次正式发布前不承诺旧开发版本兼容。正式发布后：

- REST `/api/v1` 只做向后兼容字段扩展；删除/重命名或状态语义变化进入新的主版本；
- 客户端必须忽略未知 response 字段，服务端拒绝未知 enum/非法状态迁移；
- domain event 名含 `.v1`，envelope 同时携带 `schemaVersion`；破坏性 payload 变化使用新事件版本；
- ASDP proto 只追加 field number，禁止复用已发布编号；
- task token scope、tenant/namespace 和 actor 归因属于安全契约，不得宽松降级；
- migration 必须支持已发布版本的滚动部署，正式发布后不得依赖清库升级；
- 弃用至少跨一个 minor 发布并在文档、指标和客户端中提示。
