# Managed MQ / Redis fixtures

Run commands from the repository root. Redis and PostgreSQL use the existing isolated Compose
project on `127.0.0.1:16389` and `127.0.0.1:16991`. The Managed transport uses RocketMQ **5.5.1
LiteTopic with a separate Cluster Proxy** on `127.0.0.1:19981`. The classic 5.3.2 Broker on
`19881` is retained only for the negative FIFO baseline, not as a production fallback.

## Infrastructure

```sh
docker compose -p managed-mq-redis -f managed-agents/tests/managed-mq-redis/compose.yaml \
  --profile database up -d --wait
managed-agents/tests/managed-mq-redis/build-lite-image.sh
docker compose -p managed-mq-redis-lite -f managed-agents/tests/managed-mq-redis/compose-lite.yaml \
  up -d --wait
```

The Lite image is built from the official 5.5.1 release binary and verified against its recorded
SHA-512. A fixed-digest 5.5.0 image supplies Java and launcher scripts; its RocketMQ libraries
are replaced by 5.5.1. The topics initialization container creates the Proxy heartbeat topic,
one-queue Lite parent Topics and Shared consumer groups before Proxy startup. All Lite parents
retain idle LiteTopics for fourteen days. A group binds exactly one parent and starts from its
minimum offset when no committed offset exists.

The Proxy runs in **Cluster** mode. The 5.5.1 embedded Local Proxy returns no Lite subscription
service and fails during `subscribeLite`; changing only the SDK or group attributes cannot fix
that deployment. This local fixture shares the Broker network namespace with Proxy and topic
initialization so its advertised loopback Broker address is reachable. This loopback topology
is unsuitable for multi-host deployments.

Use a **new** database for the current destructive Service schema; existing fixture schemas are
never automatically upgraded or reset:

```sh
docker compose -p managed-mq-redis -f managed-agents/tests/managed-mq-redis/compose.yaml \
  exec -T postgres psql -X -v ON_ERROR_STOP=1 -U managed -d postgres \
  -c 'CREATE DATABASE managed_mq_lite_test;'
```

If that fixture database already exists, reuse it only with the same schema or choose a new
name. PostgreSQL credentials `managed` / `managed-local-test` are local fixture values.

The small NameServer heap explicitly overrides the image's young generation with `-Xmn32m`.
Overriding only `-Xmx` retains the image's `-Xmn2g`, leaving almost no old generation and causing
frequent collection and administration timeouts. The fixture and deployment initializers check
mqadmin's success text as well as its exit status, retry at most three times, and fail before
starting business processes if any resource is unconfirmed. Run their offline regression with
`python3 -m unittest discover -s managed-agents/release/tests -p test_managed_mq_init.py`.

## Protocol contracts

```sh
mvn -T1 -pl managed-agents/common -am \
  -Dtest=ManagedInfrastructureTest,ManagedExecutionGuardTest,RocketMqManagedMessageBusTest,ManagedAdmissionStoreTest,ManagedJournalStoreTest,ManagedPipelineTest,ManagedToolExecutionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-DspotlessFiles=.*/runtime/managed/.*[.]java' test

MANAGED_REDIS_TEST_URI=redis://127.0.0.1:16389 \
MANAGED_MQ_TEST_ENDPOINT=127.0.0.1:19981 \
MANAGED_DATABASE_TEST_URI=jdbc:postgresql://127.0.0.1:16991/managed_mq_lite_test \
mvn -T1 -pl managed-agents/common -am \
  -Dtest=RedisManagedInfrastructureIntegrationTest,RocketMqManagedMessageBusIntegrationTest,ManagedPostgresAdmissionIntegrationTest,ManagedPostgresJournalIntegrationTest,ManagedPostgresPublicResourcesIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-DspotlessFiles=.*/runtime/managed/.*[.]java' test
```

The adapter tests use three separate `ma_push_bus_*_it_v3` parents and FIFO / Shared /
wildcard groups. They exercise the actual Push callback: failure retries the same Session head,
another Session progresses, and only a successful return releases the successor. These held-callback tests require no serialized SDK receipt. The proactive Turn ACK path described
below persists only private, expiring capabilities. `RocketMqLitePushIntegrationTest` additionally holds a production adapter callback
for 70 seconds, beyond the Proxy's default visibility interval; its topic and group are supplied
through `MANAGED_LITE_PUSH_TEST_TOPIC` and `MANAGED_LITE_PUSH_TEST_GROUP`.

The earlier single-database long-tool protocol fixture has been replaced by
`ManagedToolWorkerRealInfrastructureTest`. Its independent Agent Harness Runtime and Tool Worker databases, actual Sandbox
processes and Execute/Control consumers exercise the production ownership boundary, reliable
Resume, ordered Turn recovery and cancellation; see the Tool Worker execution boundary section below.

Optional infrastructure tests skip unless their variables are explicitly set. Production
never falls back to a fake or the classic FIFO adapter after a dependency failure.

## Tool Worker execution boundary

`ManagedToolWorkerPipelineTest` runs actual local Sandbox processes with independent H2 execution/runtime
ledgers and an explicit in-memory transport. `ManagedToolWorkerReliabilityTest` covers lost publish
responses, reordered events beyond one Inbox page, stale collectors, all pending conditions and
confirmed late results. `ManagedMcpExecutorsTest` uses a controlled MCP client to cover uncertain RPCs,
result capture outages, definition changes and active connection protection. None requires provider
credentials.

