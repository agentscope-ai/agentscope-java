---
title: Harness Architecture
description: What HarnessAgent is, how its capabilities cooperate, and how state flows
  during a call()
zh_link: /v2/zh/docs/harness/architecture
---

`HarnessAgent` is a thin wrapper around `ReActAgent` that packages the engineering capabilities long-running agents need — workspace-driven persona, long-term memory, subagent orchestration, sandbox isolation, skill composition, plan mode, channel routing — into a single builder.

A bare `ReActAgent` only handles "one request → reason → tool → reply". Harness answers a different set of questions: how does the next turn pick up where the last left off, how does context stay bounded, how do users stay isolated, how do dangerous actions get reviewed, how do reusable capabilities accumulate.

> Installation, dependency, and an end-to-end "first `HarnessAgent`" walkthrough live in [Quickstart](/v2/en/docs/quickstart). This page is architecture only.

## Core working principle

Three things to keep in mind:

**1. Capabilities layer onto the reasoning loop, not into it.**
Workspace injection, compaction, subagents, sandbox, Plan Mode — each hooks into key moments of the ReAct loop. The core algorithm is untouched; Harness only adds.

**2. Capabilities don't depend on each other; they share three objects.**
Each capability does one job and is unaware of the others. They cooperate through:

- **`RuntimeContext`** — who is speaking in this call: `sessionId`, `userId`, plus arbitrary extras. Not persisted.
- **The workspace** — who reads and writes which files. Where they physically land (local disk, sandbox, KV store) is a configuration choice.
- **`AgentStateStore`** — how runtime state is restored across calls.

**3. Built-ins run in a fixed order; your middleware runs first.**
Harness wires its built-in middleware in a fixed order at build time. Anything you add via `.middleware(...)` runs **before** Harness's built-ins.

## Core components

Each capability answers one problem; opt in on the builder.

| Capability | What it solves | Builder hook | Detail |
|---|---|---|---|
| Workspace-driven persona | Persona, knowledge, subagent specs, skills, MCP allowlist all live as files | `.workspace(path)` | [Workspace](/v2/en/docs/harness/workspace) |
| State persistence | Same `(userId, sessionId)` resumes across requests, processes, replicas | on by default; override with `.stateStore(...)` | [Context & AgentState](/v2/en/docs/building-blocks/context) |
| Two-layer long-term memory | Facts in long conversations sediment into `MEMORY.md` | on by default; `.memory(...)` customizes prompts / trigger policy | [Memory](/v2/en/docs/harness/memory) |
| Conversation compaction | History bounded; force-retry on real overflow | `.compaction(...)` | [Compaction](/v2/en/docs/harness/compaction) |
| Large tool-result offloading | >80K-char results moved to disk + placeholder | `.toolResultEviction(...)` | [Compaction](/v2/en/docs/harness/compaction) |
| Subagent orchestration | Delegate to children, sync or background, with auto push-back | `.subagent(...)` or drop spec in `workspace/subagents/` | [Subagent](/v2/en/docs/harness/subagent) |
| Pluggable filesystem | Local + shell / shared store / sandbox without code changes | `.filesystem(...)` | [Filesystem](/v2/en/docs/harness/filesystem) |
| Sandbox isolation | Files and commands isolated; cross-call recovery; multi-replica | `.filesystem(new DockerFilesystemSpec()...)` | [Sandbox](/v2/en/docs/harness/sandbox) |
| Plan Mode | Read-only think-first phase with HITL exit | `.enablePlanMode()` | [Plan Mode](/v2/en/docs/harness/plan-mode) |
| Skill composition | Skills from Git / Nacos / MySQL / classpath / workspace | `.skillRepository(...)` | [Skill](/v2/en/docs/harness/skill) |
| MCP integration & tool allowlist | Declarative MCP servers + allow/deny per tool | `workspace/tools.json` | [Workspace](/v2/en/docs/harness/workspace) |
| Channel routing | Session management, per-session concurrency, multi-agent routing, streaming events | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/en/docs/harness/channel) |

## Persistent Team membership for existing Sessions

`LocalTeamClient.sessionMembership(...)` explicitly enables adoption, binding, queries and safe removal for existing Leader and manually supplied **BYO** member Sessions. A definition's different Sessions may join different Teams; one exact Session may belong to only one Team in the configured relationship domain, including across Team namespaces. This programming API requires a `VersionedBaseStore`; the existing `InMemoryStore` and JDBC `JdbcStore` implement that capability. Other `BaseStore` and `TeamClient` implementations continue to work through their existing APIs.

