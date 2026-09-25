# Memory & Vault

[← Hands / Worker](08-hands-worker.md) · [回目录](README.md) · [下一页：Deployments →](10-deployments.md)

---

两个可选挂载：跨会话文档库（Memory Store）与加密凭证（Vault）。在 **创建 Session** 时通过 `memoryStoreIds` / `vaultIds` 绑定。

## Memory Store

- API：`/api/memory-stores`（CRUD、路径文档 `…/memories/{*path}`、`…/memories/versions/{*path}`、shares）。
- 运行时挂到 Harness 文件系统路由 `memory-stores/{storeName}/`，读写直达存储。
- 与 Harness 原生 `MEMORY.md` / `memory/` LTM **正交**，不要混为一谈。

创建 Session 示例片段：

```json
{
  "agent": "agt_xxx",
  "environmentId": "env_xxx",
  "memoryStoreIds": ["mem_xxx"]
}
```

缺口（无 archive、无 version get/redact 等）见 [Limitations](12-limitations.md)。

## Vault

- API：`/api/vaults`（CRUD、credentials 创建/列表/删除、shares）。
- 凭证 AES-GCM 加密；生产务必设置 `BUILDER_VAULT_MASTER_KEY`。
- Session 构建时注入 MCP / 工具侧环境变量占位（如 `${ENV}`），避免明文进事件日志。

```json
{
  "agent": "agt_xxx",
  "environmentId": "env_xxx",
  "vaultIds": ["vault_xxx"]
}
```

当前无 vault update/archive、credential get/update、OAuth validate 等；集成时以现有 create/list/delete 为准。
