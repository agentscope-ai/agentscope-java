---
title: Context construction
description: Understand model inputs, supply dynamic business context, enable task projections, and configure budgets
---

HarnessAgent prepares context at the final model-call boundary by default. Configure your
sysPrompt, workspace and tools; you do not need to assemble XML or message lists yourself.
Plain ReActAgent does not automatically install the Harness pipeline.

This page focuses on what is actually sent to the model. For state persistence, RuntimeContext
and concurrent access, see [Context & AgentState](/v2/en/docs/building-blocks/context).
The taskContext options below control projection, not state maintenance or persistence.

## What the model receives

The layout is **one System message → conversation → transient state → reference material**.
Tool schemas are separate request fields and count toward the budget.

| Content | Source | Placement |
| --- | --- | --- |
| Agent instructions | sysPrompt() | System, preserving user formatting |
| Project rules | AGENTS.md | System / project_rules |
| Context conventions | Built-in resource | System / instruction_rules |
| Workspace guidance, environment, mode | Framework and configuration | System / working_principles, environment, mode_rules |
| Stable business instructions | instruction() | System / business_instruction |
| Current Todo | TaskContextState | Latest complete tool receipt or TASK_STATE, without repeating the list |
| Requirements and verification summaries | Caller-managed state | TASK_STATE, explicitly enabled |
| PLAN/BUILD and plan path | PlanModeContext | Transient RUNTIME_STATE |
| Business information | contextSource() | Transient HARNESS_CONTEXT |
| MEMORY.md, knowledge, additional files | Workspace | Reference HARNESS_CONTEXT, not System |

Transient state and references are synthetic USER-role messages, not additions to durable history.
Memory-use instructions may remain in System; MEMORY.md content is reference material.

Tags separate sources and purposes; Markdown may organize their contents. Write ordinary Markdown
in AGENTS.md without adding wrapper tags. Original sysPrompt and third-party middleware prompts
are not automatically rewritten. Tags and escaping are not security isolation or authorization.

## Add dynamic business context

contextSource(name, source) means “read this information before each reasoning request.”
It is not a tool and does not require a model tool call. Registration and Agent construction
do not invoke the source. Leave it unconfigured if you do not need extra business data.

These snippets use a HarnessAgent.Builder named builder with model and workspace already configured:

```java
import io.agentscope.harness.agent.context.ContextBlock;
import java.util.List;
import reactor.core.publisher.Mono;

builder.contextSource(
        "order-status",
        request -> Mono.just(List.of(
                ContextBlock.runtime("status", "Order 483: awaiting approval"))));
```

Replace the fixed example with an authorized asynchronous query using request.userId() and
request.sessionId(). Sources must be read-only: do not deploy, write business records or run tests here.
For reusable logic, implement the functional ContextSource interface:
`Mono<List<ContextBlock>> load(ContextRequest request)`. No Extension or Registry is required.

ContextRequest exposes agentId, userId, sessionId, callId and purpose, not mutable AgentState.
Identities may be absent and are not proof of authorization. Source instances may be shared across
sessions and copied agents; make them thread-safe and never store a “current user” in instance fields.

### Materials and stable instructions

```java
ContextBlock.runtime("status", "Awaiting approval");
ContextBlock.reference("policy", "Refund policy reference");

// Optional controls; fluent methods return new objects.
ContextBlock.runtime("status", "Approved")
        .withRevision("order-version-7")
        .withPriority(20)
        .required();

// Trusted application configuration only, not external business data.
builder.instruction("approval-policy", "Do not release without valid approval.");
```

Dynamic blocks cannot produce SYSTEM instructions. The default revision is a content hash;
an explicit business revision does not automatically establish freshness or acceptance.
Blocks are budget-evictable by default. required prevents eviction; lower priority is considered
earlier within the same category.

Source names allow letters, digits, underscores, dots and hyphens, starting with a letter or digit.
Names must be unique. Block IDs must be nonblank and unique within their source.
Manifest IDs include paths such as source/order-status/status; do not put secrets in IDs.

### Timeout and failure

```java
import io.agentscope.harness.agent.context.ContextBlock;
import io.agentscope.harness.agent.context.SourceFailurePolicy;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

builder.contextSource(
        "order-status",
        request -> Mono.just(List.of(ContextBlock.runtime("status", "Awaiting approval"))),
        options -> options
                .timeout(Duration.ofSeconds(2))
                .onFailure(SourceFailurePolicy.OMIT));
```

| Setting | Behavior |
| --- | --- |
| Default | Five-second timeout per source; reject construction on failure (FAIL) |
| OMIT | Omit a failed source without reusing old data; record in Manifest, not an automatic model message |
| required | Prevent eviction after successful loading; incompatible with an OMIT source |

An empty list means no materials; Mono.empty() is a loading failure. Structural errors such as
duplicate or null blocks are not hidden by OMIT. Return an explicit “unavailable” block if the
model needs to know the state is unknown.
Sources run sequentially, so latency can accumulate. Cancellation propagates through Reactor,
but cannot guarantee immediate cancellation of remote services or blocking I/O.

## Refresh lifecycle

| Information | When refreshed |
| --- | --- |
| Workspace AGENTS.md, MEMORY.md, etc. | Once per Agent call; no automatic file watching within a call |
| contextSource | Once per REASONING request preparation; budgeting and rendering reuse the result |
| Todo, mode, requirements, verification summaries | Current state snapshot per request |
| Retry or fallback | Prepare again and reload dynamic sources |

