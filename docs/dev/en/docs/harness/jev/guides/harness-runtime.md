---
title: "Configure modes, budgets and decision observers"
zh_link: /dev/zh/docs/harness/jev/guides/harness-runtime
---

`JevExecution.Options` configures execution mode, total budget and observer for tool selection, guards, routing and other features. Observe recommendations before deciding which ones should affect the Agent.

## Example: observe a refund guard before enforcing it

You want to add a pre-refund check without knowing how often it will block valid requests. Enforcing immediately can disrupt service; leaving it off yields no observations.

SHADOW makes real judgments and records recommendations while passing original inputs onward. Compare suggested denials with expected business outcomes, calibrate, then switch only the refund guard to ENFORCE.

## 1. Set a budget and version for this purpose

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.time.Duration;

var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "refund-guard-v1",
    (ctx, record) -> System.out.println(record));
var guard = JevAutoModeMiddleware.builder(client)
    .execution(options)
    .guardedTool("refund")
    .build();
```

Build `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client) and register `guard` with `HarnessAgent.builder().middleware(guard)`. Version identifies the configuration in observations. Change it when criteria or thresholds change so results remain distinguishable.

Two seconds bounds all batches, client retries and backoff in one logical judgment. It excludes subsequent Agent tool execution. A per-HTTP-request timeout cannot replace this total budget.

## 2. Distinguish recommendations from actual actions

| Mode | Judgment request | Actual refund behavior |
|---|---|---|
| OFF | None | Original flow; there may be no observer record |
| SHADOW | When needed | The original execution chain receives the refund even if denial is recommended |
| ENFORCE | When needed | The guard blocks or allows the protected call |

Credentials in the environment do not enable features. Update configuration and rebuild middleware to change modes. Modes are independent by purpose: tool selection can stay in SHADOW while the refund guard enforces.

## 3. Record comparable decision metadata

The observer receives the current RuntimeContext and Record:

| Field | Use |
|---|---|
| `purpose / version / mode` | Identify the capability and active configuration |
| `status / reason` | Distinguish a decision, uncertainty, call failure and cancellation |
| `elapsed` | Measure check latency separately from overall Agent time |
| `recommendation` | Compare the recommended model or denied call IDs with actual execution |

The Record omits full messages, tool arguments, credentials and raw errors. Correlate run/session through RuntimeContext. Observers should return promptly; queue database writes yourself. Observer RuntimeExceptions do not stop the Agent, but blocking work does not automatically move to the background.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessExample
```

The offline example runs identical selection, guard and routing inputs in all three modes. A SHADOW denial still enters simulated dispatch; ENFORCE produces a denied tool result. [Full source](/examples/jev/source/JevHarnessExample.java.txt)

For a live comparison, see the [refund guard example](/dev/en/docs/harness/jev/guides/tool-guard-api#refund-scenario-comparison). Preset probabilities provide no evidence of model accuracy or latency.

## Failure behavior by purpose

| Purpose | Failed judgment in ENFORCE |
|---|---|
| Tool selection | Retain the original candidate tools |
| Model routing | Use the original model without rerunning the Agent |
| Pre-execution guard | Deny protected calls; other tools proceed normally |
| Content and quality review | Withhold text that did not pass |

Shared statuses are DECIDED, INCONCLUSIVE, ERROR, CANCELLED and SKIPPED. TIMEOUT and CALL_OR_RESPONSE_ERROR are failure reasons. Cancellation notifies the observer rather than fabricating a fallback for dispatch. Whether blocking HTTP stops immediately depends on the transport.

Retrospective trace evaluation and long-task supervision support only OFF/SHADOW; neither can undo completed actions. Direct application APIs such as Judge and memory review run when invoked and do not read a global mode. Put application feature switches around the complete workflow.
