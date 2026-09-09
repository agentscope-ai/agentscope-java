# Managed Agent 资源与 MCP 接入改造记录

本轮接续 [整体审查](managed-agent-design-review-2026-09-08.md)，实现重点是把控制台资源接入托管 Harness 的运行链路，并修复版本与权限边界。代码位于主目录 `/Users/ken/agentscope-2/agentscope-java` 的 `agentscope-service-v5` 分支，没有创建独立 worktree。工作区中其他任务的改动保持原状；本记录只描述本轮 Managed Agent 改造。

这是核心实现与本地验证记录，不代表已完成线上部署、真实 MCP 服务商联调、E2B 或跨节点故障验收。

## 已实现的契约

| 范围 | 本轮改造 | 生效边界 |
| --- | --- | --- |
| MCP 定义 | Workspace 与 Agent Tools 页面支持连接的增删改、传输、required、默认启用、单工具启用与审批策略 | `mcpServers` 定义连接，`mcp_toolset` 定义该连接的工具策略，沿用已有 Agent/Workspace 版本体系 |
| MCP 运行 | 编译 toolset、工具发现后筛选、`server__tool` 命名空间、调用时保留远端原名 | 避免不同 MCP server 的同名工具覆盖；禁用项优先；默认禁用且没有显式启用项时不会误注册全部工具 |
| MCP 审批 | 未配置的 MCP 调用默认 ask，支持服务器默认及单工具 allow/ask/deny | Managed 服务层统一审批；子 Agent 审批关联根 Session，不丢入不可操作的子会话 |
| MCP 失败 | required 连接失败中止本轮，optional 失败产生结构化 Session 事件并允许其他能力继续 | 下一轮重新尝试可选失败连接；事件不回显底层服务商响应或凭证 |
| 连接释放 | Harness 保留自己创建的 MCP 客户端所有权，关闭时释放；缓存淘汰、删除会话和服务关闭清理实例 | Toolkit 的复制不转移连接所有权，避免子副本关闭父连接 |
| Vault | 托管运行消费 CP resolve 的凭证；按连接名或完整 URL 注入 bearer；显式变量替换 | 不再从旧 JPA Vault 读取托管凭证；不把全部秘密散发到每个 MCP env，也不使用宿主进程环境兜底 |
| OAuth | CP 解析 token expiry，按 credential 行锁串行 refresh，并加密更新 access/refresh token 与 revision | Brain 只获得 access token；刷新发生在 Session resolve，缓存按 revision 在下一轮重建 |
| Memory | Session 授权的 CP 文档访问适配器、实时文件系统 route、四个直接 Memory 工具 | 每次访问重新验证绑定及所有者；直接工具在 self_hosted 下也可用 |
| Memory 写入 | CP 文档头与历史版本原子写入、expectedVersion 乐观并发检查 | 新建使用 expectedVersion=0，编辑携带已读取版本；冲突返回 409；只读挂载禁止写、删、移动 |
| Workspace | Agent version 捕获 definitionFiles/workspaceVersion；Session 私有目录物化和删除同步 | 新版本可固定文件；移除定义文件保留会话产物；校验目录逃逸、符号链接及保留 manifest 路径 |
| Skills | Brain 从 Session 定义命名空间加载；Worker 从同一版本文件及显式技能列表打包 | 删除或未启用技能不再通过默认目录重新出现；保留声明的外部仓库和相同过滤规则 |
| Subagents | 继承工具策略、技能过滤、PermissionContext、sandbox/remote spec 及 Memory routes | 显式子工具列表限制最终工具集，不能通过新建文件工具或子 Agent 放开父级禁用能力 |
| Environment | 类型白名单、未知类型报错、remote 存储依赖缺失时拒绝构建、解析时重新校验绑定 | 不再把未知/无依赖 remote 环境静默解释为 local；stdio MCP 仅允许显式 local 环境 |
| 控制台接入 | self_hosted 创建后展示一次性 Worker key；Vault 提供 bearer/OAuth/显式变量类型指引 | 生产 MCP 使用 HTTP/SSE；self_hosted Worker 不承担 Brain 的 stdio 命令转发 |

