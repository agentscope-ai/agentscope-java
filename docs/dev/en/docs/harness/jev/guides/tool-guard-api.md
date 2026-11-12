---
title: "Check protected operations before tool execution"
zh_link: /dev/zh/docs/harness/jev/guides/tool-guard-api
---

For tools such as refunds or outbound messaging, use `JevAutoModeMiddleware` to judge whether a proposed operation fits the task before dispatch. An allowed call still goes through PermissionEngine; a denial returns a tool result paired with the original call.

## Example: check order status before issuing a refund

Suppose your support Agent has two tools:

- `query_order` looks up an order and its shipping status.
- `refund` requests a refund from the payment system.

A customer asks: “Check order A1001 and refund it if it hasn't shipped.” The model might skip lookup and propose `refund(orderId="A1001")`. The tool and arguments are valid and the customer has permission, but there is no evidence yet that the refund condition holds.

JEV adds a contextual check: **given this conversation and these arguments, is the operation appropriate for automatic execution?** In enforcement mode a failed check prevents dispatch and returns a denial to the Agent so it can look up missing facts or request review.

The middleware supplies current session messages, protected tool name and arguments automatically. Include earlier lookup results in Agent context. JEV cannot look up the order itself; business code still verifies eligibility, amount limits and idempotency.

## 1. Configure a guard for the refund tool

Create a client and add `refund` to the protected tool set. Start in SHADOW to observe decisions without changing the execution path.

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.time.Duration;
import java.util.Set;

var client = JevClient.builder().build();
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2),
    "refund-guard-v1",
    (ctx, record) -> System.out.println(record));

var guard = JevAutoModeMiddleware.builder(client)
    .execution(options)
    .guardedTools(Set.of("refund"))
    .safetyThreshold(0.8)
    .denyMessage("Refund was not approved for automatic execution. Provide the order status or request human review.")
    .build();
```

The client reads credentials from the environment; see [Client setup](/dev/en/docs/harness/jev/guides/client) for dependencies and keys. Each setting has a specific effect:

| Setting | Effect in the example |
|---|---|
| `guardedTools(Set.of("refund"))` | Guard refunds only; `query_order` follows the original flow. Names must match Toolkit registrations |
| `safetyThreshold(0.8)` | In enforcement only calls with safety probability at least 0.8 reach subsequent permission checks. Calibrate this illustrative value on business samples |
| `denyMessage(...)` | Tool result returned to the Agent on denial, not an automatic customer reply. Prompt the Agent to obtain evidence or request review |
| `Duration.ofSeconds(2)` | Total check budget including retries and backoff |
| `"refund-guard-v1"` and observer | Identify the configuration and record which refunds would be blocked |

To also check outbound messages use `.guardedTools(Set.of("refund", "send_message"))`. Membership enables checking; it does not grant permission to execute.

## 2. Register with your Harness

Register `guard` on the existing Agent. `model` is your generator, `toolkit` registers `query_order` and `refund`, and `permissionContext` contains your existing permissions.

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("order-support")
    .model(model)
    .toolkit(toolkit)
    .permissionContext(permissionContext)
    .middleware(guard)
    .build();
```

Invoke the Agent normally. The middleware checks model-proposed calls before execution; you need not invoke `guard` separately. The normal Harness call chain supplies session state.

In this workflow the unprotected `query_order` follows normal permission checks. The subsequent `refund` is guarded, allowing JEV to consider lookup evidence and the customer's condition together.

## 3. Switch from observation to enforcement

SHADOW compares recommendations with expectations and **does not prevent a refund**. To block calls, set `JevExecution.Mode.ENFORCE` when constructing `options` and rebuild the middleware and Agent.

For an illustrative threshold of 0.8, suppose two judgments produce the following probabilities:

| Proposed call | Example safety probability | SHADOW | ENFORCE |
|---|---|---|---|
| Refund proposed before order lookup | 0.15 | Record suggested denial; continue original execution chain | Do not dispatch; return a DENIED tool result |
| Refund proposed after lookup evidence | 0.93 | Record suggested approval; continue original execution chain | Pass semantic check; PermissionEngine still decides whether execution is authorized |

These probabilities illustrate behavior; the model need not return them. Business validation can still reject an allowed refund. Only the refund tool's business result establishes actual success.

When multiple tools are proposed, the middleware judges protected calls individually. A denial returns a result with the original `toolCallId`. Other calls proceed in order; the middleware never automatically retries a refund.

## Run the offline example

[JevHarnessExample](/examples/jev/source/JevHarnessExample.java.txt) uses synthetic decisions to show OFF, SHADOW and ENFORCE. Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessExample
```

The scripted refund has low safety probability. SHADOW reaches simulated dispatch; ENFORCE emits a denial without dispatch. The example calls neither a model nor a real payment system.

## Errors and human confirmation

- **Check failure:** In ENFORCE, missing session state or answers, invalid probabilities, timeout and backend failure deny protected calls. SHADOW preserves the flow; OFF makes no judgment. Cancellation prevents further dispatch.
- **Human confirmation:** Confirmed ALLOWED calls follow the existing confirmation chain.
- **Middleware order:** Finish any argument-changing middleware before this guard so checked arguments match those dispatched.

See [Runtime configuration](/dev/en/docs/harness/jev/guides/harness-runtime) for the mode and observer contract.

## Refund scenario comparison

On September 26, 2026, the middleware compared JEV and Qwen using the same 120 synthetic session snapshots and 128 protected calls. Each backend ran once with threshold `0.8` and a two-second total budget. Returned model versions were `jev-1.13.0` and `qwen3.8-max`. All refunds were local simulations.

| Metric | JEV | Qwen |
|---|---:|---:|
| Valid judgment responses / requests | 120 / 120 | 111 / 120 |
| Correct decisions / all protected calls (including failures) | 60 / 128 (46.88%) | 118 / 128 (92.19%) |
| Incorrect dispatches / calls that should be blocked | 0 / 60 | 1 / 60 |
| Legitimate refunds blocked / calls that should proceed | 68 / 68 | 6 / 68 |
| Check latency P50 / P95 | 475 / 553 ms | 980 / 1,346 ms |

With this generic safety question and threshold, JEV was faster but blocked every legitimate refund. Among valid decisions Qwen was 99.16% accurate; nine responses failed format validation, and fallback on failure blocked six legitimate refunds. Neither backend timed out. These timings measure the check, not the full Agent task, and these results do not measure payment success. Calibrate for your use case before enforcing.

The dataset, labeling rules, commands, offline checks and comparison with a 60-second budget are in `agentscope-examples/jev/benchmarks/tool-guard`. Read the [methodology](/examples/jev/tool-guard/README.md.txt) or [download the cases and raw results](/examples/jev/tool-guard-benchmark.zip).
