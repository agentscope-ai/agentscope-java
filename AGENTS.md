# AGENTS.md

AI coding instructions for AgentScope Java.

## 1. Collaboration principles

### Think before writing

- State assumptions; ask when unsure — do not guess.
- List competing interpretations; let the user choose.
- Suggest simpler solutions; challenge needless complexity.
- Stop when blocked; name the exact blocker.

### Simplicity first

- Write the minimum code that solves the task; avoid unrequested features, abstractions, dependencies, and configuration.
- Add error handling only for failures supported by the contract or a realistic execution path.
- Keep the diff small; trim it when it grows unnecessarily large.

### Surgical edits

- Touch only task-related lines; do not clean up nearby code or normalize its style.
- Do not refactor working code just because it differs from yours.
- Mention unrelated dead code; do not delete it proactively.
- Remove unused imports, variables, and functions introduced by your change.

### Verifiable outcomes

- Turn the task into checkable acceptance criteria.
- Pair each non-trivial step with a verification command.
- Report checks, results, and remaining gaps; skipped tests are gaps.

## 2. Project map

### Modules

- `agentscope-core/` — core agent APIs, ReAct loop, messages, events, tools, middleware, state.
- `agentscope-harness/` — workspace, memory, subagents, channels, `HarnessAgent`.
- `agentscope-extensions/` — model, storage, protocol, channel, sandbox, framework integrations.
- `agentscope-service/` — Java services; `service-controlplane/` is Go, `frontend/` is React.
- `agentscope-examples/` — runnable examples.
- `agentscope-dependencies-bom/` — third-party versions.
- `agentscope-distribution/` — published BOM and aggregate distribution.
- `docs/` — Mintlify documentation.

### Read when relevant

- Contribution rules: [CONTRIBUTING.md](CONTRIBUTING.md).
- Harness behavior: [docs/v2/en/docs/harness/architecture.md](docs/v2/en/docs/harness/architecture.md).
- Channel vocabulary: [CONTEXT.md](CONTEXT.md).
- Service work: [agentscope-service/README.md](agentscope-service/README.md).
- Documentation work: [docs/README.md](docs/README.md).
- POMs, manifests, and workflows are the source of truth for commands and versions.

## 3. Engineering rules

### Boundaries

- Reusable agent behavior belongs in `agentscope-core`; higher-level capabilities belong in Harness.
- Provider-specific code belongs in `agentscope-extensions`; application wiring belongs in examples or Service.
- Library modules must not depend on `agentscope-examples/*`.
- Keep core independent of Harness, Service, and concrete extensions.
- Keep Service gateway, control plane, dataplane, and scheduler responsibilities separate.
- Keep transport handlers thin: decode → call → map.
- New extension → update parent module, distribution, and BOM; run `.github/scripts/check-shade-and-bom-sync.sh`.

### Runtime

- Preserve public builders, interfaces, serialized state, and event payloads.
- Keep `call()` and `streamEvents()` terminal results consistent.
- Use `RuntimeContext` for user/session identity; do not share session state through agent fields.
- Use the configured state store and Harness workspace abstraction.
- Preserve Reactor cancellation, errors, cleanup, middleware order, and permission checks.
- Extend agent behavior with `MiddlewareBase`/`MiddlewareChain`; do not fork agent classes.
- Do not block reactive event-loop threads.

### Code and tests

- Target Java 17; use no preview features or switch pattern matching; run Spotless; preserve license headers.
- Keep dependency versions in the existing BOM/POM hierarchy.
- Add regression tests for bugs and behavior tests for new features.
- Prefer JUnit 5, Mockito, Reactor `StepVerifier`, fakes, and local fixtures.
- Use temporary directories and portable paths; do not use real credentials or provider services.
- Do not weaken assertions for Windows-only flakes; mirror the production guard in the test.
- Edit UI source in `agentscope-service/frontend/`; do not hand-edit generated `agentscope-service/service-controlplane/ui/`.
- Keep English and Chinese docs aligned; follow `.editorconfig`.

## 4. Commands

### Java

- Run from the repository root with `-T1`.
- Module tests: `mvn -T1 -pl <module> -am test`.
- One test: add `-Dtest=<TestClass>` and confirm it ran.
- Format: `mvn -T1 -pl <module> -am spotless:check`.
- Full check: `mvn -B -T1 clean verify`.

### Service and docs

- UI: `cd agentscope-service/frontend; npm test; npm run lint; npm run build`.
- Go: `cd agentscope-service/service-controlplane; make build; make test`.
- Docs: `cd docs; npm test; npm run validate; npm run broken-links`.
- Packaging/deployment: run the relevant release and source checks too.

## 5. Change workflow

- Inspect `git status` and relevant code first.
- Clarify public behavior, compatibility, and scope before coding.
- Implement → test → inspect the complete diff.
- User-visible behavior change → update matching `docs/v2` EN and ZH pages and affected examples.
- Run the relevant §4 check; cross-module or public-contract changes require the full check.
- Check for unrelated files, generated output, format errors, and secrets.
- Do not commit or push without user authorization.
- Follow [CONTRIBUTING.md](CONTRIBUTING.md) for contribution details.

## 6. Communication

- Lead with the result.
- Include changed files, checks and results, and remaining gaps.
- Keep unrelated findings brief and separate.