Create the Team through the existing `createTeam`, then adopt it with an existing persisted Leader Session. Bind only members already declared in that Team's directory. The example uses `.block()` at an application entry point; compose the returned `Mono` operations instead on reactive request paths.

```java
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.util.List;
import java.util.Map;

var stateStore = new InMemoryAgentStateStore();
stateStore.save("alice", "leader-session", "agent_state",
        AgentState.builder().userId("alice").sessionId("leader-session").build());
stateStore.save("alice", "worker-session", "agent_state",
        AgentState.builder().userId("alice").sessionId("worker-session").build());

var client = new LocalTeamClient(new InMemoryStore());
client.createTeam(new TeamCreateSpec(
        "research-address", "my-app", "Research a topic", "leader-agent", "",
        List.of(new TeamMemberSpec("researcher", "research-agent", "", "byo"))))
        .block();

var membership = client.sessionMembership("app-relations", Map.of("sessions", stateStore));
var address = new TeamAddress("my-app", "research-address");
var leader = new MemberSession("lead", "alice", "leader-agent", Role.LEADER,
        new SessionKey("sessions", "alice", "leader-session"));
var worker = new MemberSession("researcher", "alice", "research-agent", Role.WORKER,
        new SessionKey("sessions", "alice", "worker-session"));

var team = membership.adoptTeam("alice", address, "Research", List.of(leader)).block();
var binding = membership.bindMember("alice", address, worker).block();
var current = membership.findMembership(worker.session()).block();
var members = membership.listMemberships("alice", address).block();
boolean removed = membership.unbindMember("alice", address, worker.session(), binding.token()).block();
// The existing worker Session remains; findMembership now completes empty.
boolean stateKept = stateStore.exists("alice", "worker-session");
```

The in-memory example demonstrates the operations without models or Worker execution. For process-restart persistence, configure the existing JDBC `JdbcStore` with a durable database and use the same relationship domain and Session-store domain mapping on every application instance. Membership does not write or delete `AgentStateStore` state; a file or JDBC state store can retain the existing conversations independently.

### Identity and ownership

- `TeamAddress(namespace, teamName)` is the existing storage/routing address. Supply it explicitly; adoption rejects null/blank namespaces instead of silently moving historical records. Nonblank strings are preserved. Logical `teamId` and `displayName` are separate: two Teams may have the same display name when created at distinct addresses. One address can be adopted by only one owner and relationship domain.
- `SessionKey(stateStoreDomain, owner, sessionId)` identifies the actual Session slot. Use the same stable domain ID for the same physical store partition, and different IDs for independent partitions. Assigning two IDs to the same backing store bypasses this identity contract. Provider-specific aliases must also be resolved by the application; Agent references do not establish storage isolation.
- Session owner must equal Team owner. Definition owner is recorded separately and may differ; this trusted application API does not establish definition access or invitation authorization. Session existence means any persisted state, including legacy keys. A Session with no persisted state cannot be registered.
- Null owner supports anonymous Sessions. Blank owners and the reserved `__anon__` literal are rejected by this new API to avoid aliasing existing anonymous state slots. Old APIs retain their accepted inputs. IDs and addresses reject blank values and control characters.

### Queries, retries and compatibility

`getTeam(owner, address)` returns the stable logical ID, display name, original `TeamInfo`, adoption status and current associations. `findMembership(session)` completes empty when unbound; `listMemberships` returns an immutable current list, including the Leader. These are associated Sessions, not the declared member directory or proof that a member is running.

Before writing an adoption marker, adoption ensures an empty owner item can be stored with CAS. A storage failure at this stage leaves the Team unadopted and legacy queries available; the empty item can be retained for a retry. Relationship addresses retain existing short encodings and use fixed-length SHA-256 encodings for long owners or domains, keeping within JDBC key and namespace limits.

Adoption then writes a marker to the existing Team meta and atomically commits initial associations and a receipt in one owner item. A failure between these writes leaves `PENDING`: `getTeam` reports it with no committed members, and both `listMemberships` and legacy `listMembers` fail explicitly. Retry the same complete `adoptTeam` request, including display name and initial members. A conflicting initial request cannot overwrite that marker. Replaying an already committed adoption returns the current associations and does not restore subsequently removed members.

