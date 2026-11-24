---
title: "Adapt an existing Knowledge retriever"
zh_link: /dev/zh/docs/harness/jev/guides/knowledge-adapter-api
---

`JevKnowledge` implements the existing, deprecated `Knowledge` interface for compatibility. New applications can use [JevRag](/dev/en/docs/harness/jev/guides/rag-api) directly without depending on the deprecated API.

## Example: filter refund policies from an existing knowledge base

Your `Knowledge` implementation retrieves refund policies, shipping instructions and private compensation rules for a refund query. Keep the existing backend, remove material the user cannot access, then select relevant documents from the authorized candidates.

`JevKnowledge` wraps an existing instance; `JevKnowledgeTool` exposes results as `search_knowledge`. You do not need to rewrite document ingestion or vector retrieval.

## 1. Wrap your existing Knowledge instance

`existingKnowledge` is your implementation. Set up `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). Supply a `BiPredicate<RuntimeContext, Document>` named `canRead` that deterministically checks user, tenant and document permissions.

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

20 is the candidate recall count under enforcement; 5 limits returned results. SHADOW keeps the original retrieval config and post-ACL order while observing suggestions. After calibration, switch the shared `options` to ENFORCE. Selector and adapter modes must agree.

Here the ACL removes private material before JEV judges visible policies and shipping instructions. The example 0.2/0.8 thresholds do not guarantee selection of any document.

## 2. Register the tool or retrieve directly

Register the tool in your existing `toolkit` and pass that Toolkit to the Agent:

```java
var tool = new JevKnowledgeTool(knowledge, retrieveConfig);
toolkit.registerTool(tool);
```

The tool is named `search_knowledge`. Toolkit injects RuntimeContext; model arguments cannot impersonate another user. The tool returns passage IDs and text. Add [final draft review](/dev/en/docs/harness/jev/guides/answer-refinement-api) to check that the answer preserves refund conditions.

Applications generating their own answers can retrieve directly. `ctx` is the current user and session's runtime context:

```java
var documents = knowledge.retrieve(ctx, "What are the refund conditions?", retrieveConfig).block();
System.out.println("Documents available to the answer generator: " + documents.size());
```

ENFORCE returns only authorized documents judged clearly relevant. Explicit irrelevance yields an empty list; all-uncertain candidates or judgment errors raise an exception. Report retrieval as unavailable or escalate instead of claiming the knowledge base has no answer.

## Run the offline example

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt) includes a fixed Knowledge backend with the “unused and within seven days” policy. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

The example checks that the second model call receives the policy from the tool and that an unconditional promise is revised to retain the conditions. Scripted decisions verify tool integration and evidence delivery, not retrieval recall or live accuracy.

## Result semantics and limits

`search_knowledge` receives identity from Toolkit-injected RuntimeContext rather than model parameters. It returns authorized passage IDs and text. The upstream backend still enforces tenant retrieval boundaries; JEV grants no access.

Processing order: upstream recall, ACL, independent JEV relevance checks, probability ordering and result limit. ENFORCE requests `max(candidateLimit, config.limit)` candidates; candidateLimit ranges from 1 to 1,024. Other config values remain intact. Upstream thresholds may affect recall; measure it separately.

OFF/SHADOW return authorized candidates in original order without expanding retrieval. SHADOW records only suggestions. ENFORCE returns clearly relevant documents; uncertain ones are not evidence. Explicit irrelevance returns an empty list, backend errors propagate, and all-uncertain input raises an inconclusive exception. Match the selector's mode to the adapter's.

The adapter returns original Document objects with ID, metadata, embedding and retrieval score intact. It never overwrites the vector score with a JEV probability. `addDocuments` delegates unchanged without memory admission or write authorization.

Call `retrieve(ctx, query, config)` directly. Through the standard Knowledge interface, put `RuntimeContext.class` in Reactor Context; missing context is rejected to avoid implicit cross-session identity reuse. Set a separate retrieval timeout: JEV's budget excludes the upstream backend. Cancellation propagates along the reactive chain.

See the [combined example](/dev/en/docs/harness/jev/guides/agent-integration-example).
