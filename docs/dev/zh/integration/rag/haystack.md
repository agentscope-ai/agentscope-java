---
title: HayStack
en_link: /dev/en/integration/rag/haystack
---

`agentscope-extensions-rag-haystack` 把 [HayStack](https://haystack.deepset.ai/) RAG 服务接进 AgentScope。文档管理和索引由 HayStack 那边负责，AgentScope 只负责调用其检索接口。

## 何时使用

- 已经在 HayStack 上落地 RAG（包括索引流水线、ChromaDB、Reranker 等）。
- 想要直接复用 HayStack 的端到端检索能力。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-haystack</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.rag.integration.haystack.HayStackConfig;
import io.agentscope.core.rag.integration.haystack.HayStackKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

HayStackConfig config = HayStackConfig.builder()
    .baseUrl("http://localhost:8080")  // 你的 HayStack 服务
    .topK(10)
    .build();

HayStackKnowledge knowledge = HayStackKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "What is AI?",
    RetrieveConfig.builder().limit(5).build()
).block();
```

## 与 Agent 集成

```java
ReActAgent agent = ReActAgent.builder()
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

## 文档不通过本插件管理

`addDocuments(...)` 抛 `UnsupportedOperationException`。要新增/更新文档，请：

1. 准备好原始数据放入 HayStack 流水线的 source 目录。
2. 触发 / 重新执行 HayStack 的索引流水线。
3. 索引完成后，本插件即可检索到新文档。

这种"管控分离"避免了双侧索引状态不一致。

## 关键参数

| 字段 | 说明 |
| --- | --- |
| `baseUrl` | HayStack 服务地址（必填） |
| `topK` | 默认返回的最大条数 |
| `filterPolicy` | 过滤策略（见 `FilterPolicy`） |

`HayStackConfig` 还提供超时、自定义 header、API Key 等扩展项，按 HayStack 部署侧的鉴权方案配置即可。

`RetrieveConfig.limit` 不仅会作为 `top_k` 传给服务端，还会在结果转换和分数过滤之后限制返回数量。即使自定义流水线或句子窗口检索器扩展出更多文档，应用拿到的上下文也不会超过此限制。适配器保留服务端的结果顺序；没有分数的窗口文档仍不参与分数过滤，但同样占用返回数量。
