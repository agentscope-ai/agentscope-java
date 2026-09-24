---
title: "Knowledge 与检索工具适配"
---

# Knowledge 与检索工具适配

`JevKnowledge` 实现当前仍存在但已废弃的 `Knowledge` 接口，用于已有检索实现的兼容接入。新应用仍可直接使用[JevRag](rag-api.md)，不必新增对废弃接口的依赖。

```java
var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "retrieval-v1", (ctx, r) -> observe(r));
var selector = new JevCandidateSelector(client, options, 0.2, 0.8);
var knowledge = new JevKnowledge(existingKnowledge, selector, options,
    (ctx, doc) -> acl.canRead(ctx.getUserId(), doc.getId()), 20);
var tools = new JevKnowledgeTool(knowledge, RetrieveConfig.builder().limit(5).build());
toolkit.registerTool(tools);
```

工具名为 `search_knowledge`，RuntimeContext 由 Toolkit 注入，身份不由模型参数提供。返回已授权 Passage 的 id、text；原后端仍负责租户检索边界，JEV 不提供权限。

处理顺序：原检索器召回候选 → ACL → JEV 独立相关性判断 → 按概率排序及数量限制。ENFORCE 候选数为 `max(candidateLimit, config.limit)`，candidateLimit 限制 1..1024；保留 config 其他参数。上游阈值可能影响召回，需要业务单独测量。

OFF/SHADOW 返回 ACL 后的原排序候选，SHADOW 仅记录建议；ENFORCE 只返回明确相关的文档，不确定项不当作证据。确定无关返回空列表；判断错误返回异常，不能把后端失败包装成“无证据”。全部候选均不确定时返回 inconclusive 异常，不冒充确定无关。OFF/SHADOW 使用原检索配置，不扩大召回。selector 与适配器运行模式必须一致。

返回原 Document 对象，保留 id、metadata、embedding 和原检索 score，不把 JEV 概率覆盖到向量分数。`addDocuments` 原样委托，不做记忆准入或写授权。

可以调用 `retrieve(ctx, query, config)`；通过 Knowledge 标准接口调用时，需在 Reactor Context 放入 `RuntimeContext.class`，缺失时拒绝，防止跨会话隐式复用身份。JEV 预算不包含原检索后端耗时，宿主应为后端另配超时；取消沿同一反应式链传播。

案例见[组合案例](agent-integration-example.md)。源码：[JevKnowledge](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/integration/JevKnowledge.java)、[工具适配](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/integration/JevKnowledgeTool.java)。
