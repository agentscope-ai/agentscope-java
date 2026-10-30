---
title: "Knowledge 与检索工具适配"
---

`JevKnowledge` 实现当前仍存在但已废弃的 `Knowledge` 接口，用于已有检索实现的兼容接入。新应用仍可直接使用[JevRag](/v2/zh/jev/guides/rag-api)，不必依赖废弃接口。

## 场景：给已有知识库增加退款政策筛选

你的应用已经实现 `Knowledge`：查询“退款条件”时，召回退款政策、配送说明和内部补偿材料。希望保留现有检索后端，同时在返回给 Agent 之前排除无权访问的材料，再判断哪些授权文档与问题相关。

`JevKnowledge` 包装现有实例；`JevKnowledgeTool` 将结果作为 `search_knowledge` 工具返回。无需为了增加 JEV 重写文档入库和向量检索。

## 1. 包装现有 Knowledge

下面的 `existingKnowledge` 是应用已有实现，`client` 见[客户端接入](/v2/zh/jev/guides/client)。`canRead` 是应用提供的 `BiPredicate<RuntimeContext, Document>`，必须根据当前用户、租户和文档权限做确定性检查。

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import io.agentscope.extensions.judge.jev.integration.JevKnowledge;
import io.agentscope.extensions.judge.jev.integration.JevKnowledgeTool;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.time.Duration;

var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "refund-retrieval-v1",
    (context, record) -> System.out.println(record));
var selector = new JevCandidateSelector(client, options, 0.2, 0.8);
var knowledge = new JevKnowledge(existingKnowledge, selector, options, canRead, 20);
var retrieveConfig = RetrieveConfig.builder().limit(5).build();
```

20 是接管时的候选召回规模，5 是本次检索结果上限。SHADOW 保持原检索配置和 ACL 后排序，只观察建议；完成业务样本校准后，把同一份 `options` 改为 ENFORCE，让 selector 与适配器一起接管。两者模式必须一致。

在退款例子中，ACL 应先移除内部材料，JEV 再在可见政策和配送说明之间做相关性判断。阈值 0.2/0.8 为示例，不能保证特定文档一定被选中。

## 2. 注册工具或直接检索

在已有 `toolkit` 注册工具，随后将该 Toolkit 传给你的 Agent：

```java
var tool = new JevKnowledgeTool(knowledge, retrieveConfig);
toolkit.registerTool(tool);
```

工具名为 `search_knowledge`，RuntimeContext 由 Toolkit 注入，模型不能通过工具参数指定其他用户身份。返回 Passage 的 id 与 text，可继续接[最终草稿审核](/v2/zh/jev/guides/answer-refinement-api)，检查回答是否保留退款条件。

如果应用自己生成回答，也可直接检索。这里 `ctx` 是当前用户与会话的运行上下文：

```java
var documents = knowledge.retrieve(ctx, "退款需要什么条件？", retrieveConfig).block();
System.out.println("可提供给回答生成器的文档数：" + documents.size());
```

ENFORCE 下只有明确相关的授权文档进入结果。不确定或调用失败不能都当成“知识库没有答案”：明确无关返回空列表；全部不确定或判断出错返回异常，应用应显示检索暂不可用或转人工。

## 运行离线案例

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt)包含一个固定 Knowledge 后端，返回“未使用且七天内”的退款政策，并把相关证据提供给 Agent。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

案例检查第二轮模型确实收到工具返回的政策，并把无条件退款草稿修订为保留条件的回答。它验证工具接入和证据传递，判断均为预设，不代表真实检索召回率或模型准确率。

## 结果与使用边界

工具名为 `search_knowledge`，RuntimeContext 由 Toolkit 注入，身份不由模型参数提供。返回已授权 Passage 的 id、text；原后端仍负责租户检索边界，JEV 不提供权限。

处理顺序：原检索器召回候选 → ACL → JEV 独立相关性判断 → 按概率排序及数量限制。ENFORCE 候选数为 `max(candidateLimit, config.limit)`，candidateLimit 限制 1..1024；保留 config 其他参数。上游阈值可能影响召回，需要业务单独测量。

OFF/SHADOW 返回 ACL 后的原排序候选，SHADOW 仅记录建议；ENFORCE 只返回明确相关的文档，不确定项不当作证据。确定无关返回空列表；判断错误返回异常，不能把后端失败包装成“无证据”。全部候选均不确定时返回 inconclusive 异常，不冒充确定无关。OFF/SHADOW 使用原检索配置，不扩大召回。selector 与适配器运行模式必须一致。

返回原 Document 对象，保留 id、metadata、embedding 和原检索 score，不把 JEV 概率覆盖到向量分数。`addDocuments` 原样委托，不做记忆准入或写授权。

可以调用 `retrieve(ctx, query, config)`；通过 Knowledge 标准接口调用时，需在 Reactor Context 放入 `RuntimeContext.class`，缺失时拒绝，防止跨会话隐式复用身份。JEV 预算不包含原检索后端耗时，宿主应为后端另配超时；取消沿同一反应式链传播。

案例见[组合案例](/v2/zh/jev/guides/agent-integration-example)。
