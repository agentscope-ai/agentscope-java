# Durable Thread Evolution for Multi-Agent Orchestration

Date: 2026-11-10. Status: **design reference, not a claim of current capability**.

This document combines the current AgentScope Session / Thread / Turn / Run / Step execution
model, Durable child delegation, and the Session Threads and Multi-Agent Orchestration semantics
documented by Anthropic. It records how Thread should support future multi-agent capabilities and
which improvements remain.

External references:

- [Anthropic Multi-agent orchestration](https://platform.claude.com/docs/en/managed-agents/multiagent-orchestration)
- [Anthropic Session threads](https://platform.claude.com/docs/en/managed-agents/session-threads)

This document does not introduce Anthropic API compatibility and does not change the boundaries in
the [unified execution model](execution-model-plan.md). Durable remains a general-purpose
framework. Core and Harness do not depend on Durable, and platforms such as Managed Agents assemble
these capabilities above Durable.

## 1. Conclusion

The current Durable Thread and the Anthropic Session Thread have substantially the same core
boundary:

- A Session is the scope of a continuing collaboration. It owns identity, the Thread directory,
  public events and lifecycle.
- A Thread is one continuing, isolated Agent context. It owns the Agent binding, native state,
  history, checkpoints, task queue and execution ownership.
- The primary Agent uses the primary Thread. A subagent that needs durable context uses a Child
  Thread.
- One task or follow-up sent to an Agent is a Turn on that Thread.
- Suspension, recovery and takeover create another Run, not another Agent Thread. A Step remains an
  execution detail within a Run.

Multi-agent support therefore does not require redefining Thread or restoring the removed
AgentSession scheduler. Preserve the current model:

```text
Session: one business conversation or multi-agent collaboration scope
├─ main Thread: continuing context of the Coordinator / Primary Agent
│  └─ Turn → Run → Step
└─ child Thread: continuing context of one persistent Subagent
   ├─ Turn 1: initial delegation
   └─ Turn 2: follow-up
```

Durable already provides the persistent execution foundation required for multi-agent operation,
but it does not yet expose a complete multi-agent user experience. The main gaps are unifying the
Harness subagent API with Durable Child Threads, Thread discovery and relationship views,
Session-level budget and activity aggregation, shared-resource policy, and temporary Workflow
Thread lifecycle.

## 2. Concept mapping

| Anthropic concept | Durable concept | Meaning |
| --- | --- | --- |
| Session | Session | Collaboration, authorization and resource scope containing primary and child Threads |
| Primary thread | `main` Thread | Primary Agent context and default user entry point |
| Subagent thread | Child Thread | Persistent, isolated subagent context |
| One task sent to a subagent | Child Turn | Logical task that can be queued, paused, cancelled and recovered |
| Follow-up to an existing subagent | New Turn on the same Child Thread | Reuses the Agent state and context |
| Agent execution | Run | One actual invocation; recovery after suspension uses a new Run |
| One model/tool processing round | Step | Run-level diagnostics, usage and recovery correlation |
| Workflow run thread | Temporary Child Thread | Can later be expressed with purpose and automatic archival policy |

Anthropic isolates each Agent conversation and event stream in a Thread while sharing sandbox,
files, credentials and budget within the Session. Durable currently emphasizes execution
correctness: a Thread independently owns its checkpoint, journal, FIFO, lease, epoch and private
resources. Workspace, Sandbox, knowledge and result sharing are explicit policies. This difference
does not require changing Thread; it requires a Session resource policy.

## 3. Existing foundation

### 3.1 Multiple Threads and isolated execution

`DurableSession` already supports the primary Thread and explicit Thread creation, lookup and
listing. `DurableThread` provides independent Turn, history, event, checkpoint, restore and fork
operations. Every Thread has a complete `ThreadKey(SessionKey, threadId)` and a frozen
`ThreadBinding(harness, definitionVersion, stateVersion)`.

The Backend schedules ready work, leases and epochs by Thread. Different Threads in one Session can
run concurrently, while one Thread still has one authoritative owner. Session public events use one
sequence, and Thread/Turn/Run observers select their scope through filters. A Session event stream
does not automatically enter the primary Agent's model context.

### 3.2 Durable child delegation

`DelegationExecutor` already implements the following through the standard Executor/result
boundary:

1. Create a deterministic Child Thread from the parent `executionId` by default.
2. Admit a Child Turn on that Thread.
3. Persist `executionId → childThreadId + childTurnId` and parent provenance.
4. Reuse the frozen mapping after redelivery or host replacement instead of duplicating work.
5. Let the parent Turn await the Child Turn while the Child Thread schedules independently.
6. Cancel only the mapped Child Turn; pausing the parent does not automatically pause the child.
7. Reject cross-Session delegation, self-delegation and cycles of unfinished dependencies.
8. Allow a target resolver to select an existing Child Thread and reuse its context.

This covers the most difficult multi-agent recovery boundary. If the parent Agent, child Agent or
Runtime exits during delegation, the mapping, Child Turn and result application remain recoverable.

### 3.3 Harness subagents still use a separate path

Core `SubAgentTool` and Harness `SubagentsMiddleware` already support subagent creation, an
`agent_key`, asynchronous tasks and follow-ups. A local child invocation normally receives its own
child sessionId and clears threadId, turnId and runId. It can provide in-process or Harness-managed
multi-agent behavior, but it does not automatically become a Child Thread of the current Durable
Session.

Only a tool routed through Durable to `DelegationExecutor` receives the durable Child Thread,
Child Turn, recovery and independent scheduling semantics above. The next step is to unify the user
experience of these paths without adding a second persistent scheduler to Harness.

## 4. Gaps to complete multi-agent support

### 4.1 No unified subagent handle

Harness uses `agent_key` to identify a subagent that accepts follow-ups; Durable uses `ThreadKey`.
There is no common coordinator that durably maps them or exposes consistent spawn, send/follow-up,
list, interrupt and archive operations.

The target semantics are:

```text
spawn(agent definition)
  -> create or resolve a Child Thread
  -> return an opaque agent_key
  -> persistently map agent_key to ThreadKey

send(agent_key, message)
  -> submit a new Turn to the same Child Thread
```

`agent_key` is a safe handle for models and product APIs. `ThreadKey` is the authoritative Durable
identity. They should not collapse into an unprotected, user-constructible string.

### 4.2 Thread relationships have no discovery view

Parent provenance currently belongs to the Delegation/Turn, which is the correct execution
relationship. `ThreadView` contains only the definition and lifecycle; it has no purpose, creation
source, display label or archival information.

A single authoritative `Thread.parentThreadId` would be too restrictive. An explicitly reused Child
Thread can receive many follow-ups and may eventually serve Turns from more than one parent. Waiting
and cancellation belong to each Delegation, not to the Thread.

Add discovery and lifecycle metadata instead:

- `purpose`: `PRIMARY`, `SUBAGENT`, `WORKFLOW` or `GENERAL`.
- `createdByThreadId`: original creation source for lineage and audit only.
- `label`: optional human-readable name.
- `retention`: persistent retention or automatic archival after settlement.

Keep the authoritative execution relationship as:

```text
parent Thread/Turn/executionId -> child Thread/Turn
```

### 4.3 Session aggregation is incomplete

`SessionView` mainly contains Session identity, primary Thread, lifecycle and metadata. A complete
multi-agent product also needs:

- Session activity such as idle, running, waiting and paused, derived from committed Turns.
- Session-level token, monetary or time budgets atomically allocated across Threads and Turns.
- Limits for Thread count, concurrency, delegation depth and queued work.
- Session-wide cancellation, closure and resource reclamation policies.

Do not maintain these through another state machine that can drift. Derive activity from
authoritative Turn/lease facts, and align budget usage with Run and model request facts.

### 4.4 Event visibility and result routing are different concerns

Durable has Session-level public events and Thread filters, but two channels must remain distinct:

- **Observation channel:** users, consoles and platforms observe public events from all Threads.
- **Context channel:** a child result, request or permission interaction enters the primary Agent's
  context.

Observing a Child Thread from the Session does not mean the primary Agent reads its complete
history. Apply delegated results to the parent Turn as tool results through a stable executionId.
Blocking interactions may project a summary to the primary observation stream, but responses must
route to the original Thread/Turn/interactionId.

### 4.5 Shared-resource and isolation policy is not unified

| Resource | Recommended default | Meaning |
| --- | --- | --- |
| Agent conversation, checkpoint and journal | Thread-isolated | Never combine them into Session state |
| Turn queue, lease and epoch | Thread-isolated | Preserve concurrency and fencing correctness |
| Workspace | Shared or isolated by Agent definition | Reuse Harness `SHARED/ISOLATED` behavior |
| Credentials and permission policy | Shareable at Session scope, authorized at execution | Do not copy secrets into Thread DTOs/checkpoints |
| Sandbox | Explicitly shared or exclusive | Shared writes still require CAS or an operation ledger |
| Budget and concurrency quota | Aggregated by Session | Record Thread/Turn consumption in one ledger |

A Thread lease cannot protect a shared resource because different Threads run concurrently.

## 5. Recommended target model

### 5.1 Preserve the identity hierarchy

Continue to use:

```text
SessionKey(namespace, sessionId)
└─ ThreadKey(session, threadId)
   └─ turnId
      └─ runId
         └─ stepId
```

Do not encode agentId, parent Thread, purpose or Agent definition into `ThreadKey`. Those values can
change or belong to a view; encoding them in identity harms reuse, authorization and recovery.

### 5.2 Separate persistent Subagents from temporary Workflows

- A `SUBAGENT` Thread is persistent by default and accepts multiple Turns through its `agent_key`.
- A `WORKFLOW` Thread can be created for one workflow run and archived after settlement.
- Ordinary internal helper calls need not become Durable Threads. Promote only calls that require
  background execution, independent observation, restart recovery or follow-ups.

### 5.3 Delegation is an edge, not another resource hierarchy

Delegation connects a waiting parent Turn to a Child Turn and owns idempotency, result application
and cancellation boundaries. It does not replace Session/Thread/Turn or require another scheduler.

A Child Thread can contain:

```text
Delegation D1 -> Child Turn C1
follow-up D2 -> Child Turn C2
follow-up D3 -> Child Turn C3
```

Each Delegation has its own parent executionId; the Thread context remains continuous.

### 5.4 Keep Run and Step as internal execution granularity

- Each actual Child Thread invocation creates a Run.
- Recovery after a Child Turn suspends creates a new Run.
- A Run may contain multiple Steps.
- A follow-up is a new Turn, not a continuation of an old Run.

Products should show Agent/Thread and task/Turn by default. Run/Step remain available for diagnostics,
usage and recovery audit.

## 6. Phased improvement plan

### P0: Freeze semantics and protocol

1. Add the Session, Thread, Turn, Delegation, Run and Step mapping to the public protocol.
2. Define Thread purpose, origin and retention as discovery metadata, not identity.
3. Define a follow-up as a new Turn and suspension recovery as a new Run.
4. Define Session event visibility separately from model context sharing.

Acceptance: Java types, schema, HTTP and bilingual protocol documentation define the same fields and
lifecycle.

### P1: Add Thread discovery and lifecycle metadata

1. Extend `ThreadSpec/ThreadView` with `purpose`, `createdByThreadId`, `label` and
   retention/archival semantics.
2. Keep `DelegationParent` and `DelegationLink` as authoritative task relationships.
3. Add bounded Thread listing by purpose/status.
4. Distinguish close from archive: close rejects new work; archive controls default visibility and
   retention.

Acceptance: creation retries, close/archive races, snapshots, event replay, HTTP and shared Backend
contracts pass.

### P2: Build a Durable Subagent Coordinator

1. Define a Harness-neutral coordination interface without making Core/Harness depend on Durable.
2. Map opaque `agent_key` values durably to `ThreadKey` and a frozen Agent definition.
3. Let spawn create a `SUBAGENT` Thread and send/follow-up submit Turns to it.
4. Implement list/status/interrupt/archive through the public Durable API, without reading an
   in-process Agent instance.
5. Use `DelegationExecutor` for parent/child admission, waiting, cancellation and result application.

Acceptance: parent restart, child restart, Runtime replacement, duplicate spawn/send and lost
post-commit responses never duplicate a Thread or Turn; follow-ups on one `agent_key` restore the
same context.

### P3: Integrate Harness subagent tools

1. Preserve the lightweight local Harness child-call path.
2. When a host supplies the Durable coordinator, route spawn/send through Durable Child Threads.
3. Make local, persistent Subagent and temporary Workflow selection explicit and observable.
4. Align event source, task receipts and errors without exposing internal Durable IDs to the model.

Acceptance: ordinary HarnessAgent use does not require Durable. With Durable enabled, agent_key,
asynchronous completion, follow-up, cancellation and recovery use one Child Thread.

### P4: Add Session resource, budget and policy aggregation

1. Add Session-level budgets/concurrency policy and atomic consumption records.
2. Resolve WorkspaceMode, Sandbox and credentials into explicit shared or isolated policies.
3. Add Thread count, concurrency, delegation depth and fan-out limits.
4. Derive the Session activity view from authoritative Turn/lease facts.

Acceptance: concurrent Child Threads cannot exceed budgets or overwrite private state; actual
resource mechanisms control shared Workspace races; quota and permission failures remain distinct
from Agent business outcomes.

### P5: Add Workflow lifecycle and complete observation

1. Support automatic archival and retention periods for `WORKFLOW` Threads.
2. Project primary and child activity summaries into the Session observation stream while retaining
   full content on each Thread.
3. Show blocking interactions in the primary observation stream while routing answers by original
   interaction identity.
4. Present Session → Thread → Turn in the console, with Run/Step expanded on demand.

Acceptance: event reconnection, expired-cursor resynchronization, archive recovery, permission
interaction, parent/child concurrency and high-Thread-count pagination pass.

## 7. Required acceptance scenarios

1. The primary Agent exits after delegating; the child completes and a replacement host resumes the
   parent Turn.
2. The child exits around model completion, tool dispatch and result commit; no external side effect
   repeats.
3. Several follow-ups to one `agent_key` create separate Turns on one Thread.
4. Duplicate delegation, MQ redelivery and a lost post-commit response do not duplicate a Child
   Thread or Turn.
5. A Child Turn can complete while the parent is paused; the parent applies the result once after
   resume.
6. Cancelling the parent cancels only its Child Turn, not other Turns on a reused Child Thread.
7. Self-delegation, cross-Session delegation and unfinished dependency cycles are rejected.
8. Concurrent Child Threads do not mix checkpoints, journals, plan mode or private resources.
9. Exhausting the Session budget stops new execution admission while preserving committed facts and
   truthful execution state.
10. A permission request visible in the primary stream routes back to the original Child
    Thread/Turn/interaction.
11. Automatic Workflow Thread archival retains Turn/Run/Step facts required for audit.
12. Core/Harness users without Durable retain direct calls and local subagent behavior.

## 8. Approaches to avoid

- Do not add another `AgentSession` or MultiAgentSession scheduler.
- Do not share one AgentState, checkpoint or journal among every Agent in a Session.
- Do not create a Thread for every tool call, Run or recovery attempt.
- Do not automatically inject the complete Child Thread history into the primary Agent context.
- Do not use `parentThreadId` as unique ownership or the basis of cancellation propagation.
- Do not make Harness/Core depend on Durable; use an optional host coordination interface.
- Do not sacrifice the real Turn-level Delegation relationship to display a convenient Agent tree.

## 9. Related implementation and documents

- [Unified execution model](execution-model-plan.md)
- [Implementation progress](../verification/execution-model-progress.md)
- [Independent acceptance checklist](../verification/execution-model-acceptance.md)
- [Public interaction protocol](../../protocol/public-interaction-contract.en.md)
- `DurableSession` / `DurableThread`: Session and Thread user operations.
- `DelegationExecutor`: Child Thread + Child Turn delegation within one Session.
- `TransactionalSessionBackend.delegate`: atomic parent/child mapping, admission, events and schedules.
- `SubAgentTool` / `SubagentsMiddleware`: current Core/Harness subagent entry points.

Use this document as the starting point for future multi-agent design reviews. Record actual
implementation status and verification evidence in the
[implementation progress](../verification/execution-model-progress.md) and
[independent acceptance checklist](../verification/execution-model-acceptance.md). Editing this design reference
alone never establishes that a capability is implemented.
