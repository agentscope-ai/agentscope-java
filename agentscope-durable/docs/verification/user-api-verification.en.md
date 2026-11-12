# Durable user API implementation and verification

> Original dates, results, numbers and commands remain evidence for that historical version, not current acceptance claims.
> Historical cross-language tests do not establish current clients in other languages; see the [library README](../../README.md) for current checks.

Date: 2026-11-09. Covers P1–P7 of the user API and autonomous runtime plan (`../archive/user-api-and-runtime-plan.en.md`, retained in Git history).
Checkout: `/Users/ken/agentscope-3/agentscope-java`, branch `automatic-acr-release-durable`.
Changes are in the current checkout; no commit, push or deployment.

## Implemented boundaries

- `DurableRuntime` owns assembly, lifecycle and business access, and creates, gets and lists Sessions directly; DurableClient is no longer public.
  An API-only Runtime requires no Agent/model/Executor and claims no work. Execution hosts explicitly `start()`.
- Runtime/Session/Thread/Turn handles resolve persistent identities. Creation, queuing, paginated history,
  controls, interactions, observation and checkpoint/restore/fork share the authoritative Backend.
  One Durable Turn can recover across multiple native invocation Runs.
- submit/steer/inject/respond atomically record public input and correlation identities; typed
  controls validate inside transactions. Queued cancellation is immediately terminal; active
  cancellation waits for settlement. Resume cannot bypass tools, approval or UNKNOWN outcomes.
- Public HTTP exposes only user operations; claim/commit and execution ledgers remain internal.
  Reads, SSE and Runtime lifetimes do not drive Brain or cancel tasks on disconnection. Recovery
  uses persistent discovery, claims and fencing.
- Schema, messages/events, workflow and projection vectors define one current contract. Storage
  format is 5; previous development formats are rejected, without compatibility APIs, readers
  or migrations. Native checkpoint support remains separately declared and validated.

## Checks and evidence

| Check | Result |
| --- | --- |
| Complete Java 17 Durable reactor | 12 modules passed `spotless:check clean install`; 224 tests across 32 suites, no failures/errors/skips; real PostgreSQL and optional TypeScript HTTP test enabled |
| Independent source delivery | `scripts/verify-independent.py` built Core/Harness prerequisites from source, every retained module and resolved dependency graphs; no Managed source, artifact or service dependency |
| User contract matrix | Same 11-case `UserApiContract` over direct and public HTTP access, SQLite and real PostgreSQL; additional authorization, internal-operation rejection and lost-success-response retry tests |
| AgentScope | Native steer/inject/withdraw, approval, safe recovery, tool-result recovery and public checkpoint/fork tests passed |
| Events and lifetimes | Observation without Brain, replay/expired-cursor resynchronization, Run filtering with Session cursor advancement, callback retry, timeout/close without cancellation, history retained after event pruning |
| Recovery without hints | Replacement hosts discover admitted Runs without user connections or Wakeups; FIFO, waiting, claims and fencing reuse Backend contracts |
| Shared protocol | 5 TypeScript runtime and 5 Pi adapter tests passed; only affected schema/projection vectors were aligned, not a complete new user API in another language |
| Examples | Embedded recovery across two JVMs, PublicApi submission/query/cancel across API-only Runtimes, QueuedHands compilation and actual MQ execution passed |
| Docs and distribution | 16 documentation tests, Mintlify validate/broken-links and BOM/shade synchronization passed |

Logs: `/tmp/durable-user-independent.log`, `/tmp/durable-user-final-java.log`,
`/tmp/durable-user-example.log`, `/tmp/durable-user-http-example.log`,
`/tmp/durable-user-queue-example.log`, `/tmp/durable-user-bom.log`,
`/tmp/durable-user-doc-{tests,validate,links}.log`.

Isolated cache: `/var/folders/b3/0cr5wc6n1rq5r7x3ts0xvt1c0000gn/T/agentscope-durable-maven-2gendva7`.
After verification, regenerable third-party entries were removed to recover disk space;
built `io/agentscope` artifacts, source and test reports remain. The platform consumes verified
binaries and never participates in the framework build.

## Actual RocketMQ process recovery

Local isolated PostgreSQL 17, RocketMQ 5.3.2 Broker/Proxy and JDK 17; no production credentials.
Brain was killed with SIGKILL after tool-intent persistence. Replacement Brain recovered the
same Run. After a Hands restart, retrying the original submission requestId still returned
the same Run/execution identity, with exactly one execution ledger entry:

- Run: `run_33a94341-e838-460e-9b11-5e23cdda00cc`.
- Execution: `exec_c37d35783b77ae06baf7fdc970d443b25986bf049412a9a208b6986031f766de`.
- Evidence: `/tmp/durable-user-queued`; final Run revision 16.

This pure-tool example verifies identities and the ledger, not exactly-once guarantees for
arbitrary external tools. An actual shell-side-effect-once failure test is covered by the
separate platform process fixture and its current verification record.

## Scope and reproduction

Use JDK 17 and the plan's independent build commands from the repository root. Enable real PG
contracts with `-Ddurable.test.jdbcUrl=jdbc:postgresql://127.0.0.1:56068/postgres?user=postgres`
and the cross-language HTTP test with `-Ddurable.test.typescript=true`.

The framework root reactor including duplicate `agentscope-service` was excluded as requested.
The complete Durable reactor was verified. Independent Core/Harness prerequisites were compiled
and installed with `-DskipTests`; the 224 tests count Durable only, not a rerun of the full
Core/Harness suites. Cloud
accounts, Kubernetes, production load and publication were outside this core API plan. The
public protocol does not make private checkpoints interchangeable across Harnesses or turn
unknown external effects into success by executing them again.
