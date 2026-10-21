# HTTP MCP long tool fixture

An independent, authenticated MCP server for testing Managed Turn suspension, automatic Resume,
context recovery and FIFO ordering. `long_wait(marker, seconds=90)` performs an actual asynchronous
wait and returns a random call ID, timestamps and Pod identity. It has no shell, file or business
operations. Each physical invocation emits one start and one completion record to stdout.

The server uses the [official MCP Python SDK v1.26.0](https://github.com/modelcontextprotocol/python-sdk/tree/v1.26.0)
with stateless Streamable HTTP and JSON responses. Two replicas share a ClusterIP Service without
session affinity. Execution identities and complete results are shared through PostgreSQL.
Requests use `agentscope-service-long-tool-mcp-auth`; the database URI uses the separate
`agentscope-service-long-tool-mcp-execution` Secret. No volume is required. See the
[recovery contract](../../docs/mcp-execution-recovery.en.md) / [中文](../../docs/mcp-execution-recovery.md).

For the deployed Agent, prepared Session and manual API commands, use the
[Chinese walkthrough](../../docs/managed-mcp-long-tool-cluster-walkthrough.md) / [English walkthrough](../../docs/managed-mcp-long-tool-cluster-walkthrough.en.md).

## Container checks

From this directory, build and start a container with a local test token:

```bash
docker build --platform linux/amd64 -t agentscope-service-long-mcp-check .
export MCP_TEST_TOKEN="$(openssl rand -hex 24)"
export MCP_EXECUTION_DATABASE_URI="postgresql://FIXTURE_USER:PASSWORD@POSTGRES_HOST/DATABASE"
# Initialize execution-store.sql as owner; grant schema USAGE, table SELECT/INSERT,
# and UPDATE(state,result) to the fixture account.
docker run --rm --name agentscope-service-long-mcp-check --read-only \
  --cap-drop ALL --security-opt no-new-privileges \
  -e MCP_TEST_TOKEN -e MCP_EXECUTION_DATABASE_URI \
  -p 127.0.0.1:18888:8000 agentscope-service-long-mcp-check
```

In another terminal with the same test token, run `python3 test_server.py -v`. These checks cover
authentication, initialize/listTools, genuine concurrent waits and input bounds. They do not call
a model. The application-level cluster acceptance separately submits two real model Turns and
compares both replies with the physical MCP invocation ID.

## Deployment

`deployment.yaml` pins the tested ACR image by digest, starts two non-root replicas with read-only
root filesystems, and exposes only a ClusterIP Service. Create the named authentication Secret
privately before applying the manifest; never put its token in source or test transcripts.
`cluster-test.json` contains public test resource IDs, not credentials. The Agent's MCP headers
are configured separately through the authenticated management API.

The fixture persists admission/results and continues independently of caller disconnects. It
does not restart a physical wait after the MCP server Pod itself dies. This covers Hands
RPC recovery, not MCP-server execution recovery, Sandbox recovery or Broker-restart coverage.

## Repeat automated acceptance

Use `verify_session.py --help` for the real model acceptance runner. Supply a separate Session ID,
a private login JSON (`--token-file`), and an ignored output directory. The runner refuses the
prepared manual Session. With `--kubeconfig`, it also checks one physical invocation in MCP logs,
identical call IDs in both model replies, and committed PostgreSQL suspension/Resume facts.
A normal run intentionally spends at least 90 seconds in the real tool. Tokens are not written
to acceptance reports. This is separate from the four model-free HTTP contract checks above.

## Two-replica owner Pod restart acceptance

`verify_recovery.py` creates a separate Session and runs the same real acceptance. It requires
two ready replicas of all five components, locates the actual Runtime execution owner or the
Hands that first launched `mcp.call`, and deletes only that Pod with a one-second termination
grace while the physical tool is running. A healthy peer remains available. This injects a Pod
failure; it is not a graceful deployment rollout. The prepared manual Session is preserved.

```bash
python3 agentscope-service/tests/http-mcp-long-tool/verify_recovery.py \
  --component brain \
  --token-file /private/login.json --kubeconfig /private/kubeconfig \
  --out-dir /private/new-runtime-recovery-run --seconds 90
```

Use `--component hands` with a new output directory for the Worker case. Run cases in
sequence so each starts with two healthy replicas. Success requires both Turns to finish in
FIFO order, one physical start/completion, applied injected input and restored replicas. Runtime
recovery additionally requires execution on another host. Failures retain API states, owner
logs, physical invocation records and PostgreSQL facts in private files and exit nonzero.

The initial [two-replica acceptance](../../docs/master-sync-20261021.md) exposed the missing lookup path.
The [follow-up cluster acceptance](../../docs/master-sync-20261021.md) passes both owner-Pod restart cases.
The fixture and Worker now implement the advertised recovery extension. One physical invocation
with a blocked T1/T2 still fails acceptance. Run the seven HTTP checks with MCP_TEST_PEER_BASE set
to another process sharing the database to include concurrent deduplication and disconnected-caller
recovery; these checks use no model.

## Connection and wait capacity

Each replica shares an asynchronous PostgreSQL pool (`MCP_DATABASE_POOL_SIZE=16`, minimum 2,
acquisition timeout 10 seconds, at most 256 queued acquisitions). Startup waits for database
readiness; shutdown drains admitted operations before closing the pool. A sleeping tool holds
no connection. Local callers await a shielded execution task; cancellation of an HTTP request
does not cancel the physical operation or result capture. Duplicate calls on another replica
query durable results every `MCP_EXECUTION_POLL_SECONDS=1` seconds instead of every 200 ms.

Run `python -m unittest test_pool_wait -v` for lifecycle/cancellation/wait regressions.
For real pool reuse and cancellation checks, initialize an isolated database using
`execution-store.sql`, set `MCP_POOL_TEST_DSN`, and run `python -m unittest test_execution_pool -v`.
The HTTP suite with two replicas still verifies cross-process deduplication and disconnect recovery.