`ManagedToolWorkerRealInfrastructureTest` runs the same four Sandbox assertions with Redis, RocketMQ Lite
and two fresh owned PostgreSQL databases per case. Set a new dedicated prefix such as
`ma_hands_test_20261018a`; provision all five families with `deploy/managed-mq-init.sh` using these
suffixes (including the Turn depth families):

| Variable suffix after `MANAGED_AGENTS_RUNTIME_MQ_` | Value suffix after the chosen prefix |
| --- | --- |
| `TURN_TOPIC` / `HARNESS_RUNTIME_GROUP` | `_turns` / `_brain` |
| `TOOL_EVENT_TOPIC` / `TOOL_EVENTS_GROUP` | `_events` / `_brain_tools` |
| `RESUME_TOPIC` / `RESUME_GROUP` | `_resumes` / `_brain_resumes` |
| `TOOL_EXECUTE_TOPIC` / `TOOL_EXECUTE_GROUP` | `_execute` / `_hands_execute` |
| `TOOL_CONTROL_TOPIC` / `TOOL_CONTROL_GROUP` | `_control` / `_hands_control` |

```sh
MANAGED_AGENTS_TOOL_WORKER_PROTOCOL_TEST=true \
MANAGED_AGENTS_TOOL_WORKER_MQ_TEST_PREFIX=ma_hands_test_20261018a \
MANAGED_REDIS_TEST_URI=redis://127.0.0.1:16389 \
MANAGED_MQ_TEST_ENDPOINT=127.0.0.1:19981 \
MANAGED_DATABASE_TEST_URI=jdbc:postgresql://127.0.0.1:16991/postgres \
mvn -T1 -pl managed-agents/agent-service -am test \
  -Dtest=ManagedToolWorkerRealInfrastructureTest -Dsurefire.failIfNoSpecifiedTests=false
```

Use only the isolated fixture's administrator account (`managed` / `managed-local-test`); this test
creates and drops exclusively generated `ma_hands_test_*` databases, without resetting the supplied
database. The retired single-database protocol runner is no longer a test entry point.

## Actual service replicas

`replicas.py` starts two separate JVMs for each of Agent Service, Agent Harness Runtime and Tool Worker, plus the Go Control
Plane. Each run creates a new database, one-queue Lite parents and dedicated consumer groups.
It runs the actual four schema migration entry points, then provisions six scoped business
roles with the deployment grants script. Ordinary JVMs and Control use those business roles;
only migration/inspection commands use the fixture database owner. The ma version ledger is
validated at startup, including when Agent Service and Agent Harness Runtime use another schema as their search path.
It preserves a manifest and logs in the supplied **new** directory. Failed deliveries cannot
enter the next run's database, and the existing development stack is untouched. Ports `19081`,
`19082`, `19084`, `19085`, `19182`, `19184`, `19185`, `19281` and `19282` must be free.

Build Control Plane with `make build` in `managed-agents/control-plane`, then compile
Java services and test classes from the repository root:

```sh
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/tool-worker \
  -am package -DskipTests
python3 managed-agents/tests/managed-mq-redis/replicas.py start --state /tmp/managed-replicas-example
python3 managed-agents/tests/managed-mq-redis/replicas.py smoke --state /tmp/managed-replicas-example --seconds 180
python3 managed-agents/tests/managed-mq-redis/replicas.py parallel --state /tmp/managed-replicas-example --seconds 60
python3 managed-agents/tests/managed-mq-redis/replicas.py controls --state /tmp/managed-replicas-example
python3 managed-agents/tests/managed-mq-redis/replicas.py cancellation --state /tmp/managed-replicas-example
python3 managed-agents/tests/managed-mq-redis/replicas.py tool-worker-restart --state /tmp/managed-replicas-example
python3 managed-agents/tests/managed-mq-redis/replicas.py redis-restart --state /tmp/managed-replicas-example
python3 managed-agents/tests/managed-mq-redis/replicas.py stop --state /tmp/managed-replicas-example
```

Only the deterministic `ManagedReplicaModel` class and its nested class are put in a small
fixture JAR. Agent Harness Runtime's explicit fixture main registers that model before the real application
starts; other test configurations are excluded. Tool Worker explicitly enables local process Sandbox
execution. Provider credentials are cleared. Harness, HTTP, MQ, Redis, database transactions,
Redis/MQ renewal, tool requests, result commits and public event projection use the actual services.
No ACS or model provider is contacted.

Each fixture copies the three deployment JARs into its owned directory and records SHA-256
hashes in `manifest.artifacts`. A later Maven build or clean cannot change its running or
restarted JVMs. The Go binary must still be built before starting the fixture. Use a new state
directory for a new schema or deployment; retained evidence is never converted into a passing run.

`smoke` submits T1 / T2 through different Agent Service processes and verifies durable lifecycle order,
public results and one subprocess execution. `parallel` waits for a long tool to actually start,
then requires another Session to complete before that subprocess finishes; both share the same
physical parent queue. A long tool can be `waiting_tool` while another Session progresses.
`controls` accepts inject / steer during suspension through different AgentServices and retries each
idempotency key across replicas. It checks applied receipts, ordered model-visible input,
two actual SSE subscribers and Last-Event-ID replay through another Agent Service against the exact
durable event history. `cancellation` stops a suspended Turn's subprocess and requires its
queued successor to complete. `tool-worker-restart` kills both Tool Worker JVMs during a 120-second tool,
restarts them and requires the same task, execution ID and process handle with one start/done.
`redis-restart` stops only the fixture Redis container, accepts a durable steer while Redis is
offline, restarts Redis without deleting data and checks input refill and one tool execution.
Run these infrastructure fault tests sequentially, without simultaneous Redis/MQ unit tests.
The historical `takeover` command still targets the earlier whole-Turn lease scenario; it is
not part of the current suspension acceptance commands. Signals are
restricted to processes whose command carries the fixture's exact state marker. Startup alone
is not acceptance. These checks do not cover the complete ACK-loss, unknown-launch, result-stage
restart, subagent and parent-child capacity matrix. On startup failure,
stop that manifest's processes before retrying a new state directory. Databases and logs remain.

