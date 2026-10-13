---
title: Capability Grant
description: Trim the agent's capability surface to what the current user is authorized for
zh_link: /v2/zh/docs/building-blocks/capability-grant
---

## Overview

In enterprise deployments an agent serves many users with different permissions. The capability
grant layer lets you **cut out every capability the current user is not authorized for** — a cut
capability does not exist for the model: it never enters tool schemas, cannot be enumerated,
cannot be activated via `reset_equipped_tools`, cannot be loaded via `load_skill_through_path`,
and cannot be executed. The harness skill chain (`HarnessSkillMiddleware`'s prompt and catalog,
plus the harness `load_skill_through_path`) is equally governed by the grant surface.

It is orthogonal to the existing layers:

| Layer | Relation |
|-------|----------|
| **grant (this page)** | When a whitelist is set, only whitelisted capabilities pass; ALLOW_ALL (no trimming) applies when no resolver is configured or it returns null |
| activation (`activatedGroups`) | Session-level switch deciding whether an existing capability is currently active; stacks independently with grant |
| Permission system | Execution-time approval (ALLOW / DENY / ASK); grant trims up-front, the two complement each other |
| External tools (`ToolRequestConfig`) | Caller-controlled via the request; never trimmed |

## Quick start

Implement a `CapabilityResolver` and register it on the `ReActAgent`.

```java
CapabilityResolver resolver = (ctx, agent) -> {
    String userId = ctx.getUserId();
    // Granularity is yours: per-(userId, agentId), or ignore agentId and return one
    // surface per user — the framework treats both identically.
    return new CapabilitySurface(
            authzService.allowedSkills(userId),      // allowed skillIds (normally the skill name); null = not set
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

The `CapabilitySurface` is an immutable grant enumeration. The framework resolves it once per
`call()` (server-side — a `null` return means no trimming) and carries it on the
`RuntimeContext` through the whole call chain (frozen per request, never persisted);
sub-agents without their own resolver reuse the parent's surface instance (zero re-computation).

**Caching**: the resolver runs at every call entry (exactly once per call). If its backend is
an expensive lookup (DB / RPC), consider caching it yourself to match your business needs
(e.g. per `userId` with a TTL), accepting the corresponding permission-propagation delay; the
framework does not cache resolver results.

Returning a superset is safe: surface contents may exceed any agent's actual capabilities —
names the agent does not have are simply never matched.

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

`Set.of()` (explicitly empty) and `null` (not set) are semantically different: the former
declares "zero grants via this dimension", the latter "not participating". Once the surface is
not all-`null`, the two have the same grant outcome (nothing passes via that dimension); the
accessors (`allowedSkills()` etc.) still preserve the distinction.

## Grant decision rules

Positive authorization is hierarchical; matching **any set dimension** grants:

- Granting a **skill** implicitly grants its manual, scripts and associated tools (the skill
  tool group `skillId + "_skill_tools"`, where a source-less skillId is simply the skill name);
- Granting a **ToolGroup** implicitly grants every tool in it;
- Granting a **tool** by name grants exactly that tool (grouping is ignored, and it does not
  amplify to sibling tools in the same group).

Skills are granted and addressed **by skillId**: normally just the skill name (the
industry-standard unique identity); the combined `name + "__s__" + source` form only when
same-named skills come from different repositories (entitlement lists use the bare name; the
combined form for the rare precise case). Catalogs key by skill name and enforce
per-agent-catalog uniqueness (same name + same instance is idempotent, a different
instance is rejected).

Only whitelists are provided; there is no deny (reverse-ban) capability. Default is deny —
anything not reachable from the enumeration does not exist.

## System capability markers (`system`)

As a rule, tools marked `system` are **basic core capabilities that keep the agent running**
(the framework meta tools are exactly this); the marker is not a control axis for
business-shaped capability trimming — do not use it as a business capability switch.

The framework's own meta tools (`reset_equipped_tools`, `load_skill_through_path`) are marked
system and stay usable under any surface (including DENY_ALL). Assemblers may also mark their
own tools/groups/skills `system = true` (`@Tool(system = true)` or the corresponding builder) —
an explicit declaration that the capability is exempt from trimming; registration logs an INFO
trace for auditability.

**The marker applies only to the object it marks — it does not propagate**: a tool's `system`
exempts that tool's visibility and execution; **a group's `system` exempts only the group's own
enumeration and activation** — member tools stay governed by the grant surface (mark each
member `system` if it needs the exemption); a skill's `system` exempts its prompt visibility
and loading.

> **Trust boundary**: the `system` marker is open to and trusted from the assembler (the agent
> developer). The threat model is "end user vs. the agent's capability surface", not defense
> against the assembler.

## Fail-closed behavior

When the resolver throws, **the call is aborted**: the exception propagates to the caller and
ERROR is logged — an authorization-infrastructure failure is not "no grants". Catch inside
your resolver and return `DENY_ALL` if graceful degradation is wanted.

**Direct execution**: paths that execute tools directly without going through an agent
`call()` entry (e.g. `Toolkit.callTool` / `callTools`) bypass grant resolution — a missing
surface means no trimming; place a surface on the `RuntimeContext`
(`rc.put(CapabilitySurface.class, surface)`) to have grants applied.

## Static skillBox path limitation

`skillBox(...)` is deprecated since 2.0.0 (retiring with the hook system); **do not use it in
new code** — register skills with `skillRepositories(...)` instead. For existing code still on
this path, its limitation: the static `skillBox(...)` registration (or
`dynamicSkillsEnabled(false)`) has no per-call skill-visibility filtering — when a resolver is
configured, `build()` logs an ERROR and disables the resolver on that agent: top-level calls
of this agent are untrimmed; as a sub-agent, loading and execution stay gated under the
inherited surface, but the static skill *prompt* still enumerates the full skill catalog (a
residual exposure; the static load tool's embedded `skillName` enum lists the full catalog too).
