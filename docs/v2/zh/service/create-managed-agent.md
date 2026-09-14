---
title: "Managed Agent：创建与测试"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

在控制台创建由 Service 运行的 Agent，然后用一个小请求验证。管理员应已完成[本地安装](/v2/zh/service/quickstart)或[生产安装](/v2/zh/service/kubernetes)，并配置模型与可用 Environment。

## 创建资料助手

1. 打开 **DESIGN → Agents → New agent**，Name 填“资料助手”。
2. Runtime 明确选择 **AgentScope Managed**，Model 留空使用部署默认模型。
3. 填写下面的 Instructions，在 Advanced settings 选择可用 Environment。
4. 点击 **Create & open agent**，检查保存后的职责和运行配置。

```text
根据提供的材料整理任务、负责人、期限和待确认事项。
只使用有依据的信息，不补造时间、来源或结论。
```

<Frame caption="Agent 目录示例，使用固定演示数据。">
  <img src="/imgs/service/agents.png" alt="从 Agent 目录创建并查看 Managed Agent" />
</Frame>

## 用 Chat 验证

在 **WORK → Chat → New chat** 选择“资料助手”，发送“小李周五完成安装说明，下周一评审，时间待确认。请整理待办。”检查回复保留了待确认项，再追问“还缺少什么信息？”。刷新后检查历史保留。

模型无响应时先检查部署凭据；文件或 Shell 工具失败时检查 Environment。首次先完成文本请求，再添加工具与知识。

## 分派实际工作

通过[控制台 Issue](/v2/zh/service/issues)安排带验收标准的任务，或[发布 Endpoint](/v2/zh/service/endpoints)供应用调用。模型、工具或资源调整后，用新 Chat 或新任务验证。

详细参数、能力和运行原理见参考手册中的[Managed Agent](/v2/zh/service/managed-agent)。[Workspace](/v2/zh/service/workspaces)、[Environment](/v2/zh/service/environments)、[Memory](/v2/zh/service/memory) 和 [Vault](/v2/zh/service/vault) 的绑定方法也在该参考分类中。

完成创建后，用[售前方案案例](/v2/zh/service/cases/presales-team)验证知识绑定、角色分工和文件交付。案例提供完整客户需求与知识资料。