## MCP 与 Vault 示例

以下为 Agent 定义中的相关字段，也适用于 Workspace Tools。默认禁用所有 MCP 工具，仅开启 `search`，调用前由 Managed Session 审批。

```json
{
  "mcpServers": [
    {
      "name": "crm",
      "type": "url",
      "transport": "http",
      "url": "https://crm.example/mcp",
      "timeout": "PT30S",
      "initializationTimeout": "PT30S",
      "required": true
    }
  ],
  "tools": [
    {
      "type": "mcp_toolset",
      "mcpServerName": "crm",
      "defaultConfig": {
        "enabled": false,
        "permissionPolicy": { "type": "always_ask" }
      },
      "configs": [
        { "name": "search", "enabled": true }
      ]
    }
  ]
}
```

模型可见的名字是 `crm__search`，MCP `tools/call` 发送的仍是 `search`。连接名最长 64 字符，允许字母、数字、下划线和短横线，不允许双下划线。工具配置使用远端原始工具名。

Vault 凭证保存为 `type=static_bearer`，`target=crm` 或 `target=https://crm.example/mcp`，创建 Session 时通过 `vaultIds` 绑定。URL 匹配包含 path/query；主机大小写、默认端口与末尾斜杠被规范化。不同 path、不同 query 不共享凭证。多个匹配凭证属于配置冲突，不选择第一个秘密。

非 bearer 的 header 可以写成 `{"X-Api-Key":"${CRM_TOKEN}"}`，在 Vault 中配置 `type=environment_variable,target=CRM_TOKEN`。只有显式引用的位置得到替换。普通 `api_key` 类型仍可保存，但没有自动 MCP 注入语义。

OAuth 的 secret 是 JSON，例如：

```json
{
  "access_token": "<access-token>",
  "expires_at": "2026-09-08T10:00:00Z",
  "refresh": {
    "token_endpoint": "https://identity.example/oauth/token",
    "client_id": "<client-id>",
    "refresh_token": "<refresh-token>",
    "token_endpoint_auth": {
      "type": "client_secret_basic",
      "client_secret": "<client-secret>"
    }
  }
}
```

刷新支持 `none`、`client_secret_basic` 和 `client_secret_post`，要求 HTTPS token endpoint，禁止自动跟随重定向，使用有界超时，并保存服务商返回的新 refresh token。缺少 expiry 的有效 access token 不会主动刷新。失效且无法刷新的凭证要求修复或重新授权。

## Memory 使用

Session 的 `memoryStoreIds` 是授权范围；Environment 的 `config.memoryAccess` 可按 store ID 配置 `read_only` / `read_write`。托管 Brain 的直接工具为 `memory_store_list`、`memory_store_read`、`memory_store_write`、`memory_store_edit`。当内置 toolset 默认禁用时，需要显式启用这些工具。

本地/支持 route 的文件工具还可访问 `memory-stores/<name>`。这些是实时远程文件系统路由，并非宿主 shell 或外部 Worker 磁盘上的真实目录；shell/Worker 应通过直接 Memory 工具访问。Native Harness 的 `MEMORY.md` 与 Memory Store 仍是两种记忆机制。

## 兼容与发布

- 已有 Agent 版本没有 definitionFiles 时，保留旧文件解析兼容路径。升级后应重新发布 Workspace/Agent，才有新版本文件固定保证；不能为旧版本补造历史文件。
- 旧的裸 `mcpServers` 仍可解析，但 MCP 默认审批改为 ask，managed 连接默认 required。希望故障可降级的连接应显式设置 `required=false`。
- 旧的 enableTools/disableTools 仍参与筛选，不应与 toolset 的相反策略混用。
- 子 Agent 的显式工具白名单现在约束最终工具集。原先依赖未声明的 `read_file` 或 `memory_search` 自动出现的声明，需要补上这些工具名。
- 本轮增加 `vault_credentials.revision` 的幂等数据库迁移。需同时部署控制面与数据面及新前端；旧数据面不会消费新资源契约。
- 构建不会自动重启用户运行的服务。本文末尾验证结果与线上生效状态分开记录。

