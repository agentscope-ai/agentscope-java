# Brain + Hands: tool in Sandbox

[中文](README.md) | [Examples](../../README.en.md)

One Maven module packages **two independent Spring Boot applications and runners**, rather than a
role-switching application. The offline model needs no API key, but **running either application
requires a real RocketMQ 5 Broker and gRPC proxy**. The script provisions neither infrastructure nor topics.

## Process boundary

- **Brain**: `LocalBackend` (SQLite), `DurableRuntime`, the AgentScope ReAct adapter and
  `RocketMqRemoteExecutor`. It does not construct a worker, Sandbox client or local tool executor.
- **Hands**: its own `hands.db`, persistent `SandboxBindings`, `LocalSandboxClient` and
  `RocketMqWorker` (which owns its built-in `WorkerRuntime`). It does not construct a Brain backend, agent or model.
- `SandboxTool` shares only the frozen tool contract/constants/arguments: `sandbox.exec`, version
  `v1`, route `hands`, target `{"operation":"sandbox.exec"}`. Hands verifies the business namespace,
  session, tool digest and exact command/timeout before admitting **every** command, including controls.
- Resource binding is `(namespace, sessionId, v1, {})`, persisted in the Hands database. Preserve
  the original Hands directory and binding across restarts; attachment must reach the original
  Sandbox, never a replacement.

The model requests `printf once >> count; printf recovered` with `timeout_seconds=30`. Its call ID
is `sandbox-<turnId>`, stable across recovery of the same Turn and independent between Turns. Only a
`ToolResultState.SUCCESS` result with JSON `output` exactly `recovered` produces
`Sandbox result applied: recovered`. Failed, cancelled, unfinished or malformed results cannot
masquerade as success. Brain verifies the terminal status and final answer;
success/failure/cancellation observations all wake its waiter.

## Build and run two processes

Requirements: Java 17+, Maven, POSIX `sh`, Python 3, and an already running RocketMQ 5 **gRPC proxy**
(default `localhost:8081`, not the NameServer address). Run from the repository root. Configure
Broker ACLs and authentication for your deployment; this is not a production credential configuration.

Both applications default to `mq-namespace=durable-sandbox`, separate from the echo tool scenario.
According to `RocketMqExecutionTopology`, route `hands` requires these pre-provisioned normal topics:

- `durable-sandbox-hands-commands`
- `durable-sandbox-hands-controls`
- `durable-sandbox-hands-updates`

Groups are `durable-sandbox-hands-brain`, `durable-sandbox-hands-worker`, and
`durable-sandbox-hands-worker-controls`. Grant the corresponding producer/consumer access. Both
applications must use the same MQ namespace, business namespace and session ID. Do not mix
independent Brain databases in one consumer group.

```sh
# Build both executable JARs without contacting a Broker (tests skipped).
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only

# Terminal 1: leave running until shutdown (Ctrl-C).
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh \
  hands localhost:8081 durable-brain-hands-tool-in-sandbox-data/hands

# Terminal 2: exits after verifying the answer.
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh \
  brain localhost:8081 durable-brain-hands-tool-in-sandbox-data/brain
```

Module `agentscope-durable-example-brain-hands-tool-in-sandbox` produces two files in
`agentscope-examples/durable/brain-hands/tool-in-sandbox/target/`:

- `agentscope-durable-example-brain-hands-tool-in-sandbox-brain.jar`
- `agentscope-durable-example-brain-hands-tool-in-sandbox-hands.jar`

Each starts independently with `java -jar`; for example:

```sh
java -jar agentscope-examples/durable/brain-hands/tool-in-sandbox/target/agentscope-durable-example-brain-hands-tool-in-sandbox-hands.jar --durable.brain-hands-tool-in-sandbox.endpoint=localhost:8081
java -jar agentscope-examples/durable/brain-hands/tool-in-sandbox/target/agentscope-durable-example-brain-hands-tool-in-sandbox-brain.jar --durable.brain-hands-tool-in-sandbox.endpoint=localhost:8081
```

Wait for `tool-in-sandbox hands ready`. Brain prints `turn`, `status`, `cursor` and `answer`.
Repeat the **same** Brain command, preserving both directories: `request-1` resolves to the same Turn,
and `hands/sandboxes/<resource-id>/count` remains `once`. A new request ID intentionally admits a
new Turn and another side effect in the same resource.

The script selects a JAR, not a Java role; valid input is only `--compile-only` or
`<brain|hands> <endpoint> [data-directory]`, with no `worker` alias. Invalid input fails before Maven.
`DURABLE_MAVEN_REPOSITORY` optionally selects its Maven local repository.

## Source, configuration and offline tests

In `src/main/java/io/agentscope/durable/examples/brainhands/toolinsandbox/`,
`SandboxBrainApplication.java` imports only `SandboxBrainExample.java` (assembly/runtime/model);
`SandboxHandsApplication.java` imports only `SandboxHandsExample.java` (worker/bindings/authorization).
Neither application scans the package. `SandboxTool.java` is the shared tool contract;
`SandboxToolProperties.java` contains role-independent configuration and validation.

Prefix: `durable.brain-hands-tool-in-sandbox`. Defaults in `src/main/resources/application.yml`:
endpoint `localhost:8081`, namespace `demo`, session ID `conversation`, request ID `request-1`,
Brain wait timeout `60s`, MQ namespace `durable-sandbox`. Without an explicit `data-directory`, Brain
uses `durable-brain-hands-tool-in-sandbox-data/brain` and Hands uses its `/hands` sibling.
Use direct JAR Spring arguments, environment variables or external YAML for additional properties;
for a different MQ namespace, configure both applications and provision its derived topics.

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-brain-hands-tool-in-sandbox -am test \
  -Dtest=SandboxToolExampleTest,SandboxRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false
```

Offline tests reuse the **production tool, model and Brain harness**, executing real local commands
with separate Brain/Hands SQLite stores. Only transport delivery is in memory, using public
`QueuedExecutor`, `TransactionalExecutionStore` and `WorkerRuntime` APIs. They duplicate commands/events,
withhold result delivery, close/reopen backend, records, bindings, worker and queue, then replay results
onto the same request/Turn. Assertions retain success, final answer, original resource/handle attachment
and `count=once`. A new request on the same Thread dispatches a new command in the original resource,
producing `onceonce`; duplicate submission does not execute again. Tests also cover isolated Spring
contexts without executing runners, property binding, authorization and rejecting false model success.

**These tests do not validate the RocketMQ SDK, Broker, real two-process transport or process-kill
fault recovery.** Those remain independent infrastructure acceptance checks, not claims implied by offline coverage.

## Retention and security

`LocalSandboxClient` runs commands with the host user's permissions. It is **not a container or a
security isolation boundary**; the allowlist is not a substitute for authenticated callers, Broker
ACLs or OS isolation. Use only a trusted development host.

Hands starts binding maintenance and stops the worker/maintenance via a shutdown hook. Default
`canDelete=false` is retained: shutdown **does not** request deletion, close the original Sandbox or
erase operation evidence. Sandboxes and `count` remain for recovery inspection. An uncertain effect
is not permission to retry in a new workspace.

Production should use a real `SandboxClient`, stable resource identity and durable records, and an
authoritative cleanup barrier covering every admitted operation, cancellation and UNKNOWN outcome
before deletion. Never enable unconditional cleanup or erase evidence while outcomes are unresolved.