## Restored MemoryStore acceptance

After starting a newly built fixture, run:

```sh
python3 managed-agents/tests/managed-mq-redis/memory.py --state /tmp/managed-replicas-example --seconds 30
python3 managed-agents/tests/managed-mq-redis/memory.py --state /tmp/managed-replicas-example --seconds 30 --readonly
```

The explicit fixture model performs a real local tool and resumes the same Turn. During
suspension the fixture updates a document through the public resource API; resumed Agent Harness Runtime must
read the new version through its actual client and Go resource API. The second case also changes
the Environment to read-only and attempts an Agent write. Agent shared mounts already enforce
read-only, and the write must preserve that policy and create no additional document version.
This does not attribute the denial to the Environment change: live Environment enforcement is
independently tested against real PostgreSQL by the Go resource suite. Both scenarios require
a completed queued successor, one physical tool execution, valid private checkpoints and no
legacy `cp.sessions` row. They do not use ACS or a provider model. Native Memory requests validate
the frozen mount in Agent Harness Runtime and current ownership, archival and Environment access in Control Plane.

The ordinary smoke's `verify_smoke` helper can also
audit the same admitted run after a harness fault without starting a replacement tool. It checks
a later resumed epoch and a terminal checkpoint bound to that epoch, rather than assuming
exactly two physical attempts.

## AgentTask Harness / MCP acceptance

Build the Go control-plane binary and the three Java services first. Start a new, isolated
fixture with the real AgentTask Outbox and observer workers enabled:

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start --state /tmp/managed-tasks-example --task-workers
python3 managed-agents/tests/managed-mq-redis/tasks.py --state /tmp/managed-tasks-example --seconds 180
python3 managed-agents/tests/managed-mq-redis/tasks.py --state /tmp/managed-tasks-example --cancel
python3 managed-agents/tests/managed-mq-redis/tasks.py --state /tmp/managed-tasks-example --timeout
python3 managed-agents/tests/managed-mq-redis/replicas.py stop --state /tmp/managed-tasks-example
```

The explicit fixture model calls the actual collaboration MCP `task.get`, performs one local
Sandbox subprocess through Tool Worker, and calls MCP `task.complete` after the same Turn resumes.
Assertions cover one Attempt / Turn / side effect, persisted suspension and Resume, original
human Actor, native terminal output and no legacy `cp.sessions` row or control-plane heartbeat.
Cancellation checks a durable native Input, actual stopped subprocess and observed terminal
Attempt. These are real service processes and middleware; the model is deterministic and the
Sandbox is a local process, not ACS. They do not prove arbitrary external MCP side-effect recovery.
`--timeout` sets the Agent's actual runtime policy to 30 seconds before submitting a 60-second
tool, and requires native cancellation before timeout failure. The timestamp audit checks that
Attempt completion follows the committed native cancellation event.

For a deliberately lost completion reply, add `--mcp-reply-retry` to `replicas.py start` alongside
`--task-workers`. A local test proxy discards one committed MCP completion response and retries the
identical upstream request. `tasks.py` requires a successful read-only receipt, one result comment,
one Turn / Attempt and one subprocess side effect. The proxy is opt-in fixture code and is never
part of the production deployment.

Private credential and business-timeout checks use the Go HTTP suite with an explicit test DSN;
the suite creates and deletes its own PostgreSQL database and uses a labeled Agent Service HTTP fixture:

```sh
cd managed-agents/control-plane
CONTROL_PLANE_TEST_POSTGRES_DSN='postgres://managed:managed-local-test@127.0.0.1:16991/postgres?sslmode=disable' \
  go test ./internal/httpapi -run 'TestNativeManagedTaskAdmissionRetryAndCancellation|TestManagedDeadlineUsesFrozenPolicyAndStartedTime' -count=1 -v
```

It checks future-time credential verification, Session binding, runtime-binding revocation,
cancellation, deadline stop requests and failure only after native stop proof. Future-time
verification is not a wall-clock multi-day execution test.

## Queue isolation evidence

```sh
MANAGED_LITE_TEST_ENDPOINT=127.0.0.1:19981 mvn -T1 \
  -pl managed-agents/common -am -Dtest=RocketMqLiteIsolationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Passed on 5.5.1 Cluster Proxy: one physical parent queue, two Sessions, first Session retains
T1 while another Session progresses, and T2 waits for T1 ACK. The prior Local Proxy experiment
failed with `ClientProcessor.syncLiteSubscription:134`; bytecode inspection located its null
`LocalServiceManager.getLiteSubscriptionService()`. No server binary was patched.

