---
title: 概览
en_link: /dev/en/integration/rag/overview
---

新应用请先阅读 [RAG 接入与迁移](/dev/zh/integration/rag/migration)，了解调用 Agent 前检索，以及由应用提供检索工具的两种接法，避免继续依赖已废弃的 RAG Builder 方法。

`io.agentscope.core.rag.Knowledge` 是接入外部知识库的旧版兼容接口。当前 `main` 中的已有接法仍可运行，但该接口和 Builder 接入方法已标记为待移除。`agentscope-extensions-*` 模块提供以下适配器：

| 扩展 | 类型 | 适合场景 |
| --- | --- | --- |
| [Simple](/dev/zh/integration/rag/simple) | 自建：embedding + 向量库 | 对接自家向量库（PgVector / Milvus / Qdrant / Elasticsearch / 内存） |
| [Bailian](/dev/zh/integration/rag/bailian) | 阿里云百炼知识库 | 用百炼托管的企业级知识库 |
| [Dify](/dev/zh/integration/rag/dify) | Dify 数据集 | 已经在 Dify 上维护知识库的场景 |
| [HayStack](/dev/zh/integration/rag/haystack) | 自托管 HayStack RAG | 已经基于 HayStack 搭建索引流水线 |
| [RAGFlow](/dev/zh/integration/rag/ragflow) | RAGFlow 服务 | 需要 OCR、知识图谱增强的复杂文档场景 |

## 旧版 Builder 接法

下面的示例用于维护兼容层上的已有应用，不建议作为新代码的起点。

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)        // 任选一种 Knowledge 实现
    .ragMode(RAGMode.AGENTIC)    // 或 GENERIC、NONE
    .build();
```

已有应用也可以显式注册旧检索工具。`KnowledgeRetrievalTools` 本身同样已废弃；新工具可以直接封装应用自己的检索服务，具体见迁移指南。

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerTool(tools);
```

## 选型建议

| 需求 | 建议 |
| --- | --- |
| 自己掌控全部链路（embedding + 向量库） | **Simple** |
| 阿里云生态、企业级托管 | **Bailian** |
| 团队已经在用 Dify 编排 | **Dify** |
| 需要复杂 ETL（PDF 表格、图片 OCR、知识图谱） | **RAGFlow** |
| 已基于 HayStack 落地 RAG 流水线 | **HayStack** |

> 除 Simple 外，其余实现都**只负责检索**（retrieve），文档导入/更新请走对应平台的控制台或服务侧 API；这样设计是为了让多个 Knowledge 在使用侧完全可替换。
