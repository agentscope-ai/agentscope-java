---
title: "Workspaces：共享指令与能力文件"
---

[English](/v2/en/service/workspaces)

**Resources → Workspaces** 保存可复用的 Agent 资料：`AGENTS.md`、技能、工具和子 Agent 定义。Workspace 是资源，不是账号的 Namespace，也不是一次执行的临时目录。

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

## 哪些内容应该放在这里

| 内容 | 用途 |
| --- | --- |
| AGENTS.md | 项目操作说明与共同约束 |
| Skills | 可复用任务步骤及辅助文件 |
| Tools / MCP 配置 | 声明外部能力连接 |
| Subagents | 专项委派定义 |

可长期共享的知识文档也可放入 [Memory](/v2/zh/service/memory)，密钥放入 [Vault](/v2/zh/service/vault)。工具连接中的凭据使用明确引用，避免提交明文。

## 与执行目录的关系

Managed Agent 通过所选 [Environment](/v2/zh/service/environments) 访问工作文件；Hosted Agent 由 Host 将可移植定义映射到 provider 支持的配置，并使用任务自己的工作目录。不同运行时支持的投影能力不同，应检查 Runtime 能力说明。

编辑前查看依赖此 Workspace 的 Agent；更新后用新任务验证。删除共享 Workspace 前先处理消费者引用。备份时同时保留数据库引用和 Workspace 存储。
