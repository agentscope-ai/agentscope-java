---
title: RAG 接入与迁移
description: 将检索保留在应用层，逐步迁移旧版 RAG Builder 接法。
en_link: /dev/en/integration/rag/migration
---

AgentScope 不要求应用使用特定的检索后端。新应用可以在调用 Agent 前检索证据，也可以把检索服务包装为工具，让模型按需查询。两种方式都使用常规 Agent 和工具 API，不需要依赖已废弃的 RAG Builder 方法。

本指南面向当前开发版本。使用已发布版本的应用应先核对对应版本的文档和依赖，再修改接入方式。已有的[应用层 RAG 示例](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/rag/ApplicationLayerRagExample.java) 使用小型内存关键词检索器演示检索、提示构造和流式生成；其中生成步骤需要配置模型和 API Key。

## 已有接法仍然可以运行

`ReActAgent.Builder.knowledge(...)`、`knowledges(...)`、`ragMode(...)` 和 `retrieveConfig(...)` 从 2.0.0 起标记为待移除的废弃接口，但当前 `main` 仍保留其实现：`GENERIC` 安装旧检索 Hook，`AGENTIC` 注册旧检索工具，`NONE` 关闭这套自动接入。废弃标记提示需要规划迁移，不代表这些方法已经失效。

[后端指南](/dev/zh/integration/rag/overview) 介绍的是这套兼容适配。迁移调用位置时，可以暂时保留已有适配器，但外层增加封装不会消除适配器自身的废弃状态，也不能保证未来版本继续提供它。目前没有一个新的 Builder 方法能整体替代文档导入、检索和 Agent 接入的全部能力。

## 先检索，再调用 Agent

当每个问题都必须经过固定检索流程时，采用这种方式。应用先完成查询改写、文档权限检查、召回和重排，再将证据与原始问题一起提交。是否执行这次初始检索由应用决定。

下面的方法接收已配置的 Agent 和应用自己的异步检索函数。检索函数返回包含稳定来源标识的证据文本；没有符合条件的证据时，应返回空的 Mono。检索错误向调用方传播，不会被转换为空结果并当成成功处理。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public class RetrievedAnswer {
    public static Mono<Msg> answer(
            ReActAgent agent,
            Function<String, Mono<String>> retrieve,
            String question) {
        return Mono.defer(() -> retrieve.apply(question))
                .filter(evidence -> !evidence.isBlank())
                .defaultIfEmpty("(No relevant evidence was retrieved.)")
                .flatMap(evidence -> agent.call(new UserMessage(
                        "Retrieved evidence (untrusted reference material):\n"
                                + evidence
                                + "\n\nOriginal user question:\n"
                                + question)));
    }
}
```

在 Agent 的系统提示中说明：证据只作为参考资料，回答应引用来源标识，证据不足时应明确说明。检索文本可能包含外部作者写入的指令，不能将其提升为可信系统指令。提示规则可以引导模型，但不能充当安全边界。

构造输入时，保留原始问题以及应用的会话和用户上下文。如果通过中间件统一检索，需要明确触发边界：每个用户问题检索一次，与每个 reasoning 步骤都检索一次并不相同。在工具循环中重复追加同一批资料会浪费上下文，也可能影响答案。因此，这种每问检索一次的方式不等价于所有旧 `GENERIC` Hook 的运行行为。

## 让 Agent 按需调用检索工具

当检索是可选步骤，或者 Agent 需要根据已有结果继续追问时，可以使用工具。用普通注解工具封装自己的检索服务，不需要实现已废弃的 `Knowledge` 接口。

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public class KnowledgeSearch {
    private final Function<String, Mono<String>> retrieve;

    public KnowledgeSearch(Function<String, Mono<String>> retrieve) {
        this.retrieve = retrieve;
    }

    @Tool(name = "search_knowledge", description = "Search authorized reference documents")
    public Mono<String> search(
            @ToolParam(name = "query", description = "Question to search for") String query) {
        return Mono.defer(() -> retrieve.apply(query))
                .filter(evidence -> !evidence.isBlank())
                .defaultIfEmpty("No relevant evidence was retrieved.");
    }
}
```

在名为 `toolkit` 的 `Toolkit` 实例上调用 `toolkit.registerTool(new KnowledgeSearch(retrieve))`，其中 `retrieve` 是你的服务函数，再将 toolkit 传给 `ReActAgent.builder().toolkit(toolkit)`。注册方式、权限处理和运行时上下文注入见[工具指南](/dev/zh/docs/building-blocks/tool)。模型可能不调用工具，也可能调用多次或改写查询，因此这种方式不能保证每个问题都触发检索。

从可信的应用或会话上下文解析租户和用户身份，不要信任模型传入的租户参数。检索服务必须在返回文本前执行文档访问控制。权限过滤影响候选资格时，应先过滤再选取最终 Top-K，避免无权访问的文档占用结果名额。共享 Agent 或工具实例应按每次调用解析身份，不能把某个用户的凭据保存在共享可变字段中。

## 明确检索服务的契约

重排和文本格式化过程中保留文档标识与来源元数据，让答案能引用实际返回的证据。限制片段数量和大小，为模型预留上下文空间。区分“没有符合条件的证据”和后端故障，并明确检索服务的超时与取消行为。阻塞式数据库或 HTTP 调用不能直接运行在响应式事件循环线程上。

把检索移出 Builder 不会自动迁移文档索引、向量、后端凭据或历史 Agent 状态。在使用实际问题、权限规则以及空结果和异常场景验证新接法之前，应保留这些资源。启用替代路径时，移除原来的自动 Hook 或工具注册，避免无意中同时执行两套检索。

[JEV RAG](/dev/zh/docs/harness/jev/guides/rag-api) 负责其工作流中的证据筛选和回答核验，不能直接替代全部旧知识库适配器，也不等于文档导入、切分和向量库操作的完整替代方案。