## 尚未完成的生产能力

1. 单轮长任务中的 token 热更新。当前静态客户端不会在本轮内替换过期 token；下一轮解析生效。OAuth 浏览器授权、回调、断开/重新授权和 Agent Vault 自动关联已在后续实现，操作说明见 [MCP OAuth 账号连接](mcp-oauth-account-connection.md)；自动 metadata 发现及客户端注册仍未实现。
2. 独立可复用 Connector 资源、共享权限与版本、连接测试/实时工具发现页、持续健康检查、完整审计与配额。当前实现是 Agent/Workspace 中的 MCP connection 契约，不是独立 Connector registry。
3. MCP 私网接入/代理、组织级 egress 控制，以及凭证只驻留代理的更强隔离。当前 Brain 必须能访问 MCP endpoint，也会持有短期 access token。
4. Workspace 多文件原子发布及跨 Workspace/Agent 事务、自动重试与失败可见性；关联 Agent 的独立 system 覆盖与 AGENTS.md 更新还需要明确的来源模型。文件快照已固定，但不是完整发布流水线。
5. 外部 Git/文件系统技能仓库的不可变 commit/content pin。当前固定的是 Workspace definitionFiles 和技能选择，外部仓库内容仍遵守其原有读取语义。
6. Memory 的跨文档事务、语义检索、Redact 全链路与并发删除语义；不会把本轮单文档 CAS 描述为覆盖所有 Memory 操作。
7. E2B 的 packages/network 配置落地、Worker 生命周期管理、真实沙箱/Worker 的端到端验收，以及更多 Harness 参数的稳定产品 schema。

## 验证记录

最终专项验证：13 个 Java 测试类、81 个测试通过，相关 reactor 的 `verify` 成功，Spotless 检查通过，生成数据面可执行 JAR。命令如下：

```bash
mvn -pl agentscope-service/service-dataplane -am verify \
  -Dmaven.javadoc.skip=true \
  -Dtest=McpNamespacedToolTest,ManagedToolSelectionTest,McpConnectionLifecycleTest,ManagedSubagentBoundaryTest,HarnessAgentTest,ManagedMcpContractTest,ManagedVaultIsolationTest,ManagedDefinitionMaterializerTest,ManagedMemoryAccessTest,ManagedSkillsBundleTest,EnvironmentSpecFactoryE2bTest,HarnessAgentBuildServiceCacheKeyTest,ToolConfirmationMiddlewareTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- Go：`internal/product`、`internal/httpapi` 测试通过，其中资源授权、Memory 版本冲突、文件快照及 Vault revision 测试使用独立 PostgreSQL 17；OAuth 使用本地模拟 TLS 服务。`go build ./cmd/aistiod` 通过。
- 前端：`npm run build` 通过；Playwright `e2e/managed-mcp.e2e.ts` 通过，覆盖 Workspace 中新增连接、工具策略保存、刷新回显和删除关联。已检查 页面截图（历史本地验收记录，保存在发布前备份中）。
- 格式：本轮相关文件 `git diff --check` 无错误。
- 测试数据库容器已停止并自动删除，没有使用生产凭证。浏览器使用独立 API fixture，没有写用户已有资源。

**完整验证尚不能标为通过。** 较大范围 Java 验证曾在 Javadoc 的 stale-data/options 文件生成阶段失败，因此专项打包跳过 Javadoc。更新权限契约后，Core/Harness 的其余已执行测试通过，但 `WaitAsyncResultsToolTest.timeoutClampedToMax` 的计时断言一次通过、另一次实际等待约 303 秒而失败；扩大测试选择后，`GracefulShutdownTest.StateAndConfigTests.defaultConfig` 又出现共享 shutdown 配置未恢复为默认值的失败。这些既有测试的失败没有被删除，也没有通过放宽断言隐藏。专项成功不等于整个多模块全量测试全绿。

精简验证日志见 测试记录目录（历史本地验收记录，保存在发布前备份中）。当前改动在主目录工作区，未自动提交，未重启或部署现有服务。
