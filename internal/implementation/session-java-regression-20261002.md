# Java session/event concentrated regression — 2026-10-02

This run uses `/Users/ken/agentscope-3/agentscope-java`, branch `harness-context-redesign`, without a worktree, commit, or changes to the user's running services. It preserves the existing staged and unstaged implementation. Companion reports cover Go/public API and Python/browser verification.

## Environment and isolation

- Java 21.0.2; Maven reactor compiles the project's configured Java release.
- Isolated PostgreSQL 17.10: `127.0.0.1:54780`, database `agentscope_java`, schema `dp`; the other agents use separate databases. Test credentials are only in temporary launch configuration.
- Isolated Gateway `28080`, Control Plane `28081` (root agent), DataPlane `28082`, Chat example `28085` (browser agent).
- Shared test workspace: `/tmp/agentscope-regression-20261002/workspace`. Runtime extraction and logs: `/tmp/agentscope-java-regression-20261002`.
- `RegressionDataPlaneApplication` is a **test-source-only** launcher. It loads the real HTTP application, authentication, JDBC storage, native journal, command workers, event projection and outbox with a deterministic `Model` bean. It does not replace these components with in-memory mocks.
- The fixture emits bounded text, `/ask` requests `ask_user`, `/slow` delays streaming, and Managed Job execution calls the actual exposed `task.complete` MCP tool. It is not a real provider conformance test.
- The shell contained a DashScope key. The second initial reactor run was interrupted; all subsequent reactor and HTTP launches explicitly remove `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, and `DEEPSEEK_API_KEY`. No real model inference is part of this campaign.
- DP test profile permits the isolated test internal token; deployment secret requirements are not weakened in production code.

## Commands and run evidence

All commands ran from the project directory. The model-key removal above applies to each command below.

| Run | Scope | Result / log |
| --- | --- | --- |
| Initial complete core | Core + Harness reactor, stopped by Core failures | Core 2,503 tests; initial 6 failures, 9 skips. `/tmp/session-java-core-harness-regression-20261002.log` |
| Complete Java service reactor | `-pl agentscope-harness,agentscope-service/service-common,agentscope-service/service-dataplane,agentscope-service/service-gateway -am test -Dmaven.test.failure.ignore=true` | Core 2,503; Harness 1,098; Common 32; DP 133; Gateway 3. The ignore flag was only to collect all failures; its `BUILD SUCCESS` is **not** a passing-test claim. `/tmp/session-java-all-regression-20261002.log` |
| PostgreSQL and export-race repair | DP reactor `package -Dtest=SessionEventLogMirrorTest,PostgresSessionDurabilityTest -Dsurefire.failIfNoSpecifiedTests=false`, explicit `AGENTSCOPE_TEST_POSTGRES_*` | Passed: 3 mirror unit tests + 2 **real PostgreSQL** durability tests; rebuilt DP jar. `/tmp/session-java-postgres-regression-20261002.log` |
| Core/Harness repair verification | Ten affected test classes | Initial repair checks isolated remaining cleanup/key-selection issues; the final full Core/Harness run passes. `/tmp/session-java-fixed-regression-20261002.log` |
| Final full Core/Harness reactor | Normal complete suites, with no failure-ignore flag | Core **2,505 / 0 failures / 0 errors / 9 skips**; Harness **1,099 / 0 / 0 / 14 skips**. A downstream JEV fixture teardown failure stopped this run before Service; its fix and the remaining suites are reported separately. `/tmp/session-java-final-reactor-regression-20261002.log` |
| Structured-output race | `-pl agentscope-core test -Dtest=ReActAgentStructuredOutputTest#testConcurrencyConflictStructuredOutput_repeated -Dagentscope.runStructuredOutputRaceTest=true` | **25,000 passed**, no skips, 24.3 s total. `/tmp/session-java-structured-race-regression-20261002.log` |
| Docker file transfer | `-pl agentscope-harness -am test -Dtest=DockerSandboxFileTransferIntegrationTest -Ddocker.it=true -Dsurefire.failIfNoSpecifiedTests=false` | **9 passed**, real Alpine containers, 34.4 s total. Binary payload, projected/quoted paths, rejection and scratch-file cleanup. `/tmp/session-java-docker-files-regression-20261002.log` |
| Distributed backend contracts | Redis/MySQL/PostgreSQL/Mongo/JDBC/OSS/COS/Control Plane selected suites; JDBC `-Pintegration`; actual Docker context supplied | Ordinary suites and MongoDB containers passed. The first JDBC container launch failed on the old Testcontainers Docker API default; both passed on the explicit API-version rerun below. `/tmp/session-java-backends-regression-20261002.log` |
| JDBC real containers | `-pl agentscope-extensions/agentscope-extensions-jdbc test -Pintegration -Dtest=MysqlIntegrationTest,PostgresIntegrationTest -Dapi.version=1.44` | 10 passed: MySQL 8.0 × 5 and PostgreSQL 16 × 5. `/tmp/session-java-jdbc-containers-regression-20261002.log` |

