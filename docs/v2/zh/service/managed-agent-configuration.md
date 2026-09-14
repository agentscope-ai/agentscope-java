---
title: "Managed 参数与模型配置"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Managed Agent 的配置分为 Agent 定义、会话资源和 Dataplane 部署配置。先完成[创建与首次对话](/v2/zh/service/managed-agent)，再按本页调整参数。

## Agent 定义参数

下表使用 API 字段名。控制台在 Behavior、Workspace 和 Runtime/资源设置中编辑这些字段；创建页的 Advanced settings 可以选择环境。

| 字段 | 用途 | 配置建议 |
| --- | --- | --- |
| `name` / `description` | 显示名称与职责说明 | 写清输入、输出和适用任务 |
| `system` | Instructions，加入 Agent 指令 | 明确工作边界和验收标准；不保存 secret |
| `model` | 模型注册名或 `provider:model` 标识 | 留空使用 Dataplane 默认模型 |
| `maxIters` | 单次 Agent 推理/工具迭代上限 | 当前控制台范围 1–64，未配置时表单显示 12；不是 token 预算 |
| `workspaceId` | 关联共享能力定义 | 选择已准备的 Workspace |
| `workspaceBinding` | 发布版本、覆盖项和附加指令 | 用 Workspace 页面选择版本与覆盖范围 |
| `defaultEnvironmentId` | 新会话默认执行环境 | 先创建并验证 Environment |
| `defaultMemoryStoreIds` | 新会话默认知识 Store | 使用 Store ID，不是显示名称 |
| `defaultVaultIds` | 新会话默认凭据集合 | 只绑定该 Agent 需要的 Vault |
| `tools` / `mcpServers` / `skills` | 工具权限、MCP 连接和技能选择 | 逐项增加并验证 |

保存时保留版本检查；配置冲突后重新加载再修改。Agent key 是目录中的稳定身份，不通过改显示名称来迁移身份。

## 默认模型与显式模型

标准 Dataplane 配置包含 DashScope 模型扩展。管理员在 **Dataplane 进程/容器** 中提供 `DASHSCOPE_API_KEY`；`BUILDER_MODEL_NAME` 选择默认模型，部署配置的默认值是 `qwen-max`。修改部署环境变量后需要重启相应服务。

当 Agent 的 Model 留空时使用这个默认 Model。显式填写 `dashscope:qwen-max` 时，通过模型注册表加载 DashScope provider，并读取它的凭据。不要把 Hosted provider 的账号配置当作 Managed 模型凭据。

```json
{
  "model": "dashscope:qwen-max",
  "system": "Read the supplied sources. Cite evidence and list open questions.",
  "maxIters": 12
}
```

这是定义字段片段，不是完整创建请求。扩展模型的条件见[支持的能力与接入类型](/v2/zh/service/managed-agent-capabilities)。仅把 Model 改成另一个厂商的名称，不能给部署安装缺失的扩展或配置凭据。

## 会话资源如何选择

普通 Chat 和 Issue 使用 Agent 的资源设置。通过 Managed Session API 创建会话时，也可以指定 `environmentId`、`memoryStoreIds` 和 `vaultIds`。省略资源列表与显式传入空列表的含义不同：省略使用默认绑定，空列表表示不挂载该类默认资源。

```json
{
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": ["YOUR_VAULT_ID"]
}
```

此片段应放入所用 Session API 的请求中，并补全该接口的 Agent 等必填参数。资源必须在当前身份可用的范围内。对话、知识与凭据都在建立运行上下文时解析；保存定义之后用新 Chat 或 Issue 检查结果。

## 一次调整一个层次

1. Model 留空完成第一轮问答，验证部署默认模型。
2. 调整 Instructions 和 `maxIters`，验证职责与迭代边界。
3. 绑定 [Workspace](/v2/zh/service/workspaces) 和 [Environment](/v2/zh/service/environments)，验证文件工具。
4. 再绑定 [Memory](/v2/zh/service/memory) 与 [Vault](/v2/zh/service/vault)，分别验证知识读取和工具认证。

遇到 model cannot resolve 一类错误，检查 Model 标识和部署扩展；401/403 检查相应 provider 的认证；等待 Worker 应检查 Environment，不要通过增加迭代次数处理连接故障。
