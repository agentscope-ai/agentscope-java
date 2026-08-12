---
hide-toc: true
---

# AgentScope Service Technical Deep Dive: Control Plane, Data Plane, and Recoverable Agent Runtime

If the launch announcement answers "what AgentScope Service can do," this post focuses on "how it is built." We will walk through the product resource model, plane boundaries, the Turn lifecycle, the Brain / Hands split, the Session event contract, and multi-framework integration paths to explain the system design behind the platform.

For a product overview and capability summary, see the companion post: [AgentScope Service Official Release](./agentscope-service-release.md). This post assumes readers already understand the basics of AgentScope 2.0 / Harness and are interested in scaling a single runnable agent into an operable platform.

## What Is AgentScope Service (Implementation View)

From an implementation perspective, AgentScope Service is not a single process but a set of components with clear boundaries:

| Component | Role |
| --- | --- |
| `service-gateway` | External entry point: authentication, routing, public APIs |
| `aistiod` | Go control plane: product resources, fleet registration, Session / Team runtime state, console backend |
| `service-dataplane` | Java data plane: Managed Session Brain, executes Turns based on AgentScope Harness |
| `service-scheduler` | Channel, Cron, outbound tasks, Self-hosted Hands Worker |
| PostgreSQL | Authoritative state split by schema: `cp` / `rt` / `dp` |
| Web Console | Dashboard · Managed Agents · Agent Teams |

It serves two kinds of workloads at the same time:

1. **Managed Agents**: the control plane holds versioned Snapshots, and the data plane builds `HarnessAgent` from the Snapshot and runs eventized Turns;
2. **BYO Agents**: existing AgentScope / LangChain / Claude and other runtimes connect via extensions, `instrument()`, or Sidecar, entering the same fleet and Session observability model.

The control plane manages desired state and runtime state, but **does not execute model Turns**; the inference loop stays in the data plane or in the connecting party's own Runtime. This boundary runs through the entire architecture: once the control plane starts "running models on the side," plane responsibilities, scaling, and failure domains all become tangled.

For deployment shapes, local development can disable the Kubernetes Reconciler and take the Hosted Product path; production can enable Aistio's CRD / Workload capabilities to connect declarative agents and fleet governance to the cluster. The product brand remains Agent Service, and the underlying control component is `aistiod`.

## Why a Platform Like This Is Needed

Looking at the agent loop alone, today's frameworks are already enough to write demos. The hard part is turning "it runs" into "it can be operated":

1. **Local scripts / CLI**: state lives in local directories, good for individuals, not for multi-replica or audit scenarios.
2. **Embedded SDK inside business services**: every application reimplements SessionStore, HITL, leases, event replay, and permissions on its own, with duplicated costs and divergent standards.
3. **Low-code orchestration**: exposes Harness engineering details to business configurers, making unified platform upgrades difficult.
4. **Single managed runtime**: works well, but cross-framework fleets, customer VPC Hands, and team collaboration state often end up as separate stovepipes.

Another hidden cost is "unclear state ownership." Many people conflate the agent object inside a Java/Python process, the frontend SSE stream, and the chat history in the database. Objects can be discarded, streams can be interrupted; only numbered persisted events and recoverable state storage can support multi-replica, failover, and audit replay. AgentScope Service makes this the default contract rather than leaving it as a per-business choice.

So the platform's solution can be summarized in three sentences:

- **Governance concerns** converge on the control-plane / data-plane contract;
- **Inference kernel** converges on AgentScope Harness;
- **Tool execution boundary** converges on Environment (Hands).

The business side should define only agent differences (prompt, tools, Skills, permission policies); compaction, recovery, events, leases, and approvals evolve as platform capabilities. After the platform upgrades Harness, Managed Agents should benefit collectively without having to redraw each workflow.

## Overall Architecture

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                              Agent Service                                 │
│                                                                            │
│  Web Console ──► Gateway :8080 ──┬──► aistiod :8081 （CP / RT）            │
│                                  └──► dataplane :8082 （DP Brain）         │
│                                              │                             │
│                                              ▼                             │
│                                     PostgreSQL                             │
│                              cp · rt · dp schemas                          │
│                                              ▲                             │
│                                              │                             │
│                                     scheduler :8083                        │
│                              Channel / Cron / Hands Worker                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### Plane Responsibilities

| Plane | Responsible For | Explicitly Not Responsible For |
| --- | --- | --- |
| Gateway | JWT / public routing | Business state, model invocation |
| Control (`aistiod`) | Users, Agent versions, Environment, Session binding, Team, fleet instances, runtime commands | Model Turn |
| Dataplane | Turn Lease, event persistence, SSE, HITL, building Harness from Snapshot, Work Queue | Reading `cp` tables directly as a Catalog fallback |
| Scheduler | Channel, Cron, outbound Hands Worker | Inference loop |

