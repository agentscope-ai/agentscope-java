---
hide-toc: true
---

# AgentScope Builder — Turning OpenClaw's "Self-Evolution" into a Team-Wide Platform

In AgentScope Java 1.1.0, we distilled the "workspace as truth + self-evolution" experience from OpenClaw and the Coding Agent into a Harness engineering foundation: `HarnessAgent` + `AbstractFilesystem` + built-in compaction and layered memory. At the time, we made a promise: **write agent logic once and switch deployment shapes on demand — from a personal laptop all the way to enterprise distributed deployments**.

`HarnessAgent` attracted a lot of attention, and many developers asked for a real-world application. Today we are releasing both AgentScope Claw and AgentScope Builder. They are shipping products and concrete case studies of AgentScope Harness:

- **agentscope-claw** — the full realization of Harness on the "single-user local" end. **Using AgentScope Harness, we have built a Java version of OpenClaw**.
- **agentscope-builder** — the full realization of Harness on the "multi-user enterprise" end, released today. **Builder can be understood as the distributed version of OpenClaw**: on one platform, an entire team can develop, operate, and share self-evolving agents.

We will first explain AgentScope Claw in depth — because Builder did not appear out of thin air; it was born from the enterprise requirements that Claw could not solve.

---

## AgentScope Claw — Using Harness to Build an OpenClaw

### What It Is

AgentScope Claw is a lightweight Java version of [OpenClaw]: **a personal assistant that runs on your own computer**. It acts as you, works inside your file system and shell, and slowly "grows" with use — the skills it learns, the subagents it spawns, and the memory it accumulates are all files it writes into its workspace.

Claw lives in the repository at:

```
agentscope-examples/agents/agentscope-claw/
```

It is not sample code; it is a **complete Spring Boot application**: JDK 17, one `mvn package`, one `java -jar`, then open <http://localhost:8080> in a browser. All state is persisted under the `~/.agentscope/` workspace, which can be overridden with the `CLAW_HOME` environment variable. On first launch it auto-creates a built-in `default` agent, so you can start chatting without writing any code.

### Three Core Capabilities

What makes Claw interesting is not "it can chat," but the following three things — and they are the first complete expression of Harness's design promise in a real product:

**1. Workspace-driven self-evolution**

All of Claw's state lives not in a database, but under `~/.agentscope/claw/workspace/`:

- `AGENTS.md` — agent persona and behavioral contract, automatically injected as system prompt before each inference
- `skills/` — reusable skills that the agent writes and uses itself
- `subagents/` — subagent spec declarations, auto-discovered and loaded
- `MEMORY.md` + `memory/YYYY-MM-DD.md` — layered memory, automatically maintained by a background LLM
- `agents/<subId>/sessions/` — full conversation logs (JSONL) and compacted context

After every conversation, new facts are distilled and appended to the daily memory ledger, and a background scheduler periodically merges them into `MEMORY.md`. **Tweaking an agent's persona, knowledge, or skills does not require code changes — just edit the files in the workspace. Changing a file is equivalent to upgrading the agent**. OpenClaw already did this; now Java can do it too, backed by the AgentScope Harness runtime.

**2. Direct access to your local file system and shell**

Claw uses the `LocalFilesystemWithShell` backend — no sandbox, no remote server. All reads, writes, and commands hit the local OS directly. On your own machine this is not a bug; it is a feature: ask it to "move files older than three months in `~/Downloads` to the archive directory," and it can actually do it, because it has a shell.