The full reactor also executes dependency-module suites, including AG-UI, Control Plane, DashScope adapter, sandbox E2B adapter, skill Git repository and the JEV example. External model keys are absent; these adapter suites do not establish live provider coverage.

## Confirmed defect and corrections

### Duplicate immutable export caused a SQL retry storm

The real Gateway → Managed → DP → PostgreSQL path demonstrated concurrent exporter re-delivery of the same immutable native event. `SessionEventLog.appendInternal` treated the unique `event_id` collision as a sequence collision and retried the identical insert up to sixteen times before `appendIdempotent` read the winner. This did not create duplicate committed rows, but magnified SQL work and warning logs.

The append loop now detects a committed row with the same stable event ID and immediately returns control to the existing idempotency/payload validation path. Sequence races still retry; changed payloads and event identity conflicts remain rejected. A deterministic test races an initially absent event with an already committed winner and asserts exactly one attempted insert.

### Cancelled sandbox bindings survived in copied execution contexts

The cancellation regression exposed that native execution copies `RuntimeContext`, while its `SandboxAcquireResult` remains shared. Releasing only the original context left the copy with a stale sandbox reference. Removing the original binding could also allow an old context to fall back to a different concurrently active call.

`SandboxAcquireResult` now has an atomic shared released marker. Lifecycle cleanup claims release once and leaves an invalidated binding. `SandboxBackedFilesystem` rejects a released binding instead of falling back to another call. Regression assertions cover the original and copied contexts, a concurrent second call, and exactly one persist/release. This is a production isolation fix, not an adjustment that merely waits longer.

### Managed execution must be started before tools can complete it

A deterministic fast Managed Job reached `task.complete` before its asynchronously mirrored running event had committed in the control plane. The data plane now performs a synchronous, immutable-scope start handshake before input admission or model scheduling. The control-plane route validates the current task/attempt/generation and rejects replaced or terminal attempts; delayed historical events cannot authorize execution. Two Java checks cover captured-scope requests and rejection before model/input admission. Companion Go tests cover real PostgreSQL replica contention and immediate completion.

### Existing Core/Harness fixtures updated to the implemented contracts

- Execution contexts preserve caller values while isolating execution identity and middleware-local mutations; tests now verify those properties, consistency within one call, user isolation, and absence of identity leakage into the source context.
- Queue completion and admission tests wait for actual publisher completion and the next middleware admission. A producer sink emission is not a synchronous completion guarantee.
- Sandbox execution tests configure an external native `WorkspaceSessionLogStore` on a local test filesystem. `SandboxBackedFilesystem` deliberately does not claim durable atomic journal storage.
- The remote-filesystem/local-state-store rejection test explicitly selects `LEGACY`; native mode restores from its journal and must not require an additional legacy store.
- Legacy cache-object identity testing explicitly selects legacy mode. Native state inspection is a detached committed-state view.
- Automatic child recovery tests reopen the native journal instead of looking for obsolete JSON state-store writes. The cancellation test waits for both committed `run/end` and writer release before starting a replacement run.
- Action observation checks use the agent's actual native logical session key, and verify committed `action/end` before publishing a settled action event.
- Subagent timeout/orphan tests assert real cancellation and cessation of tool/model activity rather than a particular old `interrupt(RuntimeContext)` mock invocation.
- Skill namespace concurrency remains on the native path but uses an in-memory native journal; this test isolates skill-file namespace behavior rather than racing unrelated journal-file deletion at temporary-directory teardown.
- The JEV refund guard fixture also uses an explicit in-memory native journal and closes its HarnessAgent with try-with-resources. Its real query/refund dispatch assertions remain unchanged; all 184 JEV example tests then pass.

### Added command crash injection

`SessionInboxCrashRecoveryTest` passed both failure scenarios: a committed inbox acceptance whose ACK and immediate verification read are lost still deduplicates after reopening; a checkpoint committed before `inbox/applied` survives replacement without repeating the injected user message, and a second application attempt does not advance the journal. These are deterministic storage fault injections, not claims that every possible OS-kill window has been explored.

