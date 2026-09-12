---
title: "Vault：为工具提供凭据"
---

[English](/v2/en/service/vault)

**Resources → Vault** 保存 Agent 工具连接使用的凭据。Secret 写入后页面只展示类型、标签和目标等元数据，不重新展示明文。

## 配置一个连接

创建 Vault，点击 **Add credential**，选择类型并填写 Label、Target 和 Secret。将 Vault 绑定到使用该连接的 Agent，再配置相应 MCP 工具。先执行一次只读调用确认认证正常。

| 类型 | 使用方式 |
| --- | --- |
| Bearer / MCP OAuth | Target 匹配连接名或完整 endpoint URL，包含路径 |
| Environment variable | 只替换 MCP header、环境或 query 中明确引用的 `${VARIABLE}` |
| Generic secret | 只提供存储，不会自动注入任意工具 |

OAuth 内容需要 `access_token`，可按连接需要包含刷新信息。保存凭据本身不意味着外部服务已授予正确权限。

## 验证和轮换

Validate 检查凭据，Rotate 写入替代 secret。轮换前确认外部系统中的新凭据有效，随后验证实际工具调用。删除前查看消费者，避免同时中断多个 Agent。

不把 secret 写进 Instructions、AGENTS.md、聊天或公开示例。Vault 加密数据依赖部署的 master key；管理员备份必须同时保存数据库和原密钥，单独恢复数据库不足以恢复连接。

失败时检查 Target 是否完整匹配、变量是否明确引用、Vault 是否绑定以及外部权限是否有效。不要通过在聊天里直接粘贴 secret 来排障。

下一步：[工具配置](/v2/zh/service/agents) · [备份恢复](/v2/zh/service/operations)。
