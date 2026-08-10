---
hide-toc: true
---

# Coding Agent: The Second Half — From Personal Productivity to Organizational Engineering Systems

Developers still hand-writing code the old-fashioned way are practically training to become intangible-cultural-heritage inheritors; the vast majority are already using Coding Agents like Claude Code and Cursor. The direction is right, but the scenario is different, so the solution is different too—installing an AI assistant locally for personal productivity and building an AI-driven engineering collaboration system inside an organization are two entirely different dimensions. The former already has mature products; the latter is just getting started. This post is about the latter.

---

## You're Not the Only One Thinking About This

Between late 2025 and early 2026, something interesting happened: Stripe, Ramp, and Coinbase all publicly revealed their internal Coding Agents—Stripe calls its [Minions](https://stripe.com/engineering), Ramp calls its Inspect, and Coinbase calls its Cloudbot. The three companies built them independently, without cross-reference, yet converged on almost the same architecture.

This is no coincidence. When you upgrade a Coding Agent from "one person using it in a terminal" to "the whole team triggering it anytime via Slack / GitHub Issue," you are forced onto the same path by the same set of engineering problems. The LangChain team saw this pattern and released [Open SWE](https://github.com/langchain-ai/open-swe) in March 2026—distilling the common pattern of Stripe/Ramp/Coinbase into an open-source framework. The opening of the Open SWE README is direct:

> Elite engineering orgs like Stripe, Ramp, and Coinbase are building their own internal coding agents — Slackbots, CLIs, and web apps that meet engineers where they already work.

"Meet engineers where they already work"—it is not about making engineers learn a new tool, but about letting the agent slip into the Slack channels, GitHub Issues, and IM conversations engineers already use, becoming part of the team's workflow.

We took the same path when building AgentScope Harness. This post uses the official example `agentscope-codingagent` as a thread to explain what walls a production-grade Coding Agent hits during real-world deployment, and how we climbed over them.

---

## Two Dimensions of Coding Agent

First, let's clarify the positioning. Claude Code optimizes **"I write code faster on my own"**—you type, it works, you watch it work, and you interrupt and correct it at any time. The state lives on your local machine, the trigger is yourself, and the trust boundary is simply that you trust your own machine.

What we are building solves a different problem: **"For some small task on the team, I don't even have to watch it myself—I just throw it to the agent, let it run, and review the PR it opens."** The trigger could be any Issue commenter; the agent runs remotely for ten minutes to an hour with no one watching. Stripe engineers @Minions in Slack with "fix this bug for me" and later receive a draft PR—that is what an organizational Coding Agent should look like.

The two forms overlap in functionality—they can all write code, run commands, and edit files—but their engineering constraints are completely different. Claude Code is your own private car: you trust the driver (yourself), so you need little protection beyond airbags. An organizational Coding Agent is a fleet vehicle for a taxi company—the passenger is not the owner, the driving happens remotely, and you need dashcams, GPS tracking, mileage limits, emergency braking, and the guarantee that one broken car does not take down the whole fleet.

Open SWE sums up this philosophy in one sentence: **"Isolate first, then give full permissions inside the boundary."** Isolate first, then delegate authority.

In fact, vendors are moving in this direction too. The GitHub Copilot Coding Agent can already be triggered by an assignment on an Issue and automatically open a draft PR after running in the cloud; Claude Code also has a headless mode that can be invoked programmatically in CI. The philosophy is essentially the same—sandbox isolation, asynchronous triggers, PR-driven output—vendors are productizing the patterns proven by leading companies and packaging them as ready-to-use SaaS services. Stripe, Ramp, and Coinbase choose to build in-house mainly because of the specifics of their own engineering system: deep integration with internal systems, data compliance requirements, and the degree of workflow customization. The two paths are not contradictory; which is more suitable depends on the organization's own constraints and needs.

What AgentScope Harness aims to do is abstract the common engineering problems along this path into composable foundational capabilities, so teams that choose to build in-house do not have to start from scratch.

---

## Get It Running First

The fastest way to try it out—one environment variable and one Maven command, and you have an interactive REPL running locally. No Docker, no webhook, no GitHub App.

```bash
export DASHSCOPE_API_KEY=sk-...
cd agentscope-java
mvn install -pl agentscope-examples/agents/agentscope-codingagent -am -DskipTests -q
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

After startup you reach the `You>` prompt, and the agent works in its own workspace `~/.agentscope/codingagent/workspace/`. With nothing configured, you already get a full workspace, session persistence, and long-term memory.

```
You> write hello.txt with a haiku about Java
You> clone https://github.com/owner/repo into the workspace and tell me what it does
You> review https://github.com/owner/repo/pull/42
```

At this point, the local demo works. But the distance between a demo and production is far greater than most people think.

---

## Wall 1: Execution Isolation

You give the agent an `execute` tool so it can run shell commands. On day one it is exciting—it can `git clone`, run `mvn test`, and get the whole build through on its own. On day two you realize a problem: **the trigger is no longer you.** Any GitHub Issue commenter or any Slack user can make the agent run code, and your host is directly exposed to commands decided by the model.

This is the first wall every team building an organizational Coding Agent hits. Coinbase solves it with self-built sandbox infrastructure, Ramp with Modal's cloud containers, and Open SWE with an abstraction supporting multiple backends such as Modal, Daytona, and Runloop. We made the same abstraction—`FilesystemSpec` is the unified interface, and Docker containers, remote KV, and local filesystem are pluggable implementations. Take Docker as an example:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("coding")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("agentscope/coding-sandbox:latest")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

With this one line, all built-in tools such as `read_file`, `write_file`, and `execute` automatically switch to the sandbox backend, and the agent code does not need to change at all. `IsolationScope.SESSION` ensures each GitHub Issue / PR / IM conversation runs in its own space.

The sandbox solves "do not harm the host." But the next problem arrives immediately.

---

## Wall 2: State Continuity Breaks

A user adds another comment on the PR: "add one more test." The agent has to continue from the previous environment—waiting five minutes to re-`git clone` + `npm install` is unbearable.

Open SWE solves this with a "persistent sandbox"—follow-up messages in the same thread reuse the same sandbox. Our solution is more fine-grained: at the end of each `call()`, the sandbox packages the workspace state into a snapshot and restores it on demand next time. If the container is still there, it continues directly; if the container is gone, a new one is started from the snapshot; if there is no snapshot, a full initialization is performed. Snapshot backends can be local files, OSS, or Redis; in production, add one line of configuration:

```java
.snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/"))
```

It is not only the sandbox state that needs to carry over. **Conversation history, compressed summaries, Plan state, todo lists, permission rules—the entire AgentState is automatically persisted at the end of each `call()` and automatically loaded on the next `call()` with the same `(userId, sessionId)`.** By default it is stored in local files; for multi-replica production, switching to Redis takes one line. Once switched to Redis, if a node crashes the session floats to another node, rolling releases automatically recover on new pods, and you can even switch from a GitHub Issue conversation to DingTalk halfway through—as long as the `sessionId` matches, the memory stays.

---

## Wall 3: Context Explodes

A long Issue runs dozens of conversation rounds, `git diff` outputs tens of thousands of characters, and `mvn test` logs run to tens of kilobytes—the model's context window fills up quickly. This is a problem every team building long-running-task Coding Agents hits. The Deep Agents framework underneath Open SWE uses file-based memory for offloading, writing large results to files instead of leaving them in the conversation history.

Our solution is four independent, composable mechanisms: **conversation summarization** triggers automatically when the message count grows too high, keeping the tail verbatim and compressing the earlier part into a summary; **large tool-result offloading** writes overly long outputs to workspace files, leaving only about 2K at the head and tail plus a `read_file` path hint in the context; **argument truncation** also truncates large arguments to `write_file`; and **overflow fallback** performs emergency compression and retries when `context_length_exceeded` is actually hit.

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(50).keepMessages(20)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000).build())
    .build())
.toolResultEviction(ToolResultEvictionConfig.defaults())
```

**This is not optional.** Coding Agents will definitely run long sessions and definitely produce large diffs; not enabling these two will eventually hit the wall.

At the same time, `MEMORY.md` periodically merges long-term facts from the daily conversation churn. After running for a while, the agent learns the team's rules on its own—"the test command for this repo is `mvn -pl module test`; don't use `mvn test` at the root because it is too slow"—and will not need to ask again next time.

---

## Wall 4: Multiple People Use It at the Same Time

The first three walls—isolation, state continuity, and context management—solve the problem of "one agent session can run stably." But an organizational service is multi-tenant from day one: dozens of Issues, dozens of PRs, and dozens of IM conversations running at the same time, each with its own code repository, dependency directory, conversation history, and long-term memory, **and they must never cross over**.

Only then do you truly understand why Open SWE's README lists "Multiple tasks run in parallel — each in its own sandbox, no queuing" as a core feature. It is not showing off; it is a hard requirement.

We use `IsolationScope` to control the isolation granularity. `SESSION` gives each sessionId its own sandbox; `USER` lets multiple conversations from the same user share one repo clone. Isolation is not only at the sandbox layer—session state, memory, and subagent tasks all follow the same granularity, so developers do not have to worry about it themselves.

Concurrency control also lives at this layer: `RunDispatcher` + `MessageQueueHook` enforce that only one inference runs in the same thread at a time. When a user adds another comment while the agent is running, the new message does not interrupt the current inference; instead, it is enqueued and injected before the next round—same idea as Open SWE's `check_message_queue_before_model` middleware. `ThreadBudgetHook` caps the model-call budget per thread, and `ModelCallLimitHook` caps it globally—**one user's runaway loop must not burn through the whole company's quota**.

---

## Wall 5: The Agent Must Handle Multiple Entry Points

Stripe's Minions goes through Slack, Coinbase's Cloudbot also goes through Slack, and Open SWE connects to Slack + Linear + GitHub at the same time. Domestic scenarios also need DingTalk and Feishu. A shared belief for organizational Coding Agents is: **do not make users switch to a new interface to find the agent; make the agent appear where users already are.**

We added a channel adapter layer on top of Harness to map events from different entry points uniformly to `(threadId, message)`. `github:issue:owner/repo#42` converges into a UUID via SHA-256, and the same applies to DingTalk and Feishu. This deterministic mapping ensures that all comments on the same Issue are routed to the same agent session, and the conversation history is automatically restored.

---

## After Climbing These Walls: A Few Takeaways

### Context Engineering Is Not a Buzzword; It Is an Engineering Necessity

The industry now calls the question of "how to feed context to an agent" Context Engineering. What is interesting is that almost all mainstream Coding Agents have independently converged on the same pattern: Claude Code has `CLAUDE.md`, GitHub Copilot has `.github/copilot-instructions.md`, and Open SWE has `AGENTS.md`. **Repo-level conventions should not be hard-coded in the system prompt; they should be files—versionable, reviewable, and independently updatable.**

Our workspace pushes this idea further: in addition to `AGENTS.md` defining personality and behavior conventions, there is `skills/` for team SOPs (commit conventions, test conventions), `subagents/` for declaring subagents, `knowledge/` for domain knowledge, and `MEMORY.md` for accumulating long-term facts. The workspace is managed as Git, validated in CI, and hydrated into all replicas at deployment. **What changes frequently should be these files, not Java code.**

### Tool Curation Matters More Than Tool Quantity

When Stripe publicly shared its Minions experience, it said its agent has about 500 tools, but emphasized "tool curation matters more than tool quantity." Open SWE followed the same philosophy, exposing only about 15 core tools. Our approach is similar—the built-in toolset is limited to file operations + shell execution + memory retrieval, and business tools are registered on demand via `toolkit.register(...)`.

### Critical Steps Cannot Rely on Prompt Alone

**You cannot rely solely on a prompt to tell the model "remember to run tests"; critical steps must be guaranteed by deterministic logic.** The GitHub Copilot Coding Agent validates results through the repo's existing CI pipeline after it finishes; Open SWE has an `open_pr_if_needed` middleware as a safeguard—if the agent forgets to open a PR, the middleware automatically does it. Harness's middleware mechanism (`MessageQueueHook`, `ThreadBudgetHook`, etc.) follows the same idea: **the line between what is left to the model and what is guaranteed by deterministic code must be drawn clearly.**

A line from the Open SWE blog puts it accurately: the separation between agentic (model-driven) and deterministic (middleware-driven) is what takes an agent from "demo works" to "production reliable."

One more point: **Draft PR as the output contract.** Whether it is Copilot Coding Agent, Open SWE, or Stripe Minions, the agent's output is a draft PR that always requires human review before merge. The agent does not directly change production code—this is a basic safety assumption for organizational Coding Agents.

### Think Carefully Before Big Changes

Letting an agent directly tackle "refactor the entire auth module" is high risk—it may think and change as it goes, breaking things along the way. Harness's Plan Mode solidifies this into a "think first → write plan → human confirms → then act" flow. Once enabled, the agent enters a read-only phase, and exiting the plan requires human confirmation. Coinbase Cloudbot's "Agent Councils" follow the same idea—adding a human approval node before high-risk operations, **replacing prayers that the model does not make mistakes with process constraints**.

### Subagents Are Not Just Nice to Have

Open SWE uses Deep Agents' `task` tool for subagent dispatch, Stripe uses Blueprints for orchestration, and Ramp uses Sessions + Child Sessions. Harness's subagent usage is lightweight—write a markdown file in the workspace declaring responsibilities and toolset, and the main agent can delegate by calling `agent_spawn`. Add `timeout_seconds=0` for background calls so the main agent does not block; after the subagent finishes, the framework automatically injects the result into the next round of inference.

---

## From Single Machine to Enterprise: An Evolution Path

You do not need to climb all these walls at once. Harness is designed to let you start from the simplest form and upgrade as needed:

**Stage 1: Local CLI.** With no configuration, `execute` runs in the host's `sh -c`, and state is stored in local files. Use only in a trusted local environment.

**Stage 2: Add a sandbox.** One line `.filesystem(new DockerFilesystemSpec()...)` moves all execution into containers. Each Issue/PR gets a temporary container, and the host attack surface is not exposed.

**Stage 3: Multi-replica distributed.** Replace `stateStore` with Redis, store sandbox snapshots in OSS, and add `executionGuard` for concurrency control. At this point you can scale horizontally—run N replicas behind a load balancer, and any replica can pick up any conversation from any user.

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(new OssSnapshotSpec(ossClient, "bucket", "prefix/"))
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
        .leaseTtl(Duration.ofMinutes(30)).build()))
