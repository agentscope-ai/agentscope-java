---
title: RAG integration and migration
description: Keep retrieval in your application while migrating from the legacy RAG Builder API.
zh_link: /dev/zh/integration/rag/migration
---

AgentScope does not require a particular retrieval backend. New applications can retrieve evidence before calling an agent, or expose their retrieval service as a tool so the model can request evidence when needed. Both patterns use the normal agent and tool APIs without the deprecated RAG Builder methods.

This guide describes the current development APIs. For an application pinned to a released version, check that version's documentation and dependencies before changing its integration. The [application-layer RAG example](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/rag/ApplicationLayerRagExample.java) demonstrates retrieval, prompt construction and streaming with a small in-memory keyword retriever; its generation step requires a configured model and API key.

## Existing integrations still work

`ReActAgent.Builder.knowledge(...)`, `knowledges(...)`, `ragMode(...)` and `retrieveConfig(...)` are deprecated for removal since 2.0.0. They remain implemented on current `main`: `GENERIC` installs the legacy retrieval hook, `AGENTIC` registers the legacy retrieval tool, and `NONE` disables that automatic integration. Deprecation is a migration signal, not evidence that these methods have already stopped working.

The [backend guides](/dev/en/integration/rag/overview) describe these compatibility adapters. You can retain an existing adapter while moving the call site into your application, but wrapping it does not remove its own deprecation or guarantee its availability in a future release. There is no single new Builder method that replaces the entire ingestion, retrieval and agent-integration stack.

## Retrieve before the agent call

Choose this pattern when every question must pass through a fixed retrieval pipeline. Your application controls query rewriting, document access checks, retrieval and reranking before submitting the evidence and original question together. The agent does not decide whether that initial retrieval happens.

The following method accepts an already configured agent and an application-owned asynchronous retriever. The retriever returns formatted evidence with stable source identifiers; it should complete empty when no eligible evidence is found. Retrieval errors propagate to the caller rather than being turned into an empty successful result.

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

Configure the agent's system prompt to use evidence as reference material, cite its source identifiers and state when the evidence is insufficient. Retrieved text can contain instructions from an external author, so it must not become trusted system instructions. A prompt rule is useful guidance, not a security boundary.

Keep the original question and the application's conversation/session identity when assembling this input. If you centralize retrieval in middleware, choose the boundary deliberately: a lookup once per incoming question differs from a lookup on each reasoning step. Repeatedly appending the same evidence during tool iterations wastes context and can change the answer. This once-per-question pattern is therefore not an exact behavioral replacement for every legacy `GENERIC` hook setup.

## Let the agent request retrieval

Choose a tool when retrieval should be optional, or when the agent needs follow-up queries after seeing earlier results. Wrap your retrieval service in an ordinary annotated tool; it does not need to implement the deprecated `Knowledge` interface.

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

On a `Toolkit` instance named `toolkit`, call `toolkit.registerTool(new KnowledgeSearch(retrieve))`, where `retrieve` is your service function, and pass that toolkit to `ReActAgent.builder().toolkit(toolkit)`. See [custom tools](/dev/en/docs/building-blocks/tool#custom-tools-annotation-based) for registration, permission handling and injected runtime context. The model may skip the tool, invoke it more than once, or supply a reformulated query; this pattern does not guarantee retrieval for every question.

Resolve tenant and user identity from trusted application/session context, not from a model-supplied tenant argument. Enforce document access inside the retrieval service before returning text. If access filters affect eligibility, apply them before selecting the final Top-K so excluded documents cannot occupy the result budget. Shared agent/tool instances must resolve the identity for each invocation rather than capture one user's credentials in mutable fields.

## Keep the retrieval contract explicit

Preserve document identifiers and source metadata through reranking and text formatting so answers can cite the evidence actually returned. Bound both the number and size of passages to fit the model's context budget. Distinguish “no eligible evidence” from a backend failure, and define the timeout and cancellation behavior of the retrieval service. Keep blocking database or HTTP work off reactive event-loop threads.

Moving retrieval out of the Builder does not migrate the document index, embeddings, backend credentials or historical agent state. Retain those resources until the new path is verified with your application's questions, access rules and empty/error cases. Remove the old automatic hook/tool registration when enabling its replacement to avoid performing both paths unintentionally.

[JEV RAG](/dev/en/docs/harness/jev/guides/rag-api) addresses evidence selection and answer verification for its own workflow. It is not a drop-in replacement for all legacy knowledge adapters or for document ingestion, chunking and vector-store operations.
