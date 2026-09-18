---
title: "Memory：维护共享知识"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

**Resources → Memory** 管理可绑定给 Managed Agent 的共享知识文档，适合产品术语、操作说明和稳定事实。它与 Chat 历史、Session 工作记忆及 Issue 评论不同。

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/memory.png" alt="Memory Store 与其中的记忆文件" />
</Frame>

先选择 Store，再查看其中的文件路径和内容。把已核对、可复用的结论放入 Memory；临时讨论和未经确认的推测不要直接作为共享知识。

## 建立知识库

点击 **New store**，填写名称和描述，使用 **Add memory** 添加路径和内容。例如 `product/glossary.md` 保存产品术语，正文包含定义、来源和更新时间。将 Store 关联给需要它的 Agent，并在资源详情查看消费者。

用新 Chat 提问一个只有该文档能回答的问题，要求 Agent 引用来源，确认它通过 memory 工具找到了正确内容。

## 绑定与首次验证

1. 在 Managed Agent 的 **Runtime → Session defaults → Default memory stores** 选择 Store 并保存，对应 `defaultMemoryStoreIds`。
2. 在 `product/glossary.md` 写入一条便于核对的演示事实，例如“本项目将 Lark 定义为周报归档任务”，然后新建 Chat，要求列出可用知识文档并解释 Lark。
3. 检查工具记录：`memory_store_list` 发现已挂载内容，`memory_store_read` 读取正文。核对回答与来源路径，避免把模型已有知识误当成绑定成功。

Session API 使用 `memoryStoreIds` 覆盖默认列表；省略时继承默认值，`[]` 表示该会话不挂载默认 Store。仅在 Instructions 中提到一个 Store 名称不会建立绑定。

共享文档按需实时读取，不是把正文固定复制进每个 Session。更新测试事实后要求再次调用读取工具，确认新内容可见；已有对话中曾引用的旧内容不会因此自动改写。资源绑定发生变化时用新 Session 验证。

## 读取方式

Managed Agent 按需要发现和读取已绑定文档；系统不会把整个 Store 自动塞进每次模型提示。执行期间共享知识是只读的，长期内容应在这里维护。Session 内临时推导出的信息不自动成为所有 Agent 的共享知识。

## 更新与移除

Edit 修改文档；Redact 用于移除需要脱敏的内容；Delete 删除条目。Archive Store 后，新 Session 不再挂载它；删除整个 Store 会删除其中的文档。先检查消费者，并按你的数据保留要求备份。

如果 Agent 未读取预期知识，检查 Store 绑定、是否归档、内容路径和工具能力，再用新会话验证。仅在指令中写出 Store 名称不会建立资源绑定。

下一步：[Managed Agent](/v2/zh/service/managed-agent) · [Vault](/v2/zh/service/vault)。

动手练习：[售前方案团队](/v2/zh/service/cases/presales-team)演示产品与交付知识绑定、来源引用、缺失信息处理和更新后再次读取。