.stateStore(RedisAgentStateStore.builder().lettuceClient(redisClient).build())
```

**Stage 4: Observability and rate limiting.** Prometheus metrics, model budgets, upstream rate-limiting and retries—these together are what a system that "can stay online after launch" looks like.

---

## What the Convergence Tells Us

Looking back at the projects mentioned in this post—Stripe Minions, Ramp Inspect, Coinbase Cloudbot, LangChain Open SWE, GitHub Copilot Coding Agent, Claude Code, plus AgentScope Harness—they differ in language, ecosystem, and deployment form, but are highly consistent in core architectural decisions: per-session isolated sandbox, deterministic thread-ID routing, middleware interception chain, message-queue injection at agent runtime, repo-level instruction files, and draft PR as the output contract.

This convergence is not the result of copying each other. **It is an engineering inevitability forced by the same set of problems.**

---

## Closing Thoughts

The first half of the Coding Agent era was about personal productivity—smarter models, more accurate completions, and smoother local tools. The second half shifts the battlefield to engineering: how to turn "can run a demo once" into "7×24 stable service for an entire team." From Stripe to GitHub, from LangChain to AgentScope, everyone started from a different point and arrived at the same architecture. That convergence itself is the best signpost.

The codingagent example mentioned in this post is a complete and readable sample; I recommend cloning it, running it once, and then reading the source code—it maps all the engineering problems discussed here to real code.

Dig deeper: [Harness Architecture](../docs/harness/architecture.md) · [Workspace](../docs/harness/workspace.md) · [Sandbox](../docs/harness/sandbox.md) · [Context Compaction](../docs/harness/compaction.md) · [Subagent](../docs/harness/subagent.md) · [Skill](../docs/harness/skill.md) · [Plan Mode](../docs/harness/plan-mode.md)