`RocketMqFifoIsolationIntegrationTest` preserves the **failing classic baseline**. It requires
a fresh, dedicated, one-queue FIFO Topic and ordered group, named by
`MANAGED_FIFO_ISOLATION_TOPIC` / `MANAGED_FIFO_ISOLATION_GROUP`, and endpoint
`MANAGED_FIFO_ISOLATION_ENDPOINT=127.0.0.1:19881`. It demonstrates why multiple physical queues
can accidentally make a cross-Session test pass. It is explicitly opt-in and does not describe
the new production transport.

## Images and cleanup

| Image | Recorded digest / validation |
| --- | --- |
| redis:7.4.11-alpine | sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499 |
| apache/rocketmq:5.3.2, classic negative baseline | sha256:4a80ff5cd4b0a3d3ef6dc338ce2028a85bd995648cb3aa5c3e753746d2dbc587 |
| managed-test/rocketmq:5.5.1 | Official binary SHA-512 and fixed base digest in build-lite-image.sh / Dockerfile.rocketmq-551 |
| postgres:17 | sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d |

Java client remains 5.2.2; Lettuce remains BOM-managed. RocketMQ runs under amd64 emulation on
this arm64 fixture host. Stop only the task's two Compose projects; omit `-v` to retain evidence:

```sh
docker compose -p managed-mq-redis-lite -f managed-agents/tests/managed-mq-redis/compose-lite.yaml down
docker compose -p managed-mq-redis -f managed-agents/tests/managed-mq-redis/compose.yaml \
  --profile database down
```

## Suspension / resume protocol and historical evidence

The source has five logical channels: Turn, Execute, Control, Tool Event and Resume, each with
one parent Topic/group. Root and descendant Turns share the same Turn consumer pool; frozen
Session depth remains validated business metadata, not a transport lane.
Initialize the bindings using `init-lite-topics.sh`. `start --callbacks 1` constrains each
Runtime replica to one shared Turn callback, so parents must Suspend to let descendants run.
All consumption uses LitePushConsumer with FIFO and wildcard groups. Proxy automatic renewal is
explicitly enabled for up to three hours. Active Harness execution and held callbacks have separate
bounded capacities. Tools wait inline for five seconds by default
(`managed-agents.runtime.inline-tool-wait-seconds`), then release Harness resources and Redis execution
ownership and returns SDK Suspend. A Resume completes its own callback after its durable handoff;
the original message remains at the FIFO head until its durable terminal result is observed.

`smoke --seconds 180` requires the lifecycle running → waiting_tool → running → completed for T1,
then T2 running → completed, one process side effect, and durable suspension / resume facts. It
makes no claim to read an SDK ACK from the database. `subagents.py` checks durable waiting state, final statuses, SSE cursors and child process side effects.

The earlier `/private/tmp/managed-mq-replicas-20261014-resume-a` evidence used the retired
suspension-ACK protocol. It remains historical evidence only. The new
`/private/tmp/managed-mq-replicas-20261015-push-e` fixture passed same-Session ordering, parent/child
send, actual tool cancellation and replacement of both HarnessRuntimes while the tool continued. Its model
is explicit and deterministic; its Sandbox is a real local subprocess, not ACS.

Native log commits return after Agent Harness Runtime saves durable journal facts. Tool results use a Redis
logical mailbox; the callback completion protocol has been removed. Recovery and
bounded repair use DB facts. Exact artifacts and remaining gaps, including expiry and saturation,
are recorded in `managed-agents/docs/managed-mq-redis-refactoring-plan.md`. No individual check
proves that the complete refactoring plan has finished.

## Retired execution paths

The source no longer includes RuntimeTurnDispatcher, its database claim/heartbeat schedulers,
ToolTaskToolWorkerWorker, SandboxToolWorkerToolExecutor or the synchronous tool-worker-tools.py bridge.
Bootstrap/command controllers in the old Dataplane HTTP protocol and the Agent Service tool-task
forwarding controller are removed. Native MQ consumers now start by default; the replica
fixture omits their enable flag so actual startup exercises that default. The subprocess-child
cancellation regression has moved to ManagedSandboxToolExecutorTest and invokes the new
stable-execution protocol. Remaining legacy adapter/storage references are tracked in the
implementation plan; this removal is not a claim that all legacy paths have been cleaned up.


## Native Channel and Deployment/Cron adapters

After starting a fresh replica fixture with the current Go binary, Agent Service and Agent Harness Runtime artifacts:

```sh
python3 managed-agents/tests/managed-mq-redis/triggers.py --state /absolute/path/to/owned-fixture
```

This check submits the Channel adapter's internal request concurrently twice, checks one native
Session/Turn, submits a second Turn in the same conversation, verifies the original human Actor
and a single real subprocess effect, then repeats a Deployment fire with one scheduled key.
It waits for both Channel and Cron execution and checks that these Sessions have no legacy
`cp.sessions` rows. Provider input is explicit synthetic data; external IM delivery is not tested.
The Java bridge tests separately verify message identity, Turn filtering and paginated reply replay.
The Cron unit test checks that an unrecorded scheduled occurrence retains its coordinate across
later polling times. Neither check proves every scheduler/orchestration adapter has migrated.


`ManagedSessionChannelBridgeTest.liveNativeChannelReplyAndProviderRetry` can also exercise
actual Java reply parsing against that running fixture. Set `MANAGED_TRIGGER_CONTROL_TEST_URL`,
`MANAGED_TRIGGER_TEST_OWNER`, `MANAGED_TRIGGER_TEST_AGENT`, and
`MANAGED_TRIGGER_TEST_INTERNAL_TOKEN` from the owned fixture, then run:

