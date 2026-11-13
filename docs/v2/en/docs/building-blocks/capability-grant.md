---
title: Capability Grant
description: Trim the agent's capability surface to what the current user is authorized for
zh_link: /v2/zh/docs/building-blocks/capability-grant
---

## Overview

One-line positioning: **SkillFilter decides what the agent is designed to have; grant decides what the current user is authorized to use. The effective capability set is the intersection.**

Why grant exists: in enterprise deployments, one agent serves many users with different permissions (multi-tenant, multi-role). The grant layer cuts out every capability the current user is not authorized for — a cut capability **does not exist for the model**: it never enters tool schemas, cannot be enumerated, cannot be activated via `reset_equipped_tools`, cannot be loaded via `load_skill_through_path`, and cannot be executed. The harness skill chain (`HarnessSkillMiddleware`'s prompt and catalog, plus the harness `load_skill_through_path`) is equally governed by the grant surface. The staged cache is clamped the same way: sandbox workspace projection hydrates only the granted skills' `.skills-cache` subtrees for the current call — residue staged under an earlier, wider grant stays on disk (the grace-window GC owns deletion), no longer enters the sandbox, and is revoked out of a reused or snapshot-restored workspace when the current call no longer authorizes it.

It is orthogonal to the existing layers — each layer answers exactly one question:

| Layer | Relation |
|-------|----------|
| **grant (this page)** | When a whitelist is set, only whitelisted capabilities pass; ALLOW_ALL (no trimming) applies when no resolver is configured or it returns null |
| SkillFilter / dynamic visibility (`skillFilter(...)`, RuntimeContext overlay, `filterVisible`) | The configuration-intent layer: which skills the agent is designed to show; runtime overlays may widen or tighten (canary and temporary toggles are legitimate). Grant runs after it — the visible set is SkillFilter ∩ grant, and overlays cannot raise the grant surface |
| activation (`activatedGroups`) | Session-level switch deciding whether an existing capability is currently active; stacks independently with grant |
| Permission system | Execution-time approval (ALLOW / DENY / ASK); grant trims up-front, the two complement each other |
| External tools (`ToolRequestConfig`) | Caller-controlled via the request; never trimmed |

One-line composition: a capability actually executes only if it passes, in order — configuration intent (SkillFilter) → user grant → current activation → per-call Permission approval. Each layer answers exactly one question; they intersect, they never overlap.

## Quick start

Two things: implement a `CapabilityResolver`, register it on the agent.

```java
CapabilityResolver resolver = (ctx, agent) -> {
    String userId = ctx.getUserId();
    // Granularity is yours: per-(userId, agentId), or ignore agentId and return one
    // surface per user — the framework treats both identically.
    return new CapabilitySurface(
            authzService.allowedSkills(userId),      // allowed skill names; null = not set
            authzService.allowedToolGroups(userId),  // allowed ToolGroup names; null = not set
            authzService.allowedTools(userId));      // allowed tool names; null = not set
};

ReActAgent agent = ReActAgent.builder()
        .name("enterprise-agent").model(model)
        .toolkit(toolkit)
        .skillRepositories(repos)       // dynamic path for multi-tenancy; avoid static skillBox()
        .capabilityResolver(resolver)   // invoked by the framework at every call entry
        .build();
```

Runtime rules:

- **When it resolves**: once per `call()`, server-side, at the call entry; a `null` return means no trimming.
- **Lifecycle**: the resolved surface rides the `RuntimeContext` through the whole call chain — frozen per request, never persisted.
- **Sub-agents**: sub-agents without their own resolver reuse the parent's surface instance (zero re-computation).
- **Harness**: `HarnessAgent.builder().capabilityResolver(...)` is supported as well; resolution happens before the sandbox acquire and skill pre-staging, so the staged set, the prompt catalog and every gate observe the same surface.
- **Caching**: the resolver runs at every call entry (exactly once per call). If its backend is an expensive lookup (DB / RPC), cache it yourself to match your business needs (e.g. per `userId` with a TTL), accepting the corresponding permission-propagation delay; the framework does not cache resolver results.
- **Supersets are safe**: surface contents may exceed any agent's actual capabilities — names the agent does not have are simply never matched.

## The three surface shapes (read this)

A `CapabilitySurface` has exactly three shapes with clear meanings:

| Shape | Meaning |
|-------|---------|
| All three dimensions **`null`** (nothing set) | **No trimming at all** — everything is allowed (`ALLOW_ALL`) |
| All three dimensions **`Set.of()`** (explicitly empty) | **Deny everything** except system capabilities (`DENY_ALL`) |
| Any 1–3 dimensions set | The surface is a **complete grant enumeration**: a tool passes if it matches **any set dimension** (per-name / its groups / a granted skill's tool group); unset (`null`) dimensions grant nothing |

```java
// Only tools in the "reporting" group; unset dimensions grant nothing extra
new CapabilitySurface(null, Set.of("reporting"), null);

// No trimming at all — the only unconditionally open shape
new CapabilitySurface(null, null, null);

// Deny everything except system capabilities
new CapabilitySurface(Set.of(), Set.of(), Set.of());
```

`null` (not set) and `Set.of()` (explicitly empty) are two different things: the latter declares "zero grants via this dimension", the former "not participating". Once the surface is not all-`null`, the two have the same grant outcome (nothing passes via that dimension); the accessors (`allowedSkills()` etc.) still preserve the distinction.

## Grant decision rules

Whitelists only — matching **any set dimension** grants:

- Granting a **skill** implicitly grants its manual, scripts and associated tools (the skill tool group, convention-named `skillId + "_skill_tools"`), **and automatically enables custom tool groups bound to it via `activateOnSkill`** (the call entry merges bound groups into the grant set);
- Granting a **ToolGroup** implicitly grants every tool in it;
- Granting a **tool** by name grants exactly that tool (grouping is ignored, and it does not amplify to sibling tools in the same group).

**Grant skills by name, not by skillId.** The name is the business identity and the industry-standard unique locator: one entry covers every copy of that name in the catalog (catalogs use the standard merge semantics — same name keeps the higher-priority repository's copy). The skillId (`name + "_" + source`) remains the framework-internal registration and addressing key: loading takes a skillId, and because the standard naming rule forbids underscores in names, the first underscore in a skillId is always the separator — the split is unambiguous.

Only whitelists are provided; there is no deny (reverse-ban) capability. Default is deny — anything not reachable from the enumeration does not exist.

## System capability markers (`system`)

Which capabilities skip trimming? **Basic core capabilities that keep the agent running** (the framework meta tools are exactly this). The marker is not a control axis for business-shaped capability trimming — do not use it as a business capability switch.

The framework's own meta tools (`reset_equipped_tools`, `load_skill_through_path`) are marked system and stay usable under any surface (including DENY_ALL). Assemblers may also mark their own tools/groups/skills `system = true` (`@Tool(system = true)` or the corresponding builder) — an explicit declaration that the capability is exempt from trimming; registration logs an INFO trace for auditability.

**The marker applies only to the object it marks — it does not propagate**:

- a tool's `system` exempts that tool's visibility and execution;
- a group's `system` exempts only the group's own enumeration and activation — member tools stay governed by the grant surface (mark each member `system` if it needs the exemption);
- a skill's `system` exempts its prompt visibility and loading.

> **Trust boundary**: the `system` marker is open to and trusted from the assembler (the agent developer). The threat model is "end user vs. the agent's capability surface", not defense against the assembler.

## Fail-closed behavior

Principle: **when the authorization infrastructure fails, fail the call — never open up.**

- A throwing resolver **aborts the call**: the exception propagates to the caller and ERROR is logged — an authorization-infrastructure failure is not "no grants". Catch inside your resolver and return `DENY_ALL` if graceful degradation is wanted.
- Direct execution: paths that execute tools directly without going through an agent `call()` entry (e.g. `Toolkit.callTool` / `callTools`) bypass grant resolution — a missing surface means no trimming; invoking `DynamicSkillMiddleware` directly behaves the same (no surface on the rc = trusted assembler code, skills stay visible). Place a surface on the `RuntimeContext` (`rc.put(CapabilitySurface.class, surface)`) to have grants applied.

## Static skillBox path limitation

`skillBox(...)` is deprecated since 2.0.0 (retiring with the hook system); **do not use it in new code** — register skills with `skillRepositories(...)` (the default dynamic path) instead.

Authorization compatibility: `skillBox(...)` **combines** with a capability resolver. The static prompt is trimmed to the current grant surface right before injection — the SkillHook reads the surface from the call's runtime context and filters through the same rendering pipeline. When authorization is not engaged (or the surface is ALLOW_ALL) the prompt stays byte-identical to previous versions; when no skill is granted the whole block is omitted; sub-agents inheriting a parent surface are covered the same way.

One combination is still rejected: `skillRepositories(...)` + `dynamicSkillsEnabled(false)` + resolver — that shape renders no per-call skill prompt at all, so the configuration would be a silently ineffective authorization; `build()` fails with an `IllegalStateException` (an authorization setting must never silently degrade to an untrimmed agent).

Persistence and downgrade: authorization state (the surface, the managed marker, revoke scopes) is never persisted; the revocation manifest (`projectedWorkspacePaths`) is written only once authorization is engaged — deployments that never enable it keep a persisted JSON shape identical to older versions and can roll back safely. A grant-enabled deployment rolled back to an older version loses the unrecognized field: the old code discards the resumed sandbox state and recreates the workspace (WARN, calls unaffected). Multi-instance deployments should configure a real execution guard (`DistributedStore`) — under the default Noop guard, concurrent calls on one scope resolve projection state last-write-wins.