### Data Ownership

The planes may share a single PostgreSQL server, but **they do not share tables**:

| Schema | Owner | Data |
| --- | --- | --- |
| `cp` | `aistiod` product API | Users, Agents, versions, Environment, Session, Vault, Memory, Deployment |
| `rt` | Aistio Runtime Store | Fleet instances, runtime Session, Context, Team, Task, Message |
| `dp` | Java Dataplane | Session Event, coordination state, HITL, Work Item, data-plane projections |

The Dataplane resolves Managed Sessions through the control plane's internal API and builds the runtime only from the returned Agent Snapshot. Data-plane replicas can scale horizontally, but the product Catalog still takes the control plane as the source of truth, avoiding dual writes and cache drift. A local Catalog fallback may look convenient, but in the long run it tends to create ghost bugs where "instance A has been updated, but instance B is still running the old definition."

### A Full Turn Path

1. The client appends `user.message` to an existing Session.
2. The Dataplane acquires the Turn Lease and marks the Session as `running`.
3. The control plane resolves the pinned Agent Snapshot, Environment, Workspace, Memory, and Vault.
4. `SessionTurnRunner` executes `HarnessAgent.streamEvents`.
5. Authoritative events such as `agent.message`, `agent.tool_use`, and `span.model_request_*` are written to PostgreSQL; optional Preview Deltas are used only for typewriter effects and are not persisted.
6. The Session returns to `idle`, pauses for HITL / Tool Result, or terminates with a typed error.

Clients recover from the persisted event sequence through:

```http
GET /api/sessions/{id}/events/stream?after={seq}
```

for incremental resume. The in-process Agent object and the Preview Stream are not authoritative data sources — this is the premise of a recoverable Session.

The point of the Turn Lease is concurrency control: at any moment only one executor should advance the Turn of that Session, avoiding duplicate event sequence numbers, repeated paid model calls, or another replica mistakenly continuing during an HITL wait. The takeover policy after Lease loss / expiration is a key detail of data-plane multi-replica availability.

### Brain and Hands

| Environment | Execution Mode |
| --- | --- |
| `local` | Run filesystem and Shell on the Dataplane host; recommended for development only |
| `sandbox` | Managed cloud sandbox such as E2B |
| `remote` | Remote / distributed filesystem, no local Shell |
| `self_hosted` | Brain exposes only Tool Schema; customer-side outbound Worker poll / ack / heartbeat / return results |

`self_hosted` is suitable for private networks: the Brain does not need inbound access to the customer intranet; instead the Worker actively polls outbound for tool calls. The protocol semantics are a stable `tool_use` / `tool_result` event closed loop — when Hands move elsewhere, the Brain's inference loop does not need to be rewritten.

Security and compliance teams can therefore approve three questions separately: the scope of model context visibility, the reachable network and filesystem for tools, and the data-minimization policy for results returned to the Brain. This is easier to land than treating "the whole agent container" as a single black-box permission object.

## Core Capabilities (Implementation Layer)

> For UI screenshots, see the product post; here we focus on the mechanisms.

### Dashboard: Fleet and Runtime Observability

Dashboard data comes mainly from the control-plane Runtime Store and data-plane event projections, rather than ad-hoc frontend aggregation:

- Agent / Instance health and online inventory
- Session phase (e.g. `active` / `idle` / `compressing` / `archived` / `terminated`) and Turn duration
- Context pressure, Token delta, error counts
- Team member status, task progress, lifecycle events

Two concepts that are easy to confuse need to be separated:

- **Session**: a recoverable conversation thread; `phase` describes the thread's operational state.
- **Turn**: an execution unit from one user request to the response; duration statistics belong to Turn, not to the wall-clock lifetime of a Session mistakenly treated as "active time."

For BYO Agents, the Level-1 Session Snapshot is reported periodically by the adapter; the control plane uses it for fleet statistics and operations (compaction, termination, etc.). Field semantics must be consistent across frameworks, otherwise the Dashboard degenerates into a "metrics wall" where each adapter does its own thing. Token metrics should also prefer delta aggregation rather than simply accumulating absolute snapshots.

### Managed Agents: Versioned Definition + Eventized Session

The product resource model is roughly as follows:

| Resource | Purpose |
| --- | --- |
| Agent | Versioned system prompt, model, tools, MCP, Skill, collaboration config |
| Environment | Tool execution boundary and Sandbox / Worker configuration |
| Session | Stateful binding of Agent version, Environment, Memory, Vault, and event stream |
| Memory / Vault | Cross-Session documents and encrypted credentials |
| Deployment / Channel | Cron, Webhook, manual triggers, and messaging channels |
| Team | Lead / Member, Message, Task, Plan, lifecycle |