```sh
mvn -T1 -pl managed-agents/service-scheduler -am test \
  '-Dtest=ManagedSessionChannelBridgeTest#liveNativeChannelReplyAndProviderRetry' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Without the explicit fixture URL this one method skips; it never starts production services or
uses user provider credentials. The current source acceptance records a successful real run of
this method in `/tmp/managed-trigger-java-live-channel-final.log`.


## Failure, reply and input-close boundaries

Against an owned current replica fixture:

```sh
python3 managed-agents/tests/managed-mq-redis/failures.py --state /absolute/owned/state
python3 managed-agents/tests/managed-mq-redis/input-boundaries.py --scenario reply --state /absolute/owned/state
python3 managed-agents/tests/managed-mq-redis/input-boundaries.py --scenario closing --state /absolute/owned/state
python3 managed-agents/tests/managed-mq-redis/replicas.py takeover --seconds 240 --state /absolute/owned/state
```

The reply case uses an existing confirmation action with synthetic fixture data. It proves the
native reply identity, same-Turn continuation and one local tool effect; it does not add a permission
mechanism or prove every external-result provider. The closing case initializes the workspace,
uses a finite cross-Agent Service input load, then verifies every accepted receipt and post-close retries.
It imposes no two-second finishing SLA. The takeover case also initializes the workspace before
pausing a Agent Harness Runtime, keeps the subprocess alive through lease expiry, restores the old process and
checks both effect uniqueness and committed epoch order. Its finally block resumes that owned PID.
A failed observation is retained and must not be treated as process termination or passing evidence.

Service failures use a separate native decision so internal suspension cannot be mistaken for
failure. Ready hints and activation use the contiguous terminal prefix whose original/resume
ACKs are confirmed. Database terminal status remains separately observable by the client.

## Console conversations

```sh
python3 managed-agents/tests/managed-mq-redis/conversations.py --state /absolute/owned/state
```

This checks native Session registration before any Turn, concurrent Chat retry deduplication,
conflicting intent rejection, a queued follow-up through the Session user-message entry, one
local subprocess tool effect, original human identity, and the delete-while-running gate.
Deleting the finished Chat retains its native Session. It uses real Redis/MQ/PostgreSQL and
the explicit local tool/model fixtures, without external model or ACS credentials.

## Native parent and persistent child Turns

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start --state /tmp/managed-children-example --concurrency 1
python3 managed-agents/tests/managed-mq-redis/subagents.py --state /tmp/managed-children-example --seconds 30 --send
python3 managed-agents/tests/managed-mq-redis/subagents.py --state /tmp/managed-children-example --seconds 30 --send --replace-harness-runtimes
python3 managed-agents/tests/managed-mq-redis/subagents.py --state /tmp/managed-children-example --seconds 30 --cancel
python3 managed-agents/tests/managed-mq-redis/replicas.py stop --state /tmp/managed-children-example
```

The deterministic model invokes the actual Harness `agent_spawn` and, with `--send`,
`agent_send` tools. The same durable child Session receives distinct native Turns; the child
uses Tool Worker for real local subprocesses. Assertions cover original Actor, immutable tool and
execution identities, checkpoint suspension / Resume, durable public events and SSE cursors,
one start / done effect per tool, and absence of legacy Session mirrors. `--replace-harness-runtimes`
kills both owned Agent Harness Runtime JVMs only after parent and child are durably suspended while the
tool is running, then restarts the same frozen deployment. `--cancel` requires confirmed
physical cancellation, a start without a done effect, and cancelled parent / child outcomes.
A terminal Turn alone cannot satisfy this cancellation check.

`--concurrency 1` limits each Agent Harness Runtime channel and physical Tool Worker tool pool to one slot. Child
admission / query / cancellation use a separate bounded pool inside the same Tool Worker process,
configured by `managed-agents.runtime.child-control-concurrency` (default 4). Physical tools cannot
occupy those control slots. This adds no deployed component. Each invocation saves a new
`subagents-*.json` with `passed=false` before execution and preserves failure details.

## LitePush wildcard and held-callback capability gate

This historical transport capability check holds an active callback to verify SDK renewal and
FIFO. Current Service suspension returns Suspend and releases the callback; use the segment
pipeline tests and real replica scenarios to verify that separate contract.

Provision a **fresh owned** LITE parent with one read/write queue and a fresh consumer group
with `consumeMessageOrderly=true`, `consumeFromMinEnable=true`, and attributes
`lite.bind.topic=<parent>`, `lite.sub.model=Shared`, `lite.sub.wildcard=true`. The fixture Proxy
explicitly enables automatic renewal with a maximum window of 10,800,000 milliseconds.
Run the capability test against those exact resources:

```sh
MANAGED_LITE_PUSH_TEST_ENDPOINT=127.0.0.1:19981 \
MANAGED_LITE_PUSH_TEST_TOPIC=your_owned_parent \
MANAGED_LITE_PUSH_TEST_GROUP=your_owned_group \
MANAGED_LITE_PUSH_HOLD_SECONDS=70 \
mvn -T1 -pl managed-agents/common -am test \
  -Dtest=RocketMqLitePushIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

The test publishes before consumer startup, makes no `subscribeLite` calls and has no database
directory. It holds the first Session callback, requires another Session and a newly created
Session to complete while that callback remains open, then requires the first Session's
successor to begin only after SUCCESS. Four SDK callback threads and the Lite FIFO accelerator
are explicit. A 70-second hold exceeds this fixture Proxy's 60-second default invisibility;
it does not verify the entire three-hour limit, process takeover or every fault boundary.
No explicit receipt ACK or application message-renewal loop is used. With no endpoint setting,
the test skips and must not be counted as a real broker pass.

## Tool output while Agent Harness Runtime is absent

After building the current services and starting a **new** replica fixture, run:

```sh
python3 managed-agents/tests/managed-mq-redis/tool-stream.py \
  --state /tmp/managed-replicas-example --seconds 180 --replace-tool-workers
