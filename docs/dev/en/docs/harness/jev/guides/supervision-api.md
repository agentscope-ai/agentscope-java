---
title: "Observe task progress and delivery readiness"
zh_link: /dev/zh/docs/harness/jev/guides/supervision-api
---

`JevSupervisionMiddleware` observes progress, drift, verification needs and delivery evidence during Agent execution and recommends next steps. Use it in engineering tasks, research workflows or multistep tool use. It cannot stop tasks, replay tools or declare completion.

## Example: does a verification report need more evidence or review for delivery?

An order investigation must check status, summarize exceptions and produce an evidence-based report. Repeated “working on it” messages or a premature claim of completion do not demonstrate substantive progress.

The middleware considers current output and host evidence during execution. It can recommend continuing, obtaining verification, reviewing direction or considering completion. It neither stops the Agent nor writes a model's judgment into task status.

## Basic API

Depend on `io.agentscope:agentscope-extensions-jev` at the current project BOM version. Types in `io.agentscope.extensions.judge.jev.supervision` include `JevTaskSupervisor`, `JevSupervisionMiddleware` and `SupervisionEvidence`.

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;

var client = JevClient.builder().build();
var supervisor = new JevTaskSupervisor(
    client::systemOne,
    new JevTaskSupervisor.Thresholds(0.2, 0.8), // Illustrative, uncalibrated thresholds
    JevTaskSupervisor.Limits.defaults(),
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(3), "supervision-v1",
        (ctx, record) -> System.out.println(record)));
var middleware = new JevSupervisionMiddleware(
    supervisor,
    JevSupervisionMiddleware.Schedule.defaults(),
    (ctx, observation) -> System.out.println(observation));

var agent = HarnessAgent.builder()
    .name("engineering-assistant")
    .model(model)
    .middleware(middleware)
    .build();
```

`model` is your existing generator. Register actual verification tools normally. The first observer records check status, duration and version; the second receives actionable advice. Replace console printing with a fast-returning metadata recorder in services.

`Schedule.defaults()` limits frequency and total assessments. Three seconds bounds one evidence read and judgment, not the whole task. Start in SHADOW to see when verification is recommended without altering the workflow.

The middleware also works with ReActAgent. OFF neither captures nor calls a model. SHADOW preserves input, tool execution and output events; construction rejects ENFORCE. The API requires an explicit mode.

Call `supervisor.observe(ctx, () -> Mono.just(snapshot))` directly to use the same checks and arbitration for offline observation. A Snapshot includes Scope, observation number, task, output tail, recent events, running/failure status, truncation flags, elapsed time and host evidence. Evidence acquisition and model requests share one JevExecution budget.

## Interpret recommendations in a verification task

Checks are named by responsibility:

| Responsibility | Checks |
| --- | --- |
| core.completion | implementation_complete、requirements_satisfied、ready_to_finish |
| core.verification | tests_sufficient、needs_verification |
| core.worker-health | meaningful_progress、worker_stuck、work_off_track |
| repository.instructions | agents_md_drift |
| core.human-escalation | needs_human |
| quality.documentation | documentation_sufficient, only when documentation is explicitly required |

Each returns its raw probability and YES / NO / UNKNOWN; UNKNOWN is not NO. Calibrate the example 0.2/0.8 thresholds. Only uncertainty affecting the current decision branch triggers manual review. The informational meaningful_progress signal is retained independently.

`Decision<Report>` uses DECIDED / INCONCLUSIVE / ERROR / SKIPPED. Report holds findings, all proposals, selected advice, truncation flags, verification status, backend version and usage. DECIDED means advice was determined, not that all checks passed. Errors and limit violations may have no Report.

Arbitration priority: need for a human, runtime failure, direction problems for an active worker, incomplete evidence, missing or insufficient current verification, incomplete required documentation, completion review, branch-relevant uncertainty, then continue working. Break ties by probability, then stable ordering. Repository constraints precede health issues at equal confidence.

While order lookup continues, record CONTINUE. If the report appears complete but lacks host verification, request verification. Only after successful version-matched verification can REVIEW_COMPLETION be recommended.

Advice is CONTINUE, REQUEST_VERIFICATION, REVIEW_COMPLETION, REVIEW_DOCUMENTATION, REVIEW_DIRECTION or MANUAL_REVIEW. None carries execution authority; REVIEW_COMPLETION does not change Agent or business task state. Offline workerFailed and live iteration limits, stop requests or total tool denial prevent completion review. Exceptional termination propagates unchanged.

## Trusted verification evidence

Inject an EvidenceSource into this RuntimeContext with scoped, revision-bound artifacts and verification records. No valid evidence means no completion-review recommendation. [Integration and validity rules](/dev/en/docs/harness/jev/guides/supervision-evidence)

## Budgets, concurrency and cancellation

Defaults: five-second minimum interval, 30-second period, at most 60 assessments per invocation and three consecutive errors. New events mark observation dirty rather than triggering a request each. Periodic checks continue without new events. Only one judgment is in flight per invocation; keep only the latest pending tick.

Agent events continue downstream without waiting for judgments. Normal completion awaits the active judgment and, if quota permits, a final assessment, adding up to two budget periods. Callbacks must be nonblocking. Updates during observation mark it superseded: consume the old advice as CONTINUE but retain scores for audit. This detects observed runtime events only; the host must version external artifact changes.

Cancellation stops ticks, evidence reads and active judgments without a final observation; remote billing may continue. Propagate Agent errors without retrying the Agent. Record transient observation errors; at the consecutive-failure limit stop observing this run and recommend manual review while the worker continues. Record ASSESSMENT_LIMIT at the assessment cap; continuous output does not increase quota.

Default limits: 12,000 text characters, 30,000 diff characters, 100 files and 100,000 characters of total state. Abstain rather than truncate oversized task or repository constraints. Output and diff tails may be retained with truncation flagged, preventing completion review. Non-text inputs or final results also mark evidence incomplete. Recent events contain up to 32 type labels, not a full trace archive.

Missing questions, wrong types, invalid probabilities, empty responses, evidence failures or timeouts never produce a completion conclusion. Observer RuntimeExceptions are isolated.
## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevSupervisionExample
```

The example uses a real ReActAgent, scripted model, simulated order tool and synthetic JEV responses. The host confirms exactly one tool execution and supplies verification matching the current observation UUID. Expect `Tool executions: 1, advice: REVIEW_COMPLETION, verified revision: true`. This checks execution boundaries, not semantic accuracy.

[Full source](/examples/jev/source/JevSupervisionExample.java.txt). The example reduces the investigation to one order so you can inspect actual tool count and receipt. Measure direction and completion advice separately on real long-task traces.
