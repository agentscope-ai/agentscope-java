---
title: "Workspaces：共享指令与能力文件"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

**Resources → Workspaces** 保存可复用的 Agent 资料：`AGENTS.md`、技能、工具和子 Agent 定义。Workspace 是资源，不是账号的 Namespace，也不是一次执行的临时目录。

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/workspaces.png" alt="共享 Workspace 列表" />
</Frame>

从列表打开 Workspace，检查共享说明、技能和工具；使用 **New workspace** 新建资源。将 Workspace 关联到 Agent 后，还需要按该 Agent 的工作方式验证资源是否生效。

## 创建并关联

点击 **New workspace**，填写容易识别的名称，例如“报告工作区”。在详情中维护操作说明和能力文件，然后到 Agent 的 Workspace 页面建立关联。多个 Agent 可以复用同一个 Workspace。

先用简短 `AGENTS.md` 说明资料位置、输出约定和任务边界，再逐项添加能力。示例：

```markdown
# 报告约定

先阅读 inputs 中的任务资料。
事实必须能追溯到来源；推测单独标记。
最终报告写入 outputs，并在回复中给出文件位置。
```

示例中的目录需要由你实际准备，写进指令不会自动创建文件。用一个新任务检查 Agent 看到的文件和路径。

## Managed Agent 的版本绑定

1. 在 **DESIGN → Agents** 打开 Managed Agent，在设置中选择 Workspace。此关联对应 `workspaceId`；改变关联会发布并绑定所选 Workspace 草稿。
2. 需要选择已有发布版本或调整继承时，进入 **Definition → Workspace**。`workspaceBinding` 保存版本选择、覆盖项和附加指令。
3. 保存后创建一个新 Chat，要求 Agent 遵守一条可检查的约定，例如输出必须包含“来源”和“待确认事项”，再验证所选 Skill 或工具。

修改 Workspace 草稿与更新 Agent 所绑定的发布版本是两个步骤。运行上下文使用解析后的定义快照，不能假定修改草稿会立即改变已有 Session。多个 Agent 复用 Workspace 时，分别确认其绑定版本。

## 哪些内容应该放在这里

| 内容 | 用途 |
| --- | --- |
| AGENTS.md | 项目操作说明与共同约束 |
| Skills | 可复用任务步骤及辅助文件 |
| Tools / MCP 配置 | 声明外部能力连接 |
| Subagents | 专项委派定义 |

可长期共享的知识文档也可放入 [Memory](/v2/zh/service/memory)，密钥放入 [Vault](/v2/zh/service/vault)。工具连接中的凭据使用明确引用，避免提交明文。

## 与执行目录的关系

Managed Agent 通过所选 [Environment](/v2/zh/service/environments) 访问输入、临时文件与输出。Workspace 提供能力定义，Environment 提供实际文件与 Shell 执行位置；创建 Workspace 不会为 Agent 自动启动 Worker 或安装程序。先绑定定义，再在真实 Environment 中验证文件路径和依赖。

编辑前查看依赖此 Workspace 的 Agent；更新后用新任务验证。删除共享 Workspace 前先处理消费者引用。备份时同时保留数据库引用和 Workspace 存储。