```

This uses a deterministic model and a real local subprocess that emits beginning, middle and final
output. It waits for a persisted delta and a durable suspension, kills both Agent Harness Runtime processes, and
optionally replaces both Tool Worker processes. Before restarting Agent Harness Runtime, it requires the same tool execution
and process handle to finish, a contiguous durable source/chunk/byte prefix, a matching final
watermark, and exactly the expected public output while neither Agent Harness Runtime process exists. It then restarts
Agent Harness Runtime and verifies restored reasoning, exactly one model tool completion, one subprocess side effect,
and T1 completion before T2. JSON evidence retains the raw source journal hashes and the public
history observed before resumption. Startup alone is not acceptance; a failed assertion leaves its
failure evidence and the fixture must still be stopped with `replicas.py stop`.

Workspace and memory finalization can also suspend through Tool Worker. `--completion-timeout` explicitly
bounds observation after Agent Harness Runtime restarts (default 600 seconds, maximum 900); it is separate from the
business tool duration and is retained in the result. If an observation ends before the existing
work completes, use `--continue-record /absolute/path/to/tool-stream-<id>.json` to observe the same
requests and rerun the original strict lifecycle/checkpoint/side-effect checks. This creates another
evidence file, preserves the first failure, and never submits or restarts business work. An actual
protocol assertion failure remains a failure even if both Turns eventually complete.

The source protocol tests are `ManagedToolEventsTest` and
`ManagedPostgresToolEventsIntegrationTest`. They cover future-source buffering, conflicting duplicate
identity, ownership replacement, raw-result capture before log drain, unknown MQ publish ordering,
explicit output gaps, final watermarks, transactional rollback and bounded snapshot previews. The
self_hosted HTTP/local-process case is in `ManagedEnvironmentWorkspaceIntegrationTest`; it loses a
successful output HTTP response and resumes capture from the shared cursor with a replacement Worker.
These tests do not establish ACS or paid-model integration.

## Generated Sandbox artifact delivery

After building the three services and Dataplane test classes, start a new fixture:

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start \
  --state /tmp/managed-artifact-example --mq-profile min-offset \
  --concurrency 1 --callbacks 8
python3 managed-agents/tests/managed-mq-redis/artifacts.py --state /tmp/managed-artifact-example
```

The explicit model calls production `execute` to generate a 2 MiB binary, then the Service's
`publish_artifact` tool to archive and publish it. The Agent requires metadata before continuing
and overwrites the original file. The native Python SDK downloads the original bytes through
the other Agent Service, checks the hash, file membership, one publication and one export task, then
replaces both Agent Harness Runtime and Tool Worker processes and checks the same archived content again. A successor
Turn must complete and the original subprocess effects must each occur once. The tool is enabled
through ordinary toolsConfig; private runtime files are not deliverables. The fixture stops its
owned processes and retains JSON evidence. This is a deterministic model and actual local-process
Sandbox with the explicitly selected Broker fixture, not ACS or paid-model certification.

`ManagedArtifactsTest` and its PostgreSQL subclass cover atomic file/artifact/result/event
commit, replay, relay metadata, rollback, quota rejection without an orphan file, and invalid
hashes. `ManagedSandboxToolExecutorTest` covers immutable export through replacement, actual binary
bytes, private/outside paths and the 16 MiB limit. These tests and the replica acceptance have
different scopes; report their actual results separately.

## Historical checkpoint restore and Fork

Build the three services and Dataplane test classes, then use a new fixture:

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start \
  --state /tmp/managed-checkpoint-example --mq-profile min-offset \
  --concurrency 1 --callbacks 8
python3 managed-agents/tests/managed-mq-redis/checkpoints.py --state /tmp/managed-checkpoint-example
```

The explicit model writes and reads actual Sandbox version files, a 2 MiB binary and a 0555
directory. It verifies both model context and physical content after restoring a historical
checkpoint. The verifier first saves a Workspace image through Fork, advances the source,
replaces both Agent Harness Runtime and Tool Worker replicas, and requires independent targets and source restoration.
It also requires a historical checkpoint without a matching Workspace image to be rejected.
Fork copies the selected immutable file membership; later uploads and Artifact publications
stay independent resources. Each phase, original Turn and operation identity is retained.
Root Sessions advertise restore and Fork; child Sessions keep those capabilities disabled.
The completed `checkpoint-history-x` experiment used the prior capability declarations;
current API contract tests separately verify the advertised flags. All fixture processes
are stopped after the experiment, while the database, private objects and evidence remain.

## Native webhook acceptance

Build Agent Service, Agent Harness Runtime, Tool Worker and `service-scheduler` with their test classes. Use a new fixture:

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start \
  --state /tmp/managed-webhook-example --mq-profile min-offset --webhooks \
  --concurrency 1 --callbacks 8
python3 managed-agents/tests/managed-mq-redis/webhooks.py --state /tmp/managed-webhook-example
```

