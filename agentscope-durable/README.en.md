# AgentScope Durable

[中文](README.md)

Durable is an embeddable Java framework for persistent task admission, native Agent
recovery and independent tool execution. It reuses Core/Harness reasoning, permissions,
ThreadJournal and workspace, without depending on Managed Agents. This is a development
source API; development checks are not independent integration or production acceptance.

## Start here

- Application integration: [Java usage guide](../docs/v2/en/docs/harness/durable.md).
- User operations, messages and events: [public interaction contract](protocol/public-interaction-contract.en.md).
- Authoritative schema and shared fixtures: [protocol entry](protocol/README.md).
- Host wiring and launch instructions: [Spring Boot examples](durable-examples/README.en.md).

## Public boundaries

| Boundary | Responsibility |
| --- | --- |
| `DurableRuntime` | Assembly, business access and lifecycle; Session access, Turn scheduling and recovery |
| `HarnessAdapter` | Native state interpretation and the connection between Agent invocations and durable commits |
| `SessionBackend` | Durable facts, atomic commits, idempotent receipts, leases/fencing and event replay |
| `Executor` | Independent tool start/query/cancel/output, with truthful unconfirmed outcomes |

Session owns the Thread directory and public timeline; Thread pins a definition and
executes Turns in order. Turn is a logical task, Run is one invocation, and Step belongs
to one Run. Recovery retains Turn identity. API-only Runtime needs no local model or
Agent factory and does not call `start()`; execution hosts start explicitly. Dependencies
do not start services; closing an observer or Runtime does not cancel persistent tasks.

## Module layout

| Directory | Responsibility |
| --- | --- |
| `durable-runtime` | Public APIs, scheduling/recovery, transactional state machine, messages and events |
| `durable-worker` | Caller-side reliable delivery, Hands execution ledger, recovery and batching |
| `durable-adapters/durable-adapter-harness-agentscope` | Native ReActAgent/HarnessAgent adapter and checkpoint codec |
| `durable-examples` | Separate integrated/queued host examples, not library runtime dependencies |

Java groupId is `io.agentscope`. Native integration uses
`agentscope-durable-adapter-harness-agentscope`; there is no separate codec artifact.
`AgentScopeCheckpoints` and `AgentScopeCheckpointCodec` are part of that adapter.
Checkpoint validation and restore/fork still do not construct or start an Agent,
but depending on this artifact brings in Harness dependencies.

Infrastructure extensions all live in [`../agentscope-extensions`](../agentscope-extensions):

| Extension directory | Use |
| --- | --- |
| [durable-jdbc](../agentscope-extensions/agentscope-extensions-durable-jdbc) | SQLite and PostgreSQL backends |
| [durable-http](../agentscope-extensions/agentscope-extensions-durable-http) | Public/internal HTTP clients, handlers and SSE |
| [durable-sandbox](../agentscope-extensions/agentscope-extensions-durable-sandbox) | Sandbox bindings, detached operations, output and cleanup |
| [durable-rocketmq](../agentscope-extensions/agentscope-extensions-durable-rocketmq) | Queued Brain–Hands execution and optional wakeups |
| [durable-objects](../agentscope-extensions/agentscope-extensions-durable-objects) | S3/MinIO and OSS object stores |
| [durable-mcp](../agentscope-extensions/agentscope-extensions-durable-mcp) | MCP connections and explicit recovery extensions |

SQLite needs no database service. PostgreSQL requires a service and an explicit dependency
on the optional `org.postgresql:postgresql` driver. Applications own models, pools,
credentials, resource authorization and host lifecycle.

## Launch examples

Run from the repository root; launchers live alongside each scenario:

```sh
bash agentscope-durable/durable-examples/integrated/embedded/run.sh
bash agentscope-durable/durable-examples/integrated/http-server/run.sh
bash agentscope-durable/durable-examples/integrated/sandbox-hands/run.sh
bash agentscope-durable/durable-examples/queued/queued-hands/run.sh --compile-only
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only
```

`integrated` includes embedded, API-only http-server and colocated Sandbox Hands.
`queued` includes echo queued-hands and Sandbox Hands, with separate Brain/Worker
processes communicating through MQ. Offline fixtures need no model credentials.
Local Sandbox requires POSIX shell and Python 3 and runs with host permissions, not
production isolation. Real queued runs also require RocketMQ 5, topics/groups and
authorization. `--compile-only` checks packaging, not broker or end-to-end delivery;
see each scenario README for role arguments.

## Build and check

Java 17+ and Maven are required; isolated dependency checking also needs Python 3.
From the repository root:

```sh
make -C agentscope-durable check
make -C agentscope-durable independent
```

Normal checking covers build, formatting and tests. Isolation checking builds source
dependencies in a separate Maven cache and audits dependency trees. Success requires
actual execution evidence; this entry adds no passing claims. Real MQ, cloud Sandbox,
multi-process fault matrices, backend combinations and production capacity still
require independent acceptance. Unqueryable or unverifiable external effects remain
`UNKNOWN`; arbitrary external operations are not promised exactly-once execution.

## Design, verification and provenance

- [docs/design](docs/design): [execution model](docs/design/execution-model-plan.en.md),
  [multi-agent/Thread design](docs/design/multiagent-thread-design.en.md),
  [Hands extensions](docs/design/hands-extension-plan.en.md), [queued Hands](docs/design/queued-hands-plan.en.md).
- [docs/verification](docs/verification): [independent acceptance backlog](docs/verification/execution-model-acceptance.en.md),
  [development progress](docs/verification/execution-model-progress.en.md),
  [historical user API verification](docs/verification/user-api-verification.en.md), [historical verification](docs/verification/verification.en.md).
  Existing dates, results and commands are evidence for their recorded versions, not passing claims for the current tree.
- [docs/archive](docs/archive): [migration plan](docs/archive/migration-plan.en.md),
  [platform boundaries](docs/archive/platform-boundaries.en.md), [platform implementation](docs/archive/platform-implementation-plan.en.md),
  [previous API plan](docs/archive/user-api-and-runtime-plan.en.md), [upstream notes](docs/archive/UPSTREAM.en.md),
  [source manifest](docs/archive/source-manifest.json), [extraction record](docs/archive/extraction-record.json),
  [upstream Makefile](docs/archive/Makefile.upstream). Provenance only: historical links and commands may no longer work and are not current build instructions.
