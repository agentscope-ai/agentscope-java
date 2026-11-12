# Durable implementation scope

User decisions recorded on 2026-11-07:

- Build `agentscope-durable` as a general-purpose open-source framework/component
  with independently usable APIs, protocols, storage and defaults.
- Durable must not depend directly or transitively on any Managed Agents module,
  artifact, source checkout, test fixture, platform database or platform service
  to build, test or run. This includes `managed-agents-common`, Brain and Tool Worker.
  Future platform integration belongs on the platform side of the dependency.
- Migrate required reusable code from `common` and other source modules together
  with its dependency closure, tests and resources into Durable or appropriate
  AgentScope extensions. Replace platform-specific dependencies with narrow host
  interfaces or remove the associated wiring; do not import the entire platform
  common module or keep a renamed wrapper that still depends on it.
- Before declaring readiness, activate and validate every retained supported
  module, inspect resolved build/test dependency graphs, and verify isolated
  builds/tests/examples without Managed Agents sources or cached artifacts.
  Disabled imported modules are unfinished work, not evidence of independence.
- `agentcore-managed-agents` is an unreleased development project. Use its
  architecture, reusable mechanisms and failure tests as input, not its legacy
  APIs, DTOs, package names, prepared profiles, tables or deployment layout as
  compatibility requirements. Redesign or remove imported legacy logic where
  appropriate; do not add compatibility facades, dual protocols or old-schema
  support without a concrete requirement.
- Preserve correctness guarantees: atomic commits, idempotency, ownership/fencing,
  stable execution identity during recovery, truthful UNKNOWN outcomes and reliable
  result/event handoff. These are distinct from compatibility with old platform
  formats. Respect native Harness recovery boundaries and declared version support.
- Complete and validate Durable first. The user subsequently authorized adapting
  `/Users/ken/agentcore/agentcore-managed-agents` in the same task once Durable is
  ready. Replace superseded platform execution/recovery code directly, without
  compatibility layers; keep the dependency direction platform -> Durable.
- Original import manifests and verification records describe a historical
  snapshot, not a promise to preserve the imported design. Keep provenance intact.

User clarification recorded on 2026-11-09:

- Durable is in development/research. Do not design, implement or retain backward
  compatibility for its APIs, messages, events, protocols or persisted formats.
- Replace superseded designs directly and update all affected callers, schemas,
  examples and tests together. Remove aliases, deprecated forwarding methods,
  legacy branches, dual read/write paths, old-format readers, migration layers,
  version negotiation and downgrade fallbacks intended to support old designs.
- Existing names and layouts are design input, not compatibility constraints.
  Cross-language alignment means one current contract, not supporting historical
  contracts. Do not add release-transition or rolling-upgrade compatibility work.
- Keep current-contract validation, idempotency, fencing, stable identities within
  an execution, truthful UNKNOWN outcomes and recovery correctness. Version/state
  checks reject mismatches; they do not trigger legacy conversion or replay.
- DurableRuntime is the unified assembly, business-operation and lifecycle entry point.
  Create, get and list Sessions directly through Runtime; do not introduce a separate
  DurableClient, Client builder or Backend-taking business facade. Runtime wires
  infrastructure while Session/Thread/Turn handles keep it out of task operations.
- Constructing Runtime must not require starting Brain. API-only deployments use
  their Runtime's Client without local Agent/model/executor configuration or task
  claiming. Execution hosts explicitly start their configured execution loop.
- Managed Agents is a platform application of Durable, using the same public
  APIs, protocols and extensions as other distributed Agent applications. Durable
  must not require platform services or special platform operation semantics.

User scope and delivery clarification recorded on 2026-11-10:

- This iteration completes the Durable framework and its Core/Harness integration,
  AgentScope adapter, backends, Hands extensions and affected consumers only.
  Do not modify Managed Agents; P7 is deferred to a later iteration.
- Prioritize implementation. Keep affected-module compilation, formatting and
  focused behavior/protocol tests. Defer broad integration acceptance (real MQ,
  multi-process fault matrices, backend combinations and repeated full builds)
  to the independent acceptance backlog, with actual evidence and gaps recorded.
- These instructions supersede earlier full-acceptance sequencing gates, not
  correctness requirements. Do not remove assertions, hide known failures or
  describe deferred checks as passed. Distinguish development completion from
  independent production acceptance.
