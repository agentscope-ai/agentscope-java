---
title: "上下文价值、压缩与记忆准入"
---

# 上下文价值、压缩与记忆准入

更新：本页保留调研与设计背景；当前已实现的API和限制见[增量实现使用指南](context-planner-api.md)，不要将旧基线描述作为最新行为。

## 当前行为

CompactionMiddleware在onReasoning触发压缩，调用MemoryFlushManager和ConversationCompactor；压缩会更新当前AgentState的工作上下文，并重建后续ReasoningInput。ToolResultEvictionMiddleware处理大工具结果卸载；MemoryFlushMiddleware和显式MemorySaveTool承担不同写入路径。

这些能力已存在，但还没有Jev价值筛选。不能在外层增加“删消息”就宣称与内置压缩安全组合。需要核验Middleware顺序、记忆刷写时机和事件恢复语义。

## 两条独立策略（拟议）

- **工作上下文筛选**：当前任务还需保留的证据、是否可从持久化引用恢复、是否已被新结果替代。
- **长期记忆准入**：事实是否稳定、有来源、属于正确用户/Agent范围，是否与现有事实冲突。

当前有用不等于长期有用。用户明确删除或更正的偏好不能被旧上下文重新写回。长期记忆写入失败不能以“已记住”回复。

## 与现有压缩组合

1. 从当前调用状态创建不可变候选快照，记录message/toolCall和证据版本。
2. 规则固定保留目标、用户约束、有效授权条件、未完成任务，以及工具调用/返回配对关系。
3. Jev仅给出候选的KEEP/OFFLOAD/REVIEW建议；需要摘要仍由原摘要模型生成。
4. 首先只影子记录；受控模式优先卸载可恢复的工具结果，不直接丢弃唯一事实。
5. 变更前保存来源引用与决策版本，验证新上下文可解析、调用配对不破坏、关键约束仍存在，再原子应用到当前Session。

`RuntimeContext.resolveAgentState(ctx, agent)`是当前调用状态的读取入口；不把会话可变数据存到共享Agent字段。RuntimeContext临时属性不是恢复日志；需持久化的decision/evidence引用交给存储层。

## 记忆链路需逐一覆盖

显式MemorySaveTool、任务结束的flush、压缩前flush、后台维护均可能产生写入，单一onActing包装不能覆盖全部。建议在共享记忆准入服务归约，然后让各写入路径调用；应用身份与存储隔离仍由确定性机制负责。

验收使用跨轮和跨Session任务：保留授权条件、更新旧事实、冲突记忆、误删恢复、租户隔离、取消与并发。主要指标为后续成功率、关键约束丢失率与错误记忆写入，压缩率为辅助指标。参考fast-jev-compaction的保留/截短/移除思想，不直接移植其宿主插件机制。[来源](../references.md)
