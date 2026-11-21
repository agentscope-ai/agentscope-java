---
title: "Supply trusted verification evidence for supervision"
zh_link: /dev/zh/docs/harness/jev/guides/supervision-evidence
---

`SupervisionEvidence` supplies the host's artifact revision, task constraints and verification receipt. A recommendation for completion review must rest on current evidence, not on an Agent claiming that tests passed.

## Example: the previous report passed, but its revision has not been verified

An order report changes from v1 to v2. A successful v1 receipt does not verify v2 even if a model finds its contents complete. In a multiuser system, also prevent reuse of another task's verification records.

Read a consistent snapshot for each observation and bind verification to user/session/run and artifact revision. A scope or version mismatch prevents a completion-review recommendation.

## 1. Connect your evidence reader

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;

var source = new JevSupervisionMiddleware.EvidenceSource(request ->
    evidenceRepository.readAuthorizedSnapshot(request));
var context = RuntimeContext.builder()
    .userId(userId).sessionId(sessionId)
    .put(JevSupervisionMiddleware.EvidenceSource.class, source)
    .build();
```

`evidenceRepository` stands for an application interface taking `JevSupervisionMiddleware.EvidenceRequest` and returning `Mono<SupervisionEvidence>`. It checks ACLs, reads the current artifact and verifies the record's provenance instead of asking a model to infer success from prose.

Pass `context` to `agent.streamEvents(messages, context)` for this call. See [Continuous supervision](/dev/en/docs/harness/jev/guides/supervision-api#basic-api) for middleware registration. Evidence reading and judgment share a budget; a read timeout is never evidence of completion.

## 2. Bind verification to the current snapshot

`SupervisionEvidence` describes revision, repositoryStatus, diff, changedFiles, instructions, documentationRequired and optional Verification. It can represent code changes or versioned business artifacts such as reports.

| Verification field | Meaning in report verification |
|---|---|
| userId / sessionId | User and session authorized to see this report |
| runId | UUID of the current supervision invocation, from request.scope |
| revision | Artifact version actually checked; must match the evidence revision |
| source | Check identifier with provenance the host can verify |
| passed / summary | Actual check outcome and a summary |

A successful check, nonempty source, matching scope and matching revision are all required. Do not relabel an old receipt as v2 to make it match; rerun the check against v2.

For code tasks the revision must cover uncommitted changes; Git HEAD alone is insufficient. Setting `passed=true` in JSON does not establish trustworthy provenance.

## 3. Consume advice while retaining responsibility for final review

Missing evidence source, stale receipt, failed verification or truncated material prevents completion review. Consume REQUEST_VERIFICATION or MANUAL_REVIEW while the task continues normally.

REVIEW_COMPLETION means the task is ready for review. It does not publish, approve or change task state for you.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevSupervisionExample
```

The example records exactly one local order-tool execution. The host supplies a receipt for `order-42-v1` using the identity and run UUID from the current request.scope. Scripted JEV judgments then yield REVIEW_COMPLETION with `verified revision` true. [Full source](/examples/jev/source/JevSupervisionExample.java.txt)

This example verifies evidence integration and scope. Your application still owns the real build system, artifact versioning, authorization and cross-process recovery.