An identical bind retains its token. Unbinding requires the expected token and returns `true` when removed, or `false` when already absent. A wrong Team or stale token fails; an actual unbind followed by a new bind produces a new token. CAS contention retries at most ten times, then raises `TeamConflictException`; storage errors propagate without unconditional-write fallback. If the response fails after a commit, query or retry the identical request to determine the outcome. Cancellation does not roll back a committed association.

For adopted Teams, legacy `listMembers` reads the same associations and projects `sessionId`; an unbound declared member has an empty `sessionId`. The old record shape, declared `isLead`, `phase` and `deployMode` remain unchanged. Existing nonempty legacy Session fields require explicit full identities in the initial adoption request. `completeTeam` still changes only the original phase to `Completed`, preserving associations and the adoption marker. Versioned stores retry completion CAS rather than overwriting a concurrent update; persistent contention can now raise a conflict. Unenabled nonversioned stores retain their completion fallback.

Team metadata, phase and declared definitions remain in their existing records. Only relationship links and adoption receipts are kept in one item per relationship domain and owner; read/write cost and contention grow with that owner's links. All writers to adopted records must honor the versioned capability; direct raw mutation or deletion is unsupported. Malformed or unknown relationship formats fail rather than being reset. Session existence validation and relationship commit are separate operations, so external Session deletion can leave an association that the application must explicitly remove.

Unbinding preserves the Agent definition, Session state and workspace. It does **not** stop runs, revoke tools, update a constructed static `TeamContext`, or unregister `bindSession` notifications. Task boards, mailboxes and static `teamsMode` keep their existing runtime behavior. This capability does not create models, Workers, invitations, schedules or Service Team/Run resources.

## How state flows

Three layers exist; the framework moves data between them automatically.

- **In-call state** — `AgentState` (conversation context, permission rules, Plan Mode state, tool state) plus `RuntimeContext` (`sessionId`, `userId`, sandbox handle, extras).
- **Cross-call state** — auto-saved at the end of every `call()` and auto-loaded on the next: the `AgentState` runtime snapshot in the configured `AgentStateStore` (default `~/.agentscope/state/<agentId>/`, addressed by `(userId, sessionId)`), the never-compacted full conversation log under `sessions/<sessionId>.log.jsonl`, subtask records, and sandbox metadata.
- **Long-term memory** — accumulated across sessions: `memory/YYYY-MM-DD.md` is append-only, periodically merged into `MEMORY.md` by a throttled background job; `MEMORY.md` is injected into the system prompt every reasoning step.

Three invariants worth remembering:

- The system prompt is rebuilt every reasoning step, so edits to `AGENTS.md` or `MEMORY.md` take effect immediately — no restart.
- Compaction, memory distillation, and background maintenance are throttled; they don't run every turn.
- `AgentState` is persisted by core's `ReActAgent` + `AgentStateStore`. Harness no longer adds its own persistence hook.

## Adding your own middleware

To insert custom behaviour without bypassing Harness's plumbing:

- Use `.middleware(...)` — your middleware runs before all Harness built-ins.
- Read `RuntimeContext` from the agent for the current call's identity (`userId` / `sessionId`).
- For workspace I/O, go through `harnessAgent.getWorkspaceManager()` — it routes correctly under sandbox or remote-store modes. `java.nio.Files` writes to the host disk and will land in the wrong place outside local mode.

## Related pages

- [Workspace](/v2/en/docs/harness/workspace) — directory layout, what gets injected into the system prompt, `tools.json`
- [Context & AgentState](/v2/en/docs/building-blocks/context) — `AgentState`, `RuntimeContext`, `AgentStateStore` persistence, multi-user isolation
- [Memory](/v2/en/docs/harness/memory) — two-layer memory
- [Compaction](/v2/en/docs/harness/compaction) — summary compaction, large-result offloading, overflow recovery
- [Filesystem](/v2/en/docs/harness/filesystem) — local + shell / shared store / sandbox
- [Sandbox](/v2/en/docs/harness/sandbox) — isolated execution, cross-call recovery, distributed
- [Subagent](/v2/en/docs/harness/subagent) — declarations, sync/background, streaming forwarding
- [Skill](/v2/en/docs/harness/skill) — four-layer composition, self-learning loop
- [Plan Mode](/v2/en/docs/harness/plan-mode) — read-only phase + HITL exit
- [Channel](/v2/en/docs/harness/channel) — session management, multi-agent routing, streaming SSE
