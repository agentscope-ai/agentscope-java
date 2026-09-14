---
title: "Memory：维护共享知识"
---

[English](/v2/en/service/memory)

**Resources → Memory** 管理可绑定给 Managed Agent 的共享知识文档，适合产品术语、操作说明和稳定事实。它与 Chat 历史、Session 工作记忆及 Issue 评论不同。

## 建立知识库

点击 **New store**，填写名称和描述，使用 **Add memory** 添加路径和内容。例如 `product/glossary.md` 保存产品术语，正文包含定义、来源和更新时间。将 Store 关联给需要它的 Agent，并在资源详情查看消费者。

用新 Chat 提问一个只有该文档能回答的问题，要求 Agent 引用来源，确认它通过 memory 工具找到了正确内容。

## 读取方式

Managed Agent 按需要发现和读取已绑定文档；系统不会把整个 Store 自动塞进每次模型提示。执行期间共享知识是只读的，长期内容应在这里维护。Session 内临时推导出的信息不自动成为所有 Agent 的共享知识。

## 更新与移除

Edit 修改文档；Redact 用于移除需要脱敏的内容；Delete 删除条目。Archive Store 后，新 Session 不再挂载它；删除整个 Store 会删除其中的文档。先检查消费者，并按你的数据保留要求备份。

如果 Agent 未读取预期知识，检查 Store 绑定、是否归档、内容路径和工具能力，再用新会话验证。仅在指令中写出 Store 名称不会建立资源绑定。

下一步：[Managed Agent](/v2/zh/service/managed-agent) · [Vault](/v2/zh/service/vault)。