## Real PostgreSQL / process recovery

1. Executed `agent-api-v1-postgresql.sql` and `agent-event-mirror-outbox-postgresql.sql` twice each with `ON_ERROR_STOP=1` in the dedicated DP schema. All four executions succeeded. No existing user database was touched.
2. Started DP against that schema with `ddl-auto=update` to provision its remaining pre-existing tables, then restarted with `BUILDER_JPA_DDL_AUTO=validate`. PostgreSQL schema validation and `/actuator/health` both succeeded. This is a fresh-schema/idempotence check, not an exhaustive upgrade-fixture matrix for every older release.
3. `PostgresSessionDurabilityTest` uses fresh random schemas and drops only its own schema after each test. It verifies independent JDBC clients, native writer exclusion, immutable batch replay, sealed-run rejection, unrelated new-run admission, and tenant namespace isolation.
4. Its real JPA test verifies rollback leaves no outbox record, delivery queries preserve per-session FIFO, and two database connections serialize on the same attempt fence; the blocked reader sees the committed sealed state after lock release.
5. A Managed smoke response initially echoed the entire injected task instructions character by character, generating 2,975 deltas. This was a test fixture output-size mistake, not an infinite inference loop: the log contained exactly one model call and a completed run. The fixture now emits a fixed short response.
6. The DP process was stopped with 1,449 undelivered outbox rows from that run, leaving the database intact. On restart all **2,987 total** scoped outbox rows were delivered, with `pending=0` and maximum delivery-attempt count `1`. This provides actual process-restart delivery evidence in addition to the injected failure/retry unit tests.
7. Existing focused coverage includes frozen original-attempt replay after replacement/restart, failed transport retry, expired admission binding rejection, native sealing versus delayed writer acquisition/commit, administrative events with null run identity, and terminal readiness before public completion.

## Distributed backend evidence

| Backend / suite | Real storage used | Evidence |
| --- | --- | --- |
| DP PostgreSQL 17.10 | Yes, isolated database and random schemas | 2 journal/outbox durability tests plus actual DP process restart, SQL DDL idempotence and Hibernate validate startup |
| JDBC PostgreSQL 16 | Yes, Testcontainers | 5 tests: dialect, state CRUD, BaseStore CAS, BYTEA snapshot, advisory lock |
| JDBC MySQL 8.0 | Yes, Testcontainers | 5 tests: dialect, state CRUD, BaseStore CAS, BLOB snapshot, long-name advisory lock |
| MongoDB 7 | Yes, Testcontainers for 22 assertions | 7 state versioning + 8 index lifecycle + 7 BaseStore contract; the remainder of the 141-test Mongo suite uses mocks |
| Redis 7 | Yes, isolated container with AOF and `appendfsync always` | New integration test passed: two independent clients race CAS, replay an immutable batch, reopen the committed prefix, fence a sealed writer, admit a new run and isolate a second tenant; all 5 Redis tests passed |
| JDBC H2 / SQLite | Real embedded stores | 66 embedded-store/adapter tests passed; final JDBC suite additionally includes 26 SQL-generation tests and 10 real PostgreSQL/MySQL tests: **102 passed** |
| Standalone PostgreSQL extension | Mocks | 148 tests passed; do not confuse this with the real PostgreSQL tests above |
| Standalone MySQL extension | Mocks | 7 tests passed; real MySQL is exercised by the JDBC extension suite |
| OSS / COS | Mocks | 20 and 62 tests passed respectively; no cloud account used |
| Control Plane / Control Plane Java adapter | Deterministic stub/mocks | Full **74-test** suite passed in the final run; actual PostgreSQL control-plane verification is in the companion Go report |

Mongo/MySQL/PostgreSQL images were pulled for the existing test suites. JDBC Testcontainers 1.21.3 initially selected a Docker API below the server minimum; passing `-Dapi.version=1.44` resolved the environment mismatch without a production-code change. Docker-backed CAS checks are single-host tests, not a network-partition or multi-host durability campaign.

## Coverage mapping and remaining boundaries

The complete Core/Harness/Service suites exercise committed-prefix visibility, orphan blob rejection, unsupported required event recovery, checkpoint restore/fork, tenant/user/session routing, protected runtime paths, command idempotency and ordering, steering/injection, pending interaction continuation, immutable event identities, model/tool projections, child identity inheritance, public view reconstruction, outbox rollback and terminal fencing. The companion browser report covers refresh/reconnect, pending interaction restoration and process restart in the real Chat example. The Go report covers public APIs, approval authorization, Attempt fencing, coordinator semantics and lifecycle behavior.

