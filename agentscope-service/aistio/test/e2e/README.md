# Service API regression

`service_api_regression.py` exercises the actual public HTTP API with the deterministic workers in `examples/service-api`. It creates Jobs and test Applications/credentials, including credential rotation and revocation. Use a disposable deployment and database; it does not delete the resources it creates.

Start the matching Control Plane with PostgreSQL and follow the example's bootstrap instructions. Keep bootstrap running. From `agentscope-service/aistio`:

```bash
export AGENTSCOPE_PLATFORM_TOKEN='TEST_PLATFORM_TOKEN'
PYTHONPATH=sdk/python python test/e2e/service_api_regression.py \
  --config /tmp/service-demo.json --output /tmp/service-api-regression.json
```

The script verifies Agent, Team and Workflow completion, complete multi-message/tool snapshots, paginated event history, SSE suffix replay and live reconnect, principal/scope isolation, idempotency, credential overlap/revocation, cancellation and capability discovery. The output contains Invocation IDs and check results, not credentials. `--targets agent` narrows the execution checks during debugging; the default includes all three targets.

This script does not start or kill services. The broader 2026-10-02 campaign, PostgreSQL migration tests, process-fault experiments, Managed runtime, browser, webhook and remaining capacity/environment coverage are recorded in [the consolidated report](../../../../internal/implementation/session-service-regression-20261002.md).
