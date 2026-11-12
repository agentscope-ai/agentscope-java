# Execution model independent acceptance backlog

[中文](execution-model-acceptance.md)

Updated: 2026-11-10. See the [implementation plan](../design/execution-model-plan.en.md) and [development progress](execution-model-progress.en.md). The user prioritizes Durable implementation and defers heavy integration acceptance to a separate exercise. Managed Agents is outside this iteration.

## 1. Checks retained during development

- Compile affected modules and consumers; align Java types, schemas, examples and bilingual docs, with relevant Spotless checks.
- Run focused tests for new/changed behavior, particularly atomic commits, identity, idempotency, fencing, recovery and UNKNOWN. Fix discovered implementation defects now rather than deferring them as acceptance work.
- Use deterministic models, temporary local storage and existing shared contracts. Do not repeat every backend, example and full-repository test after each change.
- Record commands, actual outcomes and uncovered scenarios. Interrupted/skipped checks, SDK mocks and passes on older sources do not establish acceptance of current real deployments.

## 2. Deferred independent acceptance

Every item below is **pending independent acceptance**. Existing evidence helps identify scope but must be assessed against the final development sources. A numbers refer to plan section 12.

| Item | Requirements | Scenarios and criteria | Existing evidence / entry point |
| --- | --- | --- | --- |
| I1 Full build and packaged content | A16, A17, A18 | Clean verify for all retained framework modules; no obsolete classes/resources, missing packaging, BOM or example updates | Root reactor command below; the current broad run was deliberately stopped, not passed; journal resource repair has a focused test |
| I2 Independent dependency build | A18 | Build, test and run Java examples without Managed/Service sources or cached artifacts; inspect resolved dependency trees | `verify-independent.py`; earlier stage records do not validate final sources |
| I3 Backend combinations | A1–A13, A17 | SQLite/PostgreSQL × direct/HTTP contracts; lost commit responses, storage faults, snapshot/filter agreement | Existing Backend/HTTP contracts; lifecycle stage passed 124 tests including 81 actual PG cases; reassess final sources |
| I4 Real MQ and separate Hands | A4, A8, A15, A17 | Broker/Proxy with separate Brain/Hands; duplicate commands/results, lost wakeups, restart, cancellation and takeover; stable executionId and truthful UNKNOWN | Current RocketMQ tests primarily mock the SDK; real-broker drivers/fault fixtures still need implementation and have not executed |
| I5 Controls and takeover races | A2–A9, A15 | Pause/resume/cancel versus model return, result reception/application and final commit; reject stale owners, preserve explicit pause and Thread FIFO | Local race/recovery coverage exists; add independent JVM termination and lease-takeover combinations on final sources |
| I6 Usage and provenance | A5, A9, A10, A12 | Distinguish real model requests from committed-result replay; no duplicated accounting facts; original Hands Run/Step distinct from result-application Run/Step | Model evidence and Hands provenance are implemented with focused tests; cross-process verification remains pending |
| I7 Delegation and isolation | A3, A13, A14 | Isolated child Threads by default, explicit reuse and idempotency; parent pause/cancel boundaries and deadlock rejection; restore/fork cannot import executable old tasks | Isolation/reuse, atomic mappings, cancellation before/during admission, parent pause and cycle rejection pass SQLite/HTTP contracts and AgentScope recovery; later exercise independent parent/child exits |
| I8 Public APIs and observation | A1, A11, A12, A15 | Recreated clients find resources; SSE reconnect/expiry/empty filtered pages; failed callbacks do not acknowledge; observation/disconnection/close cannot drive or cancel tasks | Shared User API/HTTP contracts and chat checks exist; later run combined flows with real HTTP/SSE clients |
| I9 Examples and deployment forms | A16–A18 | Java examples, ordinary call/stream, API-only, embedded/separate Hands, optional MCP/Sandbox/Objects and bilingual documentation agree | Compilation and focused examples accompany development; Docker, external services and exhaustive deployment combinations follow here |

## 3. Reusable execution entry points

Run from the repository root with Java 17 and `-T1`. Remove ambient real-model credentials and use local fixtures by default; explicitly configure real-service tests and confirm they do not skip.

```sh
# Final framework regression excludes the duplicate agentscope-service by user scope
mvn -B -T1 -Dagentscope.frameworkOnly=true clean verify

# Isolated dependency cache build/audit without a source checkout copy
python3 agentscope-durable/scripts/verify-independent.py

# Dependency/BOM alignment
bash .github/scripts/check-shade-and-bom-sync.sh
```

Real PostgreSQL uses the existing `-Ddurable.test.jdbcUrl=...` contract entry in isolated schemas. Real RocketMQ evidence must include Broker/Proxy versions, startup commands, process exit points and reproducible assertions; there is currently no verified one-command acceptance driver. Final documentation checks run `npm test`, `npm run validate` and `npm run broken-links` in `docs/`; do not repeat them for every Java-only edit.

Each acceptance record includes the source revision or workspace diff identity, environment, commands, pass/failure/skip counts, log location and uncovered cases. Temporary logs are stage evidence; export necessary reports for independent acceptance. Fix discovered defects without weakening assertions or adding old-protocol compatibility.

## 4. Current execution decision

The broad Maven run in `/tmp/durable-thread-journal-framework-final.log` was deliberately stopped under the latest rapid-development instruction, with exit code 143. Core's 2,686 tests, Harness's 1,162 tests and Durable module tests had completed before interruption; this is not a full-repository pass. The native journal resource mismatch is fixed, with a focused test validating an actually committed event against the current schema and rejecting old/future versions. Results are recorded in development progress.

Pending acceptance items are neither missing implementation nor passed acceptance. The progress ledger continues to track actual implementation gaps. Do not start Managed Agents adaptation in this iteration.