The following must not be inferred from passing deterministic/unit suites:

- Millions of chunks, long-session capacity, large multimodal payloads, bounded-memory profiles, cold projection rebuild cost, retention/GC policy and throughput benchmarks.
- Real provider tool-argument interleaving, actual token billing, multiple SDK-version interoperability and provider-specific fallback behavior.
- Real cloud OSS/COS accounts, E2B service, NAS failure modes, a multi-node network partition/clock-skew campaign, and durable Redis failover configuration.
- Java **Session** webhook HTTPS signature/allowlist/timeout/lease-takeover behavior against a real receiver; no external receiver was contacted from Java. Its eight-failure pause is distinct from the Go **Invocation** webhook (twelve failures), whose actual TLS/two-replica/PostgreSQL tests are reported by the Go campaign.
- Seven disabled `AgentEventStreamTest.StreamOrdering` methods are unimplemented design placeholders, not passing assertions. Windows-only Core filesystem-hidden-file behavior and five platform-specific Harness shell-filesystem cases are skipped on macOS. The default Core skip for structured-output stress and nine default Harness Docker skips were **separately enabled and passed**, rather than left untested.
- Real process kill at every individual blob/CAS/inbox/action acknowledgement boundary is not equivalent to the injected storage failures and the completed outbox restart test. Those tests establish specific invariants, not an exhaustive crash-state exploration.

## Final verification status

Completed. The final remaining-suite reactor finished with **BUILD SUCCESS**, without `maven.test.failure.ignore`, in 2 min 34 s (`/tmp/session-java-final-service-regression-20261002.log`). It used current source via `-am`, `-Pintegration`, explicit PostgreSQL/Redis test endpoints, `-Dapi.version=1.44`, and a test selector excluding the already completed Core/Harness package namespaces. AG-UI (507) and Git skill repository (23), which share those namespaces, had already passed in the preceding full reactor. External model keys remained unset.

| Final relevant module | Tests | Failures / errors | Default skips |
| --- | ---: | ---: | ---: |
| Core | 2,505 | 0 / 0 | 9 |
| Harness | 1,099 | 0 / 0 | 14 |
| Service Common | 33 | 0 / 0 | 0 |
| Service DataPlane | 135 | 0 / 0 | 0 |
| Service Gateway | 3 | 0 / 0 | 0 |
| Redis extension | 5 | 0 / 0 | 0 |
| JDBC extension | 102 | 0 / 0 | 0 |
| JEV example | 184 | 0 / 0 | 0 |

The opt-in runs separately passed **25,000 structured-output repetitions** and **9 Docker file-transfer tests**, covering ten of the default skipped entries above. Remaining Core/Harness skips are seven empty stream-order design placeholders, one Windows-only Core case and five platform-specific Harness shell-filesystem cases. Other dependency suites also passed: AG-UI 507, Git skill repository 23, JEV extension 85 (one `jev.browser` opt-in skip), Control Plane 74, E2B adapter 54, DashScope adapter 480 (two obsolete non-TOOL-role converter cases skipped because Msg validation makes those inputs unreachable), and MySQL adapter 7. Mock-only cloud adapter and actual-container distinctions are recorded in the backend matrix above; adapter passes do not imply live cloud/model coverage.

The DP executable packaged successfully and the final isolated runtime loaded the synchronous Managed start barrier and sandbox release fix before the root task's HTTP verification. The root task then verified three concurrent fast Managed Jobs plus the Managed action/respond/new-turn/cancel flow. These HTTP assertions are recorded in its report, separately from Java unit counts.

Scoped formatting checks and `git diff --check` passed. File manifest: `/tmp/session-java-regression-files-20261002.json` (28 files, including the two collaborating agents' Java fixture/start-barrier changes). No files were staged or committed. All source changes are in the specified main directory/current branch; no alternate checkout was used.

Cleanup: after HTTP verification, the test DP (PID 75970, port 28082) and Gateway (PID 24696, port 28080) were terminated after identity checks. The test Redis container `agentscope-regression-20261002-redis` and its disposable volume were removed after the integration assertion passed. Testcontainers cleaned up its MySQL/PostgreSQL/Mongo/Alpine test containers. The root task then stopped and removed the disposable shared PostgreSQL 17 container and verified ports 28080–28085 and 54780 are free; only the user's original containers remain. Temporary evidence logs and the extracted fixture remain under `/tmp`; no production deployment was performed.
