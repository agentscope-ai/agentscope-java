# Queued Sandbox Hands: RocketMQ Brain + Worker

[中文](README.md) | [Examples](../../README.en.md)

A Spring Boot CLI application with two genuinely separate processes. The offline model needs no API key, but **the application requires a real RocketMQ 5 Broker and gRPC proxy**. The script does not create either or provision topics.

## Process boundary

- **Brain**: `LocalBackend` (SQLite), `DurableRuntime`, the AgentScope ReAct adapter and `RocketMqRemoteExecutor`. It does not construct a Worker, Sandbox client or local tool executor.
- **Worker**: its own `hands.db`, persistent `SandboxBindings`, `LocalSandboxClient` and `RocketMqWorker` (which owns its built-in `WorkerRuntime`). It does not construct a Brain backend, agent or model.
- The shared frozen tool is `sandbox.exec`, version `v1`, route `hands`, target `{"operation":"sandbox.exec"}`. The Worker verifies the business namespace, session, tool digest and exact command/timeout before admitting **every** command, including controls.
- Sandbox resource binding is `(namespace, sessionId, v1, {})`, persisted in the Hands database. Keep the same Worker directory and binding across restarts; attachment must reach the original Sandbox, never a replacement.

The model requests `printf once >> count; printf recovered` with `timeout_seconds=30`. Its call ID is `sandbox-<turnId>`, stable across recovery of the same Turn and independent between Turns. Only a `ToolResultState.SUCCESS` result containing JSON with `output` exactly `recovered` produces `Queued sandbox result applied: recovered`. Failed, cancelled, unfinished or malformed results cannot masquerade as success. Brain verifies the terminal status and final answer; success/failure/cancellation observations all wake its waiter.

## Run two processes

Requirements: Java 17+, Maven, POSIX `sh`, Python 3, and an already running RocketMQ 5 gRPC proxy (default `localhost:8081`). Run from the repository root. Configure Broker ACLs and authentication for your deployment; this local example is not a production credential configuration.

Both roles default to `mq-namespace=durable-sandbox`, separate from the echo Hands example. According to `RocketMqExecutionTopology`, route `hands` requires these pre-provisioned topics:

- `durable-sandbox-hands-commands`
- `durable-sandbox-hands-controls`
- `durable-sandbox-hands-updates`

Derived consumer groups are `durable-sandbox-hands-brain`, `durable-sandbox-hands-worker`, and `durable-sandbox-hands-worker-controls`. Allow the corresponding producer/consumer access. Brain and Worker must use the same MQ namespace, business namespace and session ID. Do not mix independent Brain databases in the same example consumer group.

```bash
# Terminal 1: leave running until shutdown (Ctrl-C).
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh \
  worker localhost:8081 durable-queued-sandbox-hands-data/worker

# Terminal 2: exits after verifying the answer.
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh \
  brain localhost:8081 durable-queued-sandbox-hands-data/brain
```

Wait for `queued-sandbox-hands worker ready`. Brain prints `turn`, `status`, `cursor` and `answer`. Repeat the **same** Brain command, preserving both directories: default `request-1` resolves to the same Turn, and `worker/sandboxes/<resource-id>/count` remains `once`. A new request ID intentionally admits a new Turn and another side effect.

Additional Spring arguments are passed through, for example `--durable.queued-sandbox-hands.mq-namespace=my-sandbox` (set it on both processes and provision its derived topics). Prefix: `durable.queued-sandbox-hands`. Defaults: role `BRAIN`, endpoint `localhost:8081`, namespace `demo`, session ID `conversation`, request ID `request-1`, Brain wait timeout `60s`. Without an explicit directory, each role uses `durable-queued-sandbox-hands-data/brain` or `/worker`.

## Compile and offline tests

```bash
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only

mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-queued-sandbox-hands -am test \
  -Dtest=QueuedSandboxHandsExampleTest,QueuedSandboxHandsRoundTripTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The script builds the executable jar with tests skipped; the second command runs the tests. `DURABLE_MAVEN_REPOSITORY` optionally selects the script's Maven local repository. Jar: `agentscope-durable/durable-examples/queued/sandbox-hands/target/agentscope-durable-example-queued-sandbox-hands.jar`.

Offline tests reuse the **production tool, model and Brain harness**, executing real local commands with separate Brain/Hands SQLite stores. Only command/event delivery is an in-memory fixture using public `QueuedExecutor`, `TransactionalExecutionStore` and `WorkerRuntime` APIs. Tests deliberately duplicate commands/events, withhold result delivery, close/reopen the backend, records, bindings, Worker and queue, then replay results onto the same request/Turn. They verify success, the final answer, original resource attachment and a `count` of only `once`. Regression coverage also checks that a new requestId on the same Thread produces a new command in the original resource, changing `count` to `onceonce`, while duplicate submission of that requestId does not execute again. Other tests cover Spring binding, namespace/session/tool authorization and rejecting false model success.

**These tests do not validate the RocketMQ SDK, Broker, real two-process transport, or process-kill fault recovery.** Run those independent acceptance checks against your provisioned infrastructure; offline coverage is not Broker acceptance evidence.

## Retention and security

`LocalSandboxClient` runs commands with the host user's permissions. It is **not a container or a security isolation boundary**; the command allowlist is a demonstration guard, not a substitute for authenticated callers, Broker ACLs or OS isolation. Use only a trusted development host.

Worker starts binding maintenance and uses a shutdown hook to stop the MQ Worker and maintenance. The default `canDelete=false` is retained: shutdown does **not** request deletion, close the original Sandbox or erase operation evidence. Sandboxes and `count` deliberately remain for recovery inspection. An uncertain effect is not permission to retry in a new workspace.

For production, replace the local resolver with a real `SandboxClient`, retain stable resource identity and durable records, and supply an authoritative cleanup barrier covering every admitted operation, cancellation and UNKNOWN outcome before requesting deletion. Never enable unconditional cleanup or remove recovery evidence while outcomes are unresolved.
