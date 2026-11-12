# Storage-free MCP performance fixture

[中文](README_zh.md)

`managed-agents-long-tool-mcp` simulates an external MCP service for platform performance tests.
It requires only `MCP_TEST_TOKEN`: no PostgreSQL, Redis, MQ, OSS, execution ledger or persistent
result lookup. It exposes one tool, `long_wait(marker, seconds)`, using the official MCP Python
SDK with stateless Streamable HTTP and JSON responses. Durations support fractional seconds
from 0.1 to 180, including the 200ms and 30s profiles.

Each call creates a unique call ID, awaits an asynchronous timer, and returns the marker, call ID,
Pod and actual start/finish timestamps. Repeating a marker starts an independent call. Active
operations live only in process memory and disappear on restart. HTTP disconnects do not cancel
the simulated physical wait, but no result lookup or durable recovery capability is advertised.
Platform execution ownership, checkpointing and result persistence remain platform responsibilities.

## Local build and checks

```bash
docker build -t ma-mcp-performance:local .
docker run --rm --name ma-mcp-performance --cpus=2 --memory=4g \
  --read-only --cap-drop ALL -p 127.0.0.1:18888:8000 \
  -e MCP_TEST_TOKEN=local-regression ma-mcp-performance:local
```

Use an isolated Python environment with `requirements.txt` for unit/contract checks:

```bash
python -m unittest -v test_pool_wait test_health test_probe_routes
MCP_TEST_TOKEN=local-regression python -m unittest -v test_server
```

An optional `MCP_TEST_PEER_BASE` adds a second independent HTTP server to the contract test. It
verifies distinct call IDs for repeated markers, not shared storage. Neither server needs a DB.
`deployment.yaml` pins the published storage-free image by digest, with five replicas at the
cluster’s existing 4 CPU / 8GiB per replica and no database environment variables. The
2026-10-26 rollout changes only this fixture; Brain remains at four replicas and Hands at two.
Build and publish a new immutable image before deploying future source changes.
Create new load Sessions after a fixture rollout, because the tool descriptor no longer advertises
the execution-recovery extension. Existing admitted descriptor hashes are not rewritten.

## Observation and independent acceptance

`/healthz` and `/readyz` are process-local probes. Authenticated `/diagnostics` reports active/peak
operations, started/completed/cancelled counts, event-loop lag and trace queue/drop counts.
Start/completion records go through a bounded background stdout writer. Slow logging cannot block
the event loop. Records may be dropped when this diagnostic queue fills; this is observable and
must make an exact physical-call audit fail. No logs are required to execute or restore a tool.

The closed-loop driver reads platform Turn state and captured Hands tool results. It no longer
queries `mcp_fixture.execution`. Captured results alone do not prove physical at-most-once execution.
After a run, collect all MCP replica stdout and final per-Pod `/diagnostics` snapshots, then run:

```bash
python ../pts/audit_mcp_calls.py --batch BATCH \
  --admissions RUN/admissions.jsonl --terminals RUN/terminals.jsonl \
  --logs POD_A.log.gz POD_B.log.gz --diagnostics final-diagnostics.json \
  --out RUN/mcp-audit.json
```

Diagnostics use `{"pods":[{"pod":"POD_NAME","value":{...}}]}`. The audit requires one start and one
completion per admitted marker, matching the captured call ID, no trace loss and drained logs.
`analyze_closed_loop.py` requires this independent audit for a tool run to pass. For multiple driver
shards, audit each run's admissions/terminals against the same complete MCP logs and diagnostics.

A 10,000-client cold connection burst, sustained request rate, and 10,000 long-lived asynchronous
waits are different capacity tests. The local two-container check (2 CPU / 4GiB each) completed 10,000 paced 200ms calls at
500 requests/second with no errors and 205ms response p95. A 20-second ramp of 10,000
30-second calls reached 5,000 active waits on each replica, with no errors and 33.56s response p95.
A zero-ramp cold connection burst did hit TCP connection-establishment timeouts. These checks
are not full-chain cluster acceptance. Record client errors/event-loop lag; a successful
unit test or simply increasing concurrency does not certify fixture capacity. Compare native
fixture overhead separately from the configured tool duration.

## Historical recovery experiments

[Initial recovery failure](dual-replica-recovery.en.md) and [database-backed recovery acceptance](recovery-v1-acceptance.en.md)
record an older fixture that used shared PostgreSQL. They remain historical evidence, not current
performance guarantees. `verify_recovery.py` belongs to that historical owner-restart experiment;
it is not a passing acceptance contract for this storage-free service. The platform's MCP recovery
extension remains available for external servers that actually implement it.
