# Durable Spring Boot examples

[简体中文](README.md)

`agentscope-durable-examples` groups scenarios by the Brain/Hands deployment model. `integrated`
and `queued` are Maven category aggregators; each leaf scenario has its own dependencies, Spring
Boot configuration, executable JAR, and tests.

```text
examples/
├── integrated/                 # No queue boundary between Brain and Hands
│   ├── embedded/               # Minimal in-process integration
│   ├── http-server/            # HTTP API and SSE host
│   └── sandbox-hands/          # Colocated Brain + Sandbox Hands, restart recovery
└── queued/                     # Queue-decoupled, independent Brain and Hands processes
    ├── queued-hands/           # RocketMQ + echo Hands
    └── sandbox-hands/          # RocketMQ + Sandbox Hands
```

The distinction is **whether a queue separates Brain and Hands**, not whether HTTP or restarts
are involved. The `start` and `resume` JVMs in `integrated/sandbox-hands` run sequentially, with
Brain and Hands colocated in each JVM. `http-server` is an API-only host: it starts neither Brain
nor a queue Worker.

## Integrated

| Scenario | Demonstrates | artifactId | Configuration prefix |
| --- | --- | --- | --- |
| [`embedded`](integrated/embedded/README.en.md) | In-process Brain; one model invocation for the same request across restarts | `agentscope-durable-example-embedded` | `durable.embedded` |
| [`http-server`](integrated/http-server/README.en.md) | Spring Boot HTTP API, cancellation, history, and SSE | `agentscope-durable-example-http-server` | `durable.http-server` |
| [`sandbox-hands`](integrated/sandbox-hands/README.en.md) | Colocated Brain → Sandbox Hands → Brain, original-execution recovery and safe cleanup | `agentscope-durable-example-sandbox-hands` | `durable.sandbox-hands` |

```sh
bash agentscope-durable/durable-examples/integrated/embedded/run.sh
bash agentscope-durable/durable-examples/integrated/http-server/run.sh
bash agentscope-durable/durable-examples/integrated/sandbox-hands/run.sh
```

## Queue-decoupled

| Scenario | Demonstrates | artifactId | Configuration prefix |
| --- | --- | --- | --- |
| [`queued-hands`](queued/queued-hands/README.en.md) | RocketMQ 5 separates Brain and echo Worker | `agentscope-durable-example-queued-hands` | `durable.queued-hands` |
| [`sandbox-hands`](queued/sandbox-hands/README.en.md) | RocketMQ 5 separates Brain and Sandbox Worker; Worker persists resource bindings and execution records | `agentscope-durable-example-queued-sandbox-hands` | `durable.queued-sandbox-hands` |

Each queued scenario runs `brain` and `worker` roles from the same JAR in independent processes,
with separate data directories and message-only execution handoff. The Sandbox scenario defaults
to a separate MQ namespace to avoid sharing topics with the echo scenario. See the scenario guides
for broker setup and two-process launch instructions.

Without a broker, verify packaging only:

```sh
bash agentscope-durable/durable-examples/queued/queued-hands/run.sh --compile-only
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only
```

## Build and run

Run from the repository root, for example `embedded`:

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-embedded -am package

java -jar agentscope-durable/durable-examples/integrated/embedded/target/agentscope-durable-example-embedded.jar \
  --durable.embedded.data-directory=./durable-embedded-data
```

Each category's `pom.xml` aggregates its children. To build multiple scenarios, list their
artifactIds separated by commas in `-pl`, keeping `-am` to build dependencies. Spring Boot
command-line arguments, environment variables, and external `application.yml` files can override
the defaults.

## Offline verification

Run all example tests without requiring the full upstream integration acceptance environment:

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-embedded,:agentscope-durable-example-http-server,:agentscope-durable-example-sandbox-hands,:agentscope-durable-example-queued-hands,:agentscope-durable-example-queued-sandbox-hands \
  -am -Djacoco.skip=true \
  -Dtest=EmbeddedExampleTest,HttpServerApplicationTest,SandboxHandsExampleTest,QueuedHandsExampleTest,QueuedSandboxHandsExampleTest,QueuedSandboxHandsRoundTripTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP tests run a real Tomcat on a random port and check cross-client submission, queries,
cancellation, and SSE. Queued Sandbox tests use local commands and in-memory transport to verify
Durable queue protocols, result application, and idempotency. They do not replace real RocketMQ
SDK, broker, or multi-host fault acceptance. No example requires a model API key.

These modules only demonstrate host integration, are excluded from the framework BOM, and must
not be used as application runtime dependencies.
