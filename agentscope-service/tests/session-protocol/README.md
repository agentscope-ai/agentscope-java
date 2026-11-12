# Managed Session protocol verification

All fixtures run locally. They do not contact Anthropic, a model provider or the deployed cluster.
`baseline.json` pins the official SDK versions. `schema/` contains the Python SDK's generated JSON
schemas; schemas include future features so they do not imply this Service implements those features.

Install the Python requirements into the project virtualenv, run `npm ci` in `node/`, then:

```sh
mvn -B -ntp -f managed-agents/tests/session-protocol/java/pom.xml package dependency:build-classpath -Dmdep.outputFile=target/classpath
MANAGED_AGENTS_OFFICIAL_SDK_TESTS=true mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=ManagedSessionProtocolTest,ManagedSessionTurnsApiTest,SessionApiContractTest,ManagedSessionClientTest
```

The actual Agent Service HTTP/authentication/JPA contract fixture starts the official Python,
TypeScript and Java clients as separate processes. Each receives a new isolated Session and checks
create, retrieve, send and pending event history. Python and Java use strict response validation.
Native platform SDK tests additionally cover explicit intents, original credentials, keys and SSE
scope checks. Runtime/Tool Worker tests cover checkpoints, suspension, cancellation and real local
HTTP MCP execution independently of observer transport.

For PostgreSQL, start a disposable local database with user `codex_protocol`, database
`managed_protocol_fixture` and password `protocol_fixture_only`. Expose only a random loopback port.
Set `MANAGED_PROTOCOL_TEST_JDBC=jdbc:postgresql://127.0.0.1:PORT/managed_protocol_fixture` and run
`ManagedSessionProtocolTest`. The test resets schema `ma` between cases, and rejects other URL forms.
Remove the disposable database after verification. Never point this fixture at an existing database.

Cluster acceptance scripts use the new protocol after a separate deployment. They are not run by this
local verification task; existing cluster images and prepared Sessions remain on the previous version.