The explicit `--webhooks` flag permits a loopback HTTP receiver only in this owned fixture.
`webhooks.py` freezes the Scheduler JAR and starts the production `AgentServiceWebhookWorker` from
a test-only main, without unrelated Channel/Cron schedulers. The first receiver accepts a signed
notification, while a local proxy drops its Agent Service receipt. The script kills that Worker and starts
two replacements against different AgentServices. It waits for the real claim timeout, requires the same
delivery ID with a newer attempt, validates HMAC over each original request body and deduplicates
receiver side effects. Native Turn notifications stay ordered; a later Artifact with an event ID
longer than 64 characters is found from idle without a Agent Service wakeup. All event URLs and scoped
cursors must read the actual native journal. The script records retained evidence and stops every
manifest process, including replacement Workers. This proves the network worker, not a full
Scheduler Spring application or the formal container deployment.

## Persistent input waits

`input-wait.py` exercises the actual Agent Service / Agent Harness Runtime / Tool Worker deployment, with a deterministic
model and a local process Sandbox. Start a **new** fixture with `--concurrency 1 --callbacks 8`
and an explicitly selected MQ profile. The fixture model supplies a schema-only `ask_user` tool
through its test initializer for external-result acceptance; it is not a new production tool or
an implicit configuration fallback.

```sh
python3 managed-agents/tests/managed-mq-redis/input-wait.py \
  --state /tmp/managed-replicas-example --scenario confirmation --seconds 45
python3 managed-agents/tests/managed-mq-redis/input-wait.py \
  --state /tmp/managed-replicas-example --scenario external
python3 managed-agents/tests/managed-mq-redis/input-wait.py \
  --state /tmp/managed-replicas-example --scenario external-error
python3 managed-agents/tests/managed-mq-redis/input-wait.py \
  --state /tmp/managed-replicas-example --scenario cancel
python3 managed-agents/tests/managed-mq-redis/input-wait.py \
  --state /tmp/managed-replicas-example --scenario capacity
```

Each wait must persist a pending action checkpoint and release its execution token. Reply scenarios
kill both Agent Harness Runtime processes, admit and deduplicate a reply across both AgentServices, require one durable
Resume intent while Agent Harness Runtime is absent, then restart Agent Harness Runtime and audit the same Turn and its FIFO
successor. The capacity scenario requires waiting executions observed on both replicas and an independent
Session to complete with one active Harness slot per replica. If waiters initially land on one
consumer, it adds bounded ownership probes, with at most six waiters. `--continue-record` retains
the original capacity experiment and its failure file while establishing the same two-owner
condition; original work is not resubmitted. Both external reply cases require the actual model
to observe the submitted content and success/error state, one matching public tool completion,
and a completed original Turn followed by its successor. H2 / real-PG race coverage is in
`ManagedInputSuspensionsTest` / `ManagedPostgresInputSuspensionsIntegrationTest`, including reply,
cancel, archive, invalid associations and unsettled Tool Worker work. Actual Harness external reply and
cancellation coverage is in `ManagedFailureIntegrationTest`.

## Shared parent/child callback saturation

`ManagedTurnLanesTest` and `RocketMqManagedMessageBusIntegrationTest` hold one callback at
all 17 supported transport depths, complete descendants, then release the queued root
successor. `ManagedSubagentHarnessIntegrationTest` also runs the actual parent/child
Harness pipeline with a single root callback. Neither test claims that a default leaf
Harness can recursively spawn 16 children; existing Harness depth/tool restrictions apply.

For actual two-Agent Harness Runtime admission, persistence, reasoning and Sandbox execution:

```sh
python3 managed-agents/tests/managed-mq-redis/replicas.py start --state /tmp/managed-depth-example --concurrency 1 --callbacks 1
python3 managed-agents/tests/managed-mq-redis/depth-capacity.py --state /tmp/managed-depth-example --seconds 30
python3 managed-agents/tests/managed-mq-redis/replicas.py stop --state /tmp/managed-depth-example
```

The check submits five parent Sessions and a queued successor, requires evidence that
parents and children progress with one shared Turn callback slot per replica, and
verifies one physical tool execution per child, durable checkpoints and final replies.
It preserves a `passed=false` result before starting and retains failures. The deterministic
model and real local-process Sandbox are explicit fixture choices, not ACS validation.

The saturation check includes Harness file/memory operations in its observation budget;
a 30-second tool does not imply a 30-second logical Turn with one Tool Worker slot. To continue
observing already admitted work after an observer timeout, pass `--continue-record` with
the failed `depth-capacity-*.json`. This submits no new work and writes a distinct result
that references the preserved original failure. All completion, checkpoint and side-effect
assertions remain required.

## Initial Lite offset capability gate and explicit Broker derivative

The unmodified SDK 5.2.2 / Broker 5.5.1 baseline skipped the first message on 49 of 100
fresh LiteTopics in `/tmp/managed-lite-first-message-0ce5505c8f86`. Its group already had
`consumeFromMinEnable=true` and its Broker had `initPopOffsetByCheckMsgInMem=true`.
`PopLiteMessageProcessor.getPopOffset` still initializes through MAX, whose fallback is
`maxOffset-1`. A group flag alone is therefore insufficient for this baseline.

