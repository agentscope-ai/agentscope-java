# Durable Spring Boot examples

[简体中文](README.md)

The `io.agentscope:durable` example aggregator groups scenarios by the Brain/Hands deployment model. `integrated`
and `brain-hands` are Maven category aggregators; each leaf scenario has its own dependencies,
configuration, and tests.

The modules belong to the `agentscope-examples` Maven aggregator. Durable's
`durable-standalone` profile also includes this directory so the launchers and
`make -C agentscope-durable check` continue to build the examples with their library
dependencies. The libraries do not depend on the example modules.

```text
agentscope-examples/durable/
├── integrated/                 # No queue boundary between Brain and Hands
│   ├── quickstart/             # Minimal in-process integration
│   ├── http-server/            # HTTP API and SSE host
│   └── tool-in-sandbox/        # Colocated Brain + Sandbox tool, restart recovery
└── brain-hands/                # Queue-decoupled, independent Brain and Hands processes
    ├── echo-tool/              # RocketMQ + echo tool
    └── tool-in-sandbox/        # RocketMQ + Sandbox tool
```

The distinction is whether a queue separates Brain and Hands, not whether HTTP or restarts are
involved. The `start` and `resume` JVMs in `integrated/tool-in-sandbox` run sequentially, with
Brain and Hands colocated in each JVM. `http-server` is an API-only host: it starts neither Brain
nor a queue Worker.

## Integrated

| Scenario | Demonstrates | artifactId | Configuration prefix |
| --- | --- | --- | --- |
| [`quickstart`](integrated/quickstart/README.en.md) | In-process Brain; one model invocation for the same request across restarts | `agentscope-durable-example-quickstart` | `durable.quickstart` |
| [`http-server`](integrated/http-server/README.en.md) | Spring Boot HTTP API, cancellation, history, and SSE | `agentscope-durable-example-http-server` | `durable.http-server` |
| [`tool-in-sandbox`](integrated/tool-in-sandbox/README.en.md) | Colocated Brain → Sandbox tool → Brain, original-execution recovery and safe cleanup | `agentscope-durable-example-tool-in-sandbox` | `durable.tool-in-sandbox` |

```sh
bash agentscope-examples/durable/integrated/quickstart/run.sh
bash agentscope-examples/durable/integrated/http-server/run.sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
```

## Brain/Hands split: brain-hands

| Scenario | Demonstrates | artifactId | Configuration prefix |
| --- | --- | --- | --- |
| [`echo-tool`](brain-hands/echo-tool/README.en.md) | RocketMQ 5 separates Brain and echo Hands | `agentscope-durable-example-echo-tool` | `durable.echo-tool` |
| [`tool-in-sandbox`](brain-hands/tool-in-sandbox/README.en.md) | RocketMQ 5 separates Brain and Sandbox Hands; Hands persists resource bindings and execution records | `agentscope-durable-example-brain-hands-tool-in-sandbox` | `durable.brain-hands-tool-in-sandbox` |

Each scenario remains one Maven module, but Brain and Hands have separate Java launchers and
example files, packaged as `<artifactId>-brain.jar` and `<artifactId>-hands.jar`. There is no
`role` configuration switch and no single `run()` mixing both sides' resource assembly. Brain
configures only the Agent, Runtime, and remote executor; Hands configures only the Worker and
local tool. Each process uses its own data directory and exchanges requests and results only
through the queue.

The Sandbox scenario defaults to a separate MQ namespace to avoid sharing topics with the echo
scenario. Once a broker is ready, run these commands in separate terminals:

```sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh hands localhost:8081
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh brain localhost:8081
```

The script takes `<brain|hands> <endpoint> [data-directory]`; the optional third argument sets
that process's data directory. `run.sh` selects the corresponding JAR rather than passing a role
property to Java. You can also launch each JAR directly with `java -jar`; see the scenario guides
for entry points, broker setup, and Sandbox operations. Without a broker, verify packaging only:

```sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh --compile-only
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only
```

## Build and run

Run from the repository root, for example `quickstart`:

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-quickstart -am package

java -jar agentscope-examples/durable/integrated/quickstart/target/agentscope-durable-example-quickstart.jar \
  --durable.quickstart.data-directory=./durable-quickstart-data
```

Each category's `pom.xml` aggregates its children. List artifactIds separated by commas in `-pl`,
keeping `-am` to build dependencies. Spring Boot command-line arguments, environment variables,
and external `application.yml` files can override the defaults.

## Offline verification

Select only tests in the example packages without requiring full upstream integration acceptance:

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-quickstart,:agentscope-durable-example-http-server,:agentscope-durable-example-tool-in-sandbox,:agentscope-durable-example-echo-tool,:agentscope-durable-example-brain-hands-tool-in-sandbox \
  -am -Djacoco.skip=true \
  '-Dtest=%regex[io/agentscope/durable/examples/.*Test.class]' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP tests run a real Tomcat on a random port and check cross-client submission, queries,
cancellation, and SSE. Queue tests use local tools and in-memory transport to verify result
application, recovery, and idempotency, and check isolation of the two Spring entry points.
They do not replace real RocketMQ SDK, broker, or multi-host fault acceptance. No example
requires a model API key.

These modules only demonstrate host integration, are excluded from the framework BOM, and must
not be used as application runtime dependencies.