A few key design choices:

1. **Session creation is a static binding**  
   Creation records only resource relationships; the Agent is not run until the first `user.message`.

2. **Event-native**  
   Inbound events drive work, and outbound events describe progress and results. Every persisted event has a monotonically increasing sequence number within the Session, so clients can resume from breakpoints.

3. **HITL as a first-class citizen**  
   The Ask Policy tool pauses the Turn and emits a confirmation request; `user.tool_confirmation` continues or rejects, while preserving the full history.

4. **Authoritative events vs Preview**  
   SSE can push `event_start` / `event_delta` for an immediate experience, but the persisted events are final. UI refresh, multi-device recovery, and audit replay all rely on the same source of truth.

5. **Version pin**  
   A Session binds to a specific versioned Snapshot of an Agent to avoid an unreproducible trajectory caused by a hot update mid-run. When an upgrade is needed, explicitly create a new Session or take the product-defined upgrade path.

Managed Agents are built in the Java Dataplane from the control-plane Snapshot. The underlying implementation directly reuses AgentScope 2.0 `HarnessAgent`: context compaction, tool-result eviction, state recovery, Skills / subtasks, and other engineering defaults do not need to be reimplemented as another agent loop at the product layer. What the product layer adds are tenant resources, ACL, event contract, Turn Lease, HITL ticket, Environment, and Worker queue — these are the costs that turn a "framework" into a "platform."

### Agent Teams: Cross-Session Collaboration State Machine

Agent Teams turn multi-agent collaboration into a control-plane resource rather than a temporary chat in some process's memory:

- Lead / Member topology and dynamic membership (count / whitelist constraints)
- Unicast and broadcast messages
- Shared tasks, Claim / Assign, Plan Approval
- Member Wakeup, graceful shutdown, lifecycle deadlines, failure recovery
- Messages and tasks retained across processes and across Sessions

For Managed members, Wakeup can further land in a data-plane Session / Turn; for BYO members, it is coordinated through control-plane commands and adapter capabilities. Team state lives mainly in the `rt` schema, avoiding confusion with the per-Session `dp` event log — Session is responsible for one conversation trajectory, while Team is the persistent unit for cross-member collaboration.

A pragmatic constraint is that Teams do not assume all members come from the same source. The Lead can be a Managed Harness Agent, and a Member can be a connected Coding Agent or LangChain service. The control plane handles topology, tasks, and lifecycle; the member side only needs to satisfy the collaboration and observability contract. Heterogeneous teaming is closer to enterprise reality than "unify the framework first, then talk about collaboration."

## How to Connect

### AgentScope (Native)

The Java side connects through `agentscope-extensions-aistio`. The extension registers the Runtime with the control plane, reports Session / Context / health information, and handles operational commands. For existing AgentScope applications, this is the least invasive and most contract-complete path: it shares the Dashboard and Session observability model with Managed Agents.

Because both sides share the same set of AgentScope event and state semantics, Level-1 / Context / compaction capabilities are usually the first to align. If you are already using `HarnessAgent`, the marginal cost of integration is mainly dependencies, registration config, and runtime identity, not rewriting business prompts.

### LangChain

The Python SDK provides `aistio.instrument()`. For LangChain / LangGraph, the adapter hooks into Callback / Checkpointer interception points:

```python
import aistio

aistio.instrument(
    app_or_client,
    control_plane="aistiod.aistio-system:9090",
    agent_name="my-langchain-agent",
    namespace="default",
    enable_events=False,  # Level 2 events are off by default; enable as needed
)
```

The design principle is out-of-band reporting: the main path succeeds first, and reporting fails silently and degrades to avoid control-plane jitter bringing down business inference. Level-1 is enabled by default to support the fleet view; finer-grained events (Level-2) can be enabled based on cost and compliance requirements. Context reporting uses hash-change debouncing to reduce invalid full-volume pushes.

### Claude SDK and Sidecar

The Claude Agent SDK also uses `instrument()` to obtain Level-1 snapshots and compaction / termination capabilities by decorating paths such as SessionStore.

For Coding Agents such as Claude Code and Qoder where embedding an SDK is inconvenient, a **Sidecar** is used:

- The main container continues to run the original CLI / Agent;
- The Sidecar observes the local Session directory (e.g. `~/.claude/`) and runtime state;
- It reports fleet and Session information to the control plane and forwards supported operational commands;
- When necessary, it synchronizes Session file state to external storage to support cross-node recovery.

