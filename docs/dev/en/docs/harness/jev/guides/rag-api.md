---
title: "Select relevant documents and check answer grounding"
zh_link: /dev/zh/docs/harness/jev/guides/rag-api
---

`JevRag` filters and ranks evidence from authorized documents, then checks factual claims against it. Use it when your application owns retrieval, generation and publication.

## Example: answer a refund question without exposing private compensation policy

A customer asks whether an unshipped order qualifies for a refund. Retrieval returns public terms, tracking instructions and private compensation rules. Retrieval alone neither authorizes sending all three to a model nor permits omitting the policy's review requirement.

First filter unauthorized documents at the host. JEV then selects relevant visible evidence. After generation, check separately whether the answer preserves the conditions supported by that evidence.

## 1. Provide versioned documents and access control

```java
import io.agentscope.extensions.judge.jev.application.JevRag;
import java.util.List;

var documents = List.of(
    new JevRag.Document("policy", "Unshipped orders qualify for a refund request, subject to review", "v1"),
    new JevRag.Document("delivery", "How to track a shipment", "v1"),
    new JevRag.Document("private", "Internal compensation policy", "v1"));
var rag = new JevRag(selector, judge);
var query = "Can I get a refund if the order has not shipped?";
var pending = rag.retrieve(ctx, query, documents,
    document -> !document.id().equals("private"), 2);
```

Initialize `selector` and `judge` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). The example predicate simulates access control; use the current user's real ACL. Filtering occurs before JEV, so a private document never reaches the decision model regardless of relevance.

The final 2 caps clearly relevant documents returned; it is not a quota to fill. Visible document IDs must be unique; versions identify the sources actually cited.

## 2. Generate and review only after obtaining evidence

```java
var checked = pending.flatMap(retrieval -> {
    System.out.println("Retrieval status: " + retrieval.status());
    System.out.println("Not conclusively scored: " + retrieval.unscored());
    // Demo draft. In an application, generate from retrieval.evidence() first.
    String draft = "Unshipped orders qualify for a refund request, subject to review.";
    return rag.verify(query, draft, retrieval.evidence());
});
```

Relevance uses independent probabilities per document and ranks selected items by score. `unscored()` means unresolved, not irrelevant. Inspect retrieval status and evidence count before generation. The example uses a fixed draft to illustrate verification.

Without valid evidence, `verify` returns FAIL without calling the model. Abstain or retrieve again rather than publishing an unsourced answer. Even with evidence, a JEV PASS does not replace deterministic checks that citation IDs, versions and links exist.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios rag
```

The offline fixture retains `policy`, excludes shipping instructions and filters the private passage before judgment. The supported draft gets a scripted PASS; empty evidence deterministically fails. This verifies ACL, selection and empty-evidence handling, not live recall or answer quality. [Full source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Modes, failures and the full retrieval pipeline

The component returns data without changing Agent context. In SHADOW, record recommendations only; applying a filter requires explicit host adoption. When disabled or judgment fails, no valid evidence is available and all visible IDs go to `unscored`. Preserve the status rather than recording “definitely no relevant documents.” Cancellation propagates along the original subscription.

To distinguish injection, conflicting premises, relevance and answer evidence, register a read-only Agent tool or revise before publication, use the [retrieval evidence pipeline](/dev/en/docs/harness/jev/guides/evidence-pipeline-api).