Harness registers tools conditionally based on backend capabilities — in Claw's local mode, the `execute` shell tool automatically appears in the agent's toolset; switch to an untrusted environment (such as Builder's remote mode, described later), and the same shell tool automatically disappears from the same agent code. **This is the first concrete demonstration of "the same agent logic, different shapes"**.

**3. Direct integration with the apps you already use**

Claw ships with six built-in channels out of the box:

| `type` | transport | description |
| --- | --- | --- |
| `chatui` | in-process | default local Web UI |
| `dingtalk` | Stream (WebSocket) | DingTalk internal app, no public port required |
| `wecom` | HTTP callback + REST API | self-hosted WeCom app |
| `feishu` | HTTP event callback + REST API | Feishu custom app + event subscription |
| `github` | Webhook + REST API | listen for issue / PR review comment events |
| `gitlab` | Webhook + REST API | listen for Issue / MR Note Hook |

That means you can @claw from a DingTalk DM or a GitHub issue comment, and it responds with the full workspace context. During bootstrap, each agent also registers the `outbound_send` tool, allowing it to **proactively** send messages to any channel — after a subtask finishes, `HarnessGateway.tryDispatchAnnounce` automatically reuses the inbound address so completion notifications naturally return to the DingTalk or WeCom session that triggered them.

The channel layer also ships with a default set of reliability mechanisms — idempotent deduplication, bot-loop protection, WeCom signature verification, AES-256-CBC decryption, and access-token renewal. These are the "write it wrong and it breaks" details of enterprise IM integration that the framework already handles.

### Claw's Boundaries

Claw intentionally stays simple — **no login, no multi-tenant isolation, no Docker sandbox, no horizontal scaling**. It deliberately avoids doing more, because those things would break the "install and run" experience.

But as soon as you try to put this inside a team, problems appear one after another: how do multiple people share the same process? How do self-evolved workspaces stay isolated per user? How does a user's memory remain consistent across nodes in a multi-replica deployment? How do you safely run user-submitted code? How do you share a good agent with colleagues without letting them break it?

Each of these five problems is small on its own, but together they mean **Claw must be repackaged into a different container**. That is the starting point for Builder.

---

## From Claw to Builder — The Enterprise Deployment Shape of OpenClaw

Claw assumes "one machine, one user, one workspace." Applying that assumption directly to a team breaks in five places at once — and none of them can be fixed by "running a few more Claw processes":

1. **Multiple people share one process, but each needs their own view.** Claw only recognizes the current local user. Multi-user login, token-based authentication, and per-user session separation are all outside Claw's scope.
2. **Each user's workspace must not pollute others.** A side effect of agent self-evolution is that it **writes files** — learned skills, generated subagents, accumulated `MEMORY.md`. Alice's tuned agent must not let Bob see things he should not see, nor let Bob's conversation overwrite Alice's memory. But Claw uses a single global workspace.
3. **In a multi-replica deployment, the same user must see a consistent workspace.** Running two Claw processes on two machines means two isolated local disks; the same user's request landing on different replicas sees two different memories.
4. **Running user-submitted code on the server requires OS-level isolation.** Claw enables local shell by default — a core experience on your own machine, but a direct attack surface in a multi-tenant service.
5. **Good agents need to be shareable without being breakable.** What teams need is not "export the whole thing and let someone else import it," but fine-grained "I authorize a group to use it, but they cannot modify it."

These five problems boil down to one thing: **"one user, one machine, one workspace" must become "many users, many machines, multiple namespace-isolated workspaces."** This cannot be solved by patching Claw — it requires building a multi-tenant, distributed isolation layer on top of Harness's workspace abstraction.

---

## Builder Product Position 1 — A Multi-Tenant, Distributed OpenClaw

Builder packages Claw's core experience into a **team- and enterprise-oriented** web platform. One-sentence positioning:

> **Builder is the distributed version of OpenClaw** — same self-evolution, same workspace-driven design, same Harness runtime; only scaled from "one person" to "one organization," and from "one laptop" to "a set of horizontally scalable services."

As a platform product, its core capabilities are:
1. A multi-tenant, distributed version of OpenClaw that supports many users on one platform, with each user's agent isolated, supports multi-replica deployment, and keeps user workspaces consistent across nodes;
2. A no-code agent development platform where users can create, tune, and share their agents from the Web UI without writing a single line of code, with all agent state persisted in the workspace to automatically drive self-evolution.

## Builder Product Position 2 — A No-Code Agent Development Platform

Over the past year, platforms like LangSmith Fleet, Coze, and Dify sparked a wave of "no-code agent building" — users can assemble a working agent in the browser without writing code. The core experience is consistent: **choose template → configure parameters → connect tools → publish**, lowering the entry barrier for agent development.

Builder does the same: **users log in from the browser and build their own agents without writing code**. In the UI, pick a template (or start from a blank scaffold), choose a model, write a system prompt, check skills / subagents / tools / MCP services, save, and start chatting — low barrier, fast onboarding, and WYSIWYG, just like other no-code platforms.

### The Real Difference: Creation Is Just the Starting Point; the Agent Keeps Evolving

Most no-code platforms produce a **static agent** — you configure what it can do, and it does only those things forever. If you want it to do something new, you return to the admin panel and manually change the configuration. The agent's capability ceiling equals everything you thought of at creation time.

Builder is different. Every agent has a continuously growing workspace behind it:

- **Automatic memory accumulation**: after each conversation, the agent distills new facts and writes them to memory. Next time it knows you and your business better than before. You do not need to manually update its knowledge base — it grows on its own.
- **Automatic skill acquisition**: while completing tasks, if the agent notices a reusable workflow, it can structure it into a new skill under the `skills/` directory. Next time it faces a similar scenario, it calls the learned skill instead of reasoning from scratch.
- **Automatic subagent spawning**: when a class of subtasks keeps recurring, the agent can split it out into a dedicated subagent (written to `subagents/`), then delegate to it directly instead of doing it itself.

These three things **do not require the user to return to the admin panel** — the agent writes files in the workspace, and the workspace evolves continuously during conversation. **What you configure is its starting point, not its ceiling**.

Of course, "automatic evolution" does not mean total freedom. Everything in the workspace is **files: editable, versionable** — users can edit `AGENTS.md` (persona) in the UI, manage `skills/` (add/remove skills), feed `knowledge/` (domain knowledge), and review `MEMORY.md` (correct memory). "No-code" does not mean "zero control"; it means "you do not need code to control the agent, but you can always control it through files."

### Comparison with Platforms Like LangSmith Fleet

| | Typical no-code platform (Fleet / Coze / Dify) | AgentScope Builder |
|---|---|---|
| **Agent capability ceiling** | Whatever was configured at creation | Creation is the starting point; capabilities **grow continuously** |
| **Memory and skills** | Short-term memory + manually maintained tool connectors | Layered automatic memory + agent can acquire skills autonomously |
| **State carrier** | Database / internal structure (not directly user-editable) | **Workspace files** — readable, editable, Git-versionable |
| **Offline migration** | Platform-bound | Workspace subtree = standard files; copy them out and use them |

**One-sentence summary**: Builder is a "no-code + self-evolving" agent platform — the barrier to entry is as low as Fleet / Coze / Dify, but **what you create is not a static tool; it is a digital assistant that grows**.

---

## Builder's Core Mechanism: CompositeFilesystem

If Builder's implementation had to be explained in one sentence, it would be:

> **Builder runs every agent on top of `HarnessAgent` + `CompositeFilesystem` — the former handles the agent's runtime orchestration, while the latter turns the workspace into an asset that is namespace-isolated, distributable, and projectable into a sandbox.**

Let us follow a concrete request to unpack these two pieces.

### HarnessGateway — One Independent Runtime per (User, Agent)

Beneath the web layer sits a **HarnessGateway**. Its job is simple: route each `(userId, agentId)` pair to an independent `HarnessAgent` instance.

- Alice calls `agent-A` → lands on `Agent(alice, agent-A)`
- Alice calls `agent-B` → lands on `Agent(alice, agent-B)`
- Bob calls the same `agent-A` → lands on `Agent(bob, agent-A)` — completely independent from Alice's

Each `HarnessAgent` instance receives a `CompositeFilesystem` bound to that user and that agent's namespace. In other words — **HarnessGateway does not care how isolation is implemented; it only "gives the right request to the right agent instance"**; the real isolation work happens in the next layer.

### CompositeFilesystem — Turning Workspaces into Isolated Assets

`CompositeFilesystem` is the key that makes all of Builder work. Its name is literal — **it is a composite file system**:

```
┌──────────────────────────────────────────────────┐
│  CompositeFilesystem                             │
│                                                  │
│  ┌───────────────────────────────────────────┐   │
│  │  Layer 1: Namespace routing                │  │
│  │    transparently rewrites all paths to    │  │
│  │    users/{userId}/agents/{agentId}/...    │  │
│  └───────────────────┬───────────────────────┘   │
│                      ▼                           │
│  ┌───────────────────────────────────────────┐   │
│  │  Layer 2: Storage backend                  │  │
│  │    local disk / Docker container /        │  │
│  │    remote KV — choose one                 │  │
│  └───────────────────────────────────────────┘   │
└──────────────────────────────────────────────────┘
```

- **Top layer is namespace routing**: when the agent calls `read("AGENTS.md")`, CompositeFilesystem reads `(userId, agentId)` from the current `RuntimeContext` and transparently rewrites the path to `users/{userId}/agents/{agentId}/AGENTS.md`. The agent code thinks it is operating on "a whole file system," but **what it actually sees is a namespace-trimmed subtree that belongs only to it**.
- **Bottom layer is the physical storage backend**: after namespace routing, where the data actually lands depends on the backend implementation. The default is host disk via `LocalFilesystemWithShell`, with paths resolved to `~/.agentscope/builder/workspace/users/{userId}/agents/{agentId}/...`.

The key point: **agent code has no idea either layer exists**. It still uses Harness's uniform `read / write / ls / grep` API, exactly as in Claw. Isolation is implemented inside CompositeFilesystem, not by business code "carefully avoiding other people's directories."

### End-to-End Walkthrough of a Write

To make this abstraction concrete — when Alice asks her `agent-A` in the UI to learn a new skill (writing to `skills/sql-helper/SKILL.md`), the full call chain is:

1. **Web layer**: JWT parsing yields `userId=alice`, URL path parsing yields `agentId=agent-A`, both are attached to `RuntimeContext`.
2. **HarnessGateway**: routes to the `Agent(alice, agent-A)` `HarnessAgent` instance.
3. **Agent inference**: the model decides to call the `write_file("skills/sql-helper/SKILL.md", ...)` tool.
4. **CompositeFilesystem top layer**: intercepts the call, reads `(alice, agent-A)` from `RuntimeContext`, and rewrites the path to `users/alice/agents/agent-A/skills/sql-helper/SKILL.md`.
5. **CompositeFilesystem bottom layer**: default local storage appends this relative path to `~/.agentscope/builder/workspace/`, ultimately writing to disk at `~/.agentscope/builder/workspace/users/alice/agents/agent-A/skills/sql-helper/SKILL.md`.
6. **Next inference**: when the next conversation starts, `WorkspaceContextHook` injects the system prompt and also reads `skills/` through CompositeFilesystem, automatically locating Alice/agent-A's own subtree; the newly learned skill appears in the toolset.

When Bob's `agent-A` does the same thing, the path is rewritten to `users/bob/agents/agent-A/...` — physically a completely different directory tree from Alice's. **There is no business code that asks "is this Alice or Bob"; isolation emerges from the abstraction layer**.

### When You Need Stronger Isolation: Add a Sandbox on Top of the Same Composite

In Claw's local mode, shell commands hit the host directly. Builder's default local mode inherits this — suitable for trusted teams and single-node deployments. But as soon as your scenario involves **untrusted code entering the agent** (for example, letting the agent run user-submitted SQL, Python, or shell scripts), the host can no longer take that hit directly.

Builder does not "rebuild a sandbox solution" for this scenario; instead, it **adds a projection layer on top of CompositeFilesystem**:

- After entering sandbox mode, the runtime moves entirely inside a Docker container
- The host side remains the "master" of the workspace; CompositeFilesystem projects key files such as `AGENTS.md`, `skills/`, `subagents/`, and `knowledge/` from the host into `/workspace` inside the container
- The agent inside the container sees the exact same workbench as the host — it reads the same `AGENTS.md` and uses the same set of skills
- Shell commands execute inside the container; the host process is never directly affected by any user input

Note — **this is not "another file system"**; it is the same CompositeFilesystem with an extra "host ↔ container" physical mapping in the bottom layer. Agent code, workspace directory structure, and UI experience remain unchanged; only the boundary where shell commands land moves to the container wall.

Sandbox isolation granularity — one container per session, per user, per agent, or globally shared — is a real deployment decision that can be configured per business scenario; the default `USER` (one container per user, shared across multiple sessions) is a reasonable starting point for most multi-tenant SaaS.

### When One Machine Is Not Enough: Swap the Storage Layer for a Distributed Backend

So far Builder is still single-node — workspaces live on one machine's local disk (or in Docker containers on that machine). Once you want to make Builder multi-replica, with user requests landing on any node and seeing consistent state, the problem returns to the "how do replicas share workspaces" question from the beginning.

CompositeFilesystem's solution is direct: **swap the bottom storage backend from "local disk" to "distributed KV."** Builder abstracts the `BaseStore` interface; implementations can be Redis, object storage (OSS / S3), or your own KV service. After swapping:

- All agent runtime reads and writes go through `RemoteFilesystem` and land in `BaseStore`
- The web layer managing user workspaces also uses the same `BaseStore` — what the web sees and what the agent sees is the same data
- Combined with a distributed `Session` (typical implementation: `RedisSession`), Builder processes themselves can be deployed as equal replicas

The "namespace-routing top layer" in the diagram does not change at all — namespace routing is done inside CompositeFilesystem, and the storage backend, whether local disk, Docker container, or Redis, knows nothing about it. **This is exactly where the `AbstractFilesystem` from the [Harness article](agentscope-v1-harness.md) shows its real power** — business code does not change a single line; the deployment side swaps a Bean and the migration from single-node to distributed is complete.

---

## Builder Architecture at a Glance

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder (Spring Boot, port 8080)                        │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  HarnessGateway                                              │  │
│   │   ├─ Agent (alice, agent-A) ──┐                              │  │
│   │   ├─ Agent (alice, agent-B)   │ one HarnessAgent per (user,id)│  │
│   │   └─ Agent (bob,   agent-A) ──┘                              │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  CompositeFilesystem                                         │  │
│   │   ├─ Top: namespace routing  (userId, agentId) → subtree     │  │
│   │   └─ Bottom: physical storage backend                        │  │
│   │         · Default: local disk                                │  │
│   │         · Sandbox mode: host ⇄ container projection          │  │
│   │         · Distributed: BaseStore (Redis / OSS / custom)      │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│   User and agent metadata (default H2; production can use          │
│   MySQL / PostgreSQL)                                               │
└─────────────────────────────────────────────────────────────────────┘
```

The only truly "new" thing in the whole diagram is the top row — React SPA + JWT REST API + HarnessGateway routing. The middle and bottom layers are just direct combinations of Harness's `HarnessAgent` and `AbstractFilesystem`.

This is Builder's design philosophy: **do not reinvent the agent runtime; only add the operational shell required to run it in a multi-tenant enterprise environment**.

---

## Quick Start

### Claw

```bash
# 1. Set the model API key (defaults to DashScope)
export DASHSCOPE_API_KEY=sk-xxx

# 2. Build and run
mvn -pl agentscope-examples/agents/agentscope-claw -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-claw/target/agentscope-claw-*.jar
```

Open <http://localhost:8080>. The default home directory is `~/.agentscope`. To connect DingTalk / WeCom / Feishu / other channels, edit `~/.agentscope/agentscope.json` and add the corresponding channel entries. See the [Claw README] for details.

### Builder

```bash
export DASHSCOPE_API_KEY=sk-xxx

mvn -pl agentscope-examples/agents/agentscope-builder -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-builder/target/agentscope-builder-*.jar
```

The service starts on port 8080. Log in with `admin/admin`, `bob/bob`, or `alice/alice` to access the full UI. For production deployment (database switch, sandbox image, distributed backend), see the [Builder README].

[Claw README]: https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agents/agentscope-paw
[Builder README]: https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service

---

## Claw vs Builder — Which One to Choose

| | Claw | Builder |
|---|---|---|
| **Use case** | Personal assistant on your own laptop / workstation | A team or company building and operating self-evolving agents together |
| **Users** | 1 person | Multiple people — each logged-in user has their own workspace |
| **Entry point** | Web UI + DingTalk / WeCom / Feishu / GitHub / GitLab | React SPA + JWT REST API |
| **Isolation** | None — runs directly as you | `(userId, agentId)` namespace; optional Docker sandbox |
| **Sharing** | None — one machine, one person | run / edit / fork three-tier permission levels |
| **Distributed** | Single process, single node | Switch to BaseStore backend to scale horizontally |
| **Filesystem** | `LocalFilesystemWithShell` | `CompositeFilesystem` |

**The two paths are not mutually exclusive** — Harness workspaces are files; the entire `AGENTS.md / skills/ / subagents/` subtree is an asset that can be versioned, code-reviewed, and copied directly from Claw into Builder. A common workflow: a developer tunes an agent to their satisfaction on their own machine with Claw, submits the workspace directory as a template to the repository, and operations pushes it to the whole team via Builder.

---

## Summary

In the [Harness article](agentscope-v1-harness.md), we delivered the "self-evolving agent runtime" — `HarnessAgent` + workspace conventions + pluggable filesystem + hook pipeline.

Today's article turns that runtime into **two directly runnable products**:

- **Claw proves**: using AgentScope Harness, we can already build a complete Java version of OpenClaw — self-evolution, local shell, five IM channel integrations, all packaged into a Spring Boot application you can run with `mvn package`.
- **Builder proves**: the same Harness runtime, plus an operational shell for "workspace isolation and sharing," directly evolves into a multi-tenant platform for teams. From Claw to Builder, **not a single line of agent business logic changed**; only which layer CompositeFilesystem provides isolation in, and which medium it persists data to, changed.

This is exactly what Harness promised from the start: **write agent logic once and switch deployment shapes on demand**. From today, that promise has two running proofs in the repository.

If you need a personal assistant, start with the [Claw README] quick start; if you need a team / company platform, start with the [Builder README]. Both paths converge on the same Harness — and that is why we built this.