The small `rocketmq-lite-min-offset.patch` makes the Lite initial mode follow the group's
existing consume-from-min flag; an existing offset and explicit reset remain unchanged.
`build-lite-min-offset-image.sh` verifies the official source SHA-256, pins the local base
by its image ID, and builds **a test-only derivative**, `managed-test/rocketmq:5.5.1-min-offset`.
The separate Compose project/volume uses port 19982; the official negative baseline remains
on 19981. `broker-lite-min-offset.conf` disables the in-memory shortcut so the gate checks
MIN semantics directly. This is not an upstream release or an automatic production fallback.

```sh
sh managed-agents/tests/managed-mq-redis/build-lite-min-offset-image.sh
docker compose -p managed-mq-redis-lite-min-offset -f managed-agents/tests/managed-mq-redis/compose-lite.yaml -f managed-agents/tests/managed-mq-redis/compose-lite-min-offset.yaml up -d --wait namesrv mq-store-init broker topics proxy
python3 managed-agents/tests/managed-mq-redis/lite-initial-offset.py --profile min-offset --mode live --state /tmp/lite-min-live
python3 managed-agents/tests/managed-mq-redis/lite-initial-offset.py --profile min-offset --mode offline-restart --state /tmp/lite-min-restart
python3 managed-agents/tests/managed-mq-redis/lite-initial-offset.py --profile min-offset --mode offline --from-last --state /tmp/lite-last-control
```

The raw-SDK gate uses 100 independent LiteTopics and two Broker-confirmed messages per
LiteTopic. Offline modes create the consumer only after publication; the restart mode
also restarts Broker/Proxy with the same volume before that first consumer. `--from-last`
is a control that requires the existing MAX behavior. Every run starts a distinct state
directory, records all send receipts and writes failures without replacing earlier results.
The driver compiles `LiteInitialOffsetProbe.java` for Java 17 using the specified SDK JAR.

The Proxy's system-topic bootstrap requires confirmed NameServer registration.
`mqadmin` can print a cluster error and exit zero, so `init-lite-proxy.sh` checks the actual
registration row and successful topic creation instead of relying only on exit status.

After the capability gates pass, initialize the integration parent families in this explicit
project and select the matching endpoint for Java tests:

```sh
docker compose -p managed-mq-redis-lite-min-offset -f managed-agents/tests/managed-mq-redis/compose-lite.yaml -f managed-agents/tests/managed-mq-redis/compose-lite-min-offset.yaml exec -T broker sh /opt/managed/init-topics.sh
# Java real-protocol tests: MANAGED_MQ_TEST_ENDPOINT=127.0.0.1:19982
python3 managed-agents/tests/managed-mq-redis/replicas.py start --mq-profile min-offset --state /tmp/managed-min-example --concurrency 1 --callbacks 1
```

Replica manifests pin the chosen profile, endpoint and derivative status; restart uses that
same choice. A production deployment must provide tested earliest-offset behavior for all
three channels. The test derivative's pass cannot certify unmodified 5.5.1 or a managed MQ
provider, and integration success does not imply formal production deployment.

## Current Tool Worker boundary regression

The earlier `agent_service_bootstrap_smoke.py` / `agent_service_postgres_smoke.py`
HTTP runtime fixtures have been retired. Current process acceptance uses this directory's
native `replicas.py`, `checkpoints.py`, tool-stream, cancellation and Tool Worker-restart cases.
Java `ManagedEnvironmentWorkspaceIntegrationTest` and `ManagedCheckpointWorkspaceIntegrationTest`
now drive the Agent Harness Runtime administrative Outbox, Tool Worker command consumer, immutable target and result
consumer used in production. `ManagedAdministrativeWorkerTest` covers unknown takeover, late
results and conflicts for both workspace phases. `ManagedToolWorkerRealInfrastructureTest` runs the
same Agent Harness Runtime/Tool Worker failure cases with separate PostgreSQL databases, real Redis and RocketMQ Lite.

Depth Topic consolidation requires draining the old deployment before switching producers and
consumers; see [the coordinated cutover procedure](../../deploy/README.md#consolidated-turn-topic-upgrade).
Existing historical depth-lane test evidence does not prove that the new shared pool works.

### Runtime long Suspend / proactive Turn ACK

`RocketMqTurnAcknowledgementsIntegrationTest` runs two independent production SDK consumers with real
RocketMQ and PostgreSQL, a 60-second Turn Suspend and an actual journal terminal commit. Its scenarios
cover peer ACK at roughly five seconds, stopping the original consumer before peer recovery, and a
natural 60-second receipt expiry followed by a fresh receipt. Every scenario asserts that T2 stays
behind T1 and starts within five seconds after the durable terminal, without waiting out the Suspend.
Tool results are controlled fixture results; this test does not call an LLM or external MCP server.
Use exclusively owned fixtures/databases: the test consumes the default five Lite channel families.

```sh
MANAGED_MQ_TEST_ENDPOINT=127.0.0.1:19981 \
MANAGED_DATABASE_TEST_URI=jdbc:postgresql://127.0.0.1:16991/managed_mq_lite_test \
mvn -B -ntp -T1 -pl managed-agents/common -am test \
  -Dtest=ManagedTurnAcknowledgementsTest,ManagedPostgresTurnAcknowledgementsIntegrationTest,ManagedLitePushConsumerTest,RocketMqTurnAcknowledgementsIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Only the TURNS transport configured for this feature persists expiring private receipt capabilities;
other Push channels retain SDK-owned acknowledgement. The SDK bridge is pinned to 5.2.2 and waits for
all same-LiteTopic Suspend operations in a cached batch before exposing the head for proactive ACK.