The Sidecar is not "reimplementing an agent loop"; it is the minimal observability and operability surface required by the control plane when the binary cannot be changed. It also reminds us: for many Coding Agents, the source of truth is in the filesystem, not the database — the control plane must understand this state shape rather than forcing every Runtime to first migrate to PostgreSQL.

### Local Startup and Validation

```bash
export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
BUILDER_REBUILD=1 scripts/dev-up.sh
# Console: http://localhost:8080
scripts/smoke.sh
```

It is recommended to validate at least three paths:

1. Managed Session: deliver `user.message`, recover from the event stream, and confirm sequence resumption after page refresh;
2. HITL: trigger Ask Policy, continue after confirmation, and verify the history is complete;
3. `self_hosted`: Worker poll / ack / heartbeat / return `tool_result`, and confirm the Turn recovers correctly.

See [`docs/guide/14-validation.md`](../../../agentscope-service/docs/guide/14-validation.md) and the architecture notes in [`docs/guide/02-architecture.md`](../../../agentscope-service/docs/guide/02-architecture.md).

## Implementation Pitfalls Worth Avoiding Early

1. **Treating Preview SSE as the authoritative log**  
   Typewriter effects can be dropped and rebuilt on reconnect; audit, review, and billing should align with persisted event sequence numbers.

2. **Letting the data plane cache product Catalog locally and silently fall back when the control plane is unreachable**  
   It may look highly available in the short term, but in the long term it produces the worst failure: "ran an unknown version." Better to fail observably than to silently use an old definition.

3. **Mixing Session phase with Turn duration**  
   `active` / `idle` describe thread state; duration belongs to Turn. Otherwise Dashboard "who is busiest" will be misled by long-hanging sessions.

4. **Inventing parallel metrics in BYO adapters**  
   Fleet KPIs must share semantics. When adapting a new framework, align the contract first, then consider specialized fields.

5. **Stuffing Team messages into a member's Session event stream to fake collaboration state**  
   Session trajectory and Team state-machine lifecycles differ; mixing them causes recovery, cleanup, and permission boundaries to all break.

These points are not obvious in monolithic demos, but once multi-replica, multi-framework, and multi-team scenarios appear together, they become breeding grounds for production incidents.

## Roadmap (Engineering View)

1. **Adapter coverage**  
   Deepen Level-1 / Level-2 / Context alignment for LangChain, ADK, Claude, Qoder, OpenAI Agents, and others, reduce framework-specific fields, and ensure Dashboard KPIs mean the same thing everywhere.

2. **Production-grade multi-tenancy and governance**  
   ACL, quotas, audit, canary release, key rotation, and stricter Environment isolation policies; Vault / Memory lifecycle and access boundaries will also continue to be refined.

3. **Automation**  
   Evolve Deployment / Cron / Webhook / Channel from "can trigger" to "orchestratable, replayable, compensable." When an automated Turn fails, there must be typed errors, retry policies, and a human takeover entry.

4. **Event-driven entry points**  
   GitHub / GitLab, DingTalk, WeCom, etc.: stably map external events to Session Turns or Team Tasks while preserving idempotency and authentication boundaries. Retries from external systems are normal; the platform must prevent duplicate work.

5. **Teams and recovery**  
   Dynamic membership, plan approval, member-disconnection recovery, cross-Session restart, and consistency of mixed Managed / BYO teams. The collaboration state machine is harder than a single Session because the failure domain spans multiple Runtimes.

## What Is Different from "Just Embedding Harness"

If your business service directly embeds `HarnessAgent`, you already have solid long-task and compaction capabilities. But once you face multi-tenant, multi-replica, multi-team scenarios, you still need to add:

- Versioned Agent definitions and Session pin;
- Append-only events and cursor-based resume;
- Turn Lease and HITL ticket;
- Environment switching and Self-hosted Work Queue;
- Fleet registration, context pressure, compaction / termination commands;
- Team task board and cross-Session collaboration state.

AgentScope Service is not "adding a UI layer around Harness"; it turns these distributed responsibilities into stable product resources and internal contracts. Harness lets the platform avoid rewriting the agent loop; the platform layer is still responsible for state ownership, failure domains, and governance boundaries.

## Closing

The technical kernel of AgentScope Service can be summarized in three sentences:

1. **The control plane manages desired and runtime state, the data plane runs Turns, and Hands decides where tools land**;
2. **The persisted event sequence is the source of truth for Session; in-process objects are only disposable caches**;
3. **Managed and BYO share the fleet contract; framework differences converge in adapters, not scattered across the Console**.

If you are moving from "a single Harness Agent" to "an operable agent fleet," this layering eliminates a lot of duplicated infrastructure. You are welcome to read [`agentscope-service/README.md`](../../../agentscope-service/README.md) directly; for product capabilities and onboarding stories, return to the [release post](./agentscope-service-release.md).