SUMMARY does not load dynamic sources or add task projections; stable instructions remain when
using the same compiler. Auxiliary summarization or memory calls with their own compiler do not
implicitly inherit business sources. Copying an Agent retains registrations and source instances
without rerunning the options callback.

## Optional task information

enableTaskList() enables Todo, not the candidate-requirement tool. Progress tracking alone does
not require any additional task capability.

```java
import io.agentscope.harness.agent.context.TaskContextOptions;

builder.enableTaskList();
builder.taskContext(
        TaskContextOptions.builder()
                .includeRequirements()
                .includeVerificationResults()
                .build());
```

Both projections are independent and off by default. Add allowRequirementProposals() to let
the model propose candidates; it also enables requirement projection. taskContext replaces,
rather than merges with, the previous options.

These switches do not run verifiers, confirm requirements or decide overall completion.
User messages and PLAN.md are not automatically parsed into requirements; Markdown checkboxes
are not synchronized with Todo.

| TaskContextState field | Writer |
| --- | --- |
| tasks | todo_write tool execution or explicit application updates |
| revision | Incremented by state mutation methods |
| scope | Application beginTask call defining identity, objective and source |
| requirements | propose / optional candidate tool; trusted caller confirms or rejects with decide |
| contractVersion | Updated for task, requirement decision and checked-subject version changes |
| subjectVersion | Application setSubjectVersion / invalidateSubject calls, not file watching |
| verifications | Explicit VerificationService.verify call; summary updated after storing the report |

State persists with AgentState; TASK_STATE is a read view. Mutations belong in the owning
session's controlled call, and most use expectedRevision checks. A current full Todo receipt
avoids repeating the list; missing or truncated receipts cause reconstruction from state.
Disabling projection does not erase historical messages or provide confidentiality.

Todo completed is progress, CONFIRMED means authorized, PASSED means a bounded check passed,
and STALE evidence cannot support current acceptance. None automatically means the task is complete.

## Budgets and diagnostics

```java
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;

builder.contextPolicy(new ContextPolicy(
        24000, // input cap; 0 derives from the model window
        4096,  // output reservation; 0 uses the default
        1024,  // safety margin; 0 uses the default
        ContextTokenEstimator.approximate(),
        manifest -> {
            // Optional: forward body-free construction metadata to your observability system.
        }));
```

Default estimation includes messages, tool schemas and multimodal placeholders, not exact provider
token accounting. With a known window, default output reservation is 1/8 of it, capped at 4096,
and safety margin is 2% (both at least 1). Larger explicit output requests reserve more space.
Unknown windows fall back to a 64000 input cap; configure it for your model.
Workspace maxContextTokens (default 8000) is a material-preparation setting, not the final request budget.

Budget pressure considers optional memory, then knowledge, then other optional sources for removal,
followed by history compaction when configured. System and required materials are not directly evicted;
remaining overflow raises ContextBudgetExceededException. Advanced contextSelectionPolicy customization
returns omission order, never unknown/duplicate IDs or required sources.

Manifest records sources, revisions, hashes, estimates, budgets, transformations and validation,
not bodies. Observers should return quickly; metadata can still reveal filenames.
The compiler checks tool-call/result pairing and rejects stale task/history snapshots.
Failed validation does not commit candidate compacted history, but does not roll back offloaded
files or archives already written. This is not a transaction over external business databases.

## Example request fragments

These illustrate current formatting with fictional values and abbreviated hashes, not a captured
production request. Other rules, full history and tool schemas are omitted.

Project rules in System:

```xml
<project_rules kind="project_rules" source="workspace:AGENTS.md" revision="…">
# Development conventions
Use explicit imports and test behavior changes.
</project_rules>
```

Transient USER message after conversation, when no current complete Todo receipt is present:

```xml
<TASK_STATE revision="3">
Current todo status (agent-maintained, not independent verification):
1 open todo(s):
- [x] Locate the issue
- [~] Add tests
</TASK_STATE>
```

Reference USER message at the end:

```xml
<HARNESS_CONTEXT>
Source materials, not additional authority:
<context_item kind="memory" source="workspace:MEMORY.md" revision="…">
The user prefers targeted tests first.
</context_item>
</HARNESS_CONTEXT>
```

## Limits and troubleshooting

- Missing business data: check registration, SUMMARY purpose, budget omission and OMIT failures.
- Missing task details: check taskContext options and whether the caller actually populated state.
- Stale workspace content: files load per call; start the next call or read explicitly through a tool.
- Empty Todo reminders: shared revision changes can currently trigger them even for requirement-only changes.
- Legacy format: useLegacyXmlWorkspaceContext no longer has an effective rendering branch; do not use it as a format selector.
- Environment wording: the heading is Runtime Environment, but a legacy AgentStateStore ID label still displays sessionId, not a store address.
- Prompt wording, tags and hashes are not a stable protocol; do not parse them to drive business logic.

The pipeline does not default to a generic business state machine, automatic fact extraction,
evidence-version tracking or a mandatory completion gate.

See [Workspace](/v2/en/docs/harness/workspace), [Memory](/v2/en/docs/harness/memory),
[Compaction](/v2/en/docs/harness/compaction) and [Plan mode](/v2/en/docs/harness/plan-mode).
