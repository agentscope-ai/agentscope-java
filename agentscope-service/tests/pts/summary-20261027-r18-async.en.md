# Managed Agents Performance Acceptance Report

Test date: 2026-10-27 | Run: R18 | Version: `2.1.0-async-20261027-r18`

## Conclusion

**All 49,509 Turns completed successfully with application outputs verified. Strict sustained 10,000-concurrency acceptance failed.** The main pressure remains in execution-commit scheduling and PostgreSQL, whose CPU was nearly saturated. Execution, pending message publication and query projections eventually drained.

## Environment and resources

| Component | Instances | Resources per instance |
|---|---:|---|
| Brain (Agent Harness Runtime) | 4 | 8 vCPU / 16 GiB |
| Hands (Tool Worker) | 2 | 4 vCPU / 8 GiB |
| Agent Service | 2 | 8 vCPU / 16 GiB |
| Gateway | 2 | 4 vCPU / 8 GiB |
| Control Plane | 2 | 2 vCPU / 4 GiB |
| MCP test tool service | 5 | 4 vCPU / 8 GiB |
| PostgreSQL | 1 database instance | 12 vCPU / 48 GiB |
| Load-driver main container | 4 | Request: 2 vCPU / 4 GiB; limit: 4 vCPU / 8 GiB |

Business services ran on Kubernetes. RocketMQ provided message scheduling with 64 stable shards and seven Topic/Group pairs. Redis, OSS object storage and TableStore SSD query storage were also used. The test used isolated database, messaging and query data, with no scaling or resource changes during the run. Driver resources exclude sampling sidecars.

## Test method

| Item | Configuration |
|---|---|
| Target concurrency | 10,000; four drivers with 2,500 each |
| Ramp | 60 seconds |
| Sustained window | 16:33:24–16:38:34 China Standard Time; 309.97 seconds |
| Tool scenario | One MCP tool call per Turn, with an actual 30-second wait |
| Model | Deterministic streaming test model; real LLM service latency excluded |
| Scope | Admission, Brain inference, Hands execution, suspension/resumption, final output and query projection |

The drivers issued **155,750 HTTP requests**, created **56,731 Sessions** and admitted **49,509 Turns**. HTTP counts include Session creation, Turn admission and output reads. There were no manual requeues, restarts or data clearing during load or drain.

## Results and utilization

| Metric | Result |
|---|---:|
| Successful Turns / verified application outputs | 49,509 / 49,509 |
| Business execution failures | 0 |
| Sustained successful throughput | 122.64 Turns/s |
| End-to-end latency p50 / p95 / p99 | 72.07 / 117.98 / 137.11s |
| Tool dispatch to Hands p95 | 5.02s |
| Tool result to Brain resumption p95 | 14.31s |
| Execution after Brain resumption p95 | 40.94s |
| Independently sampled peak active Turns | 9,997 |
| Sustained median / minimum active Turns | 9,969 / 9,519 |
| PostgreSQL WAL rate | 45.43 MiB/s |
| Projection catch-up after the last Turn completed | 721.67s, approximately 12 minutes |

| Component | Mean sampled CPU during sustained load | Sampled peak |
|---|---:|---:|
| Brain | 66.6% | 83.9% |
| Hands | 43.5% | 62.5% |
| Agent Service | 24.8% | 27.0% |
| PostgreSQL | 99.91% | 99.92% |

CPU figures use available instance samples; PostgreSQL uses complete-minute samples inside the sustained window. Equivalent CPU data was not collected for other components. Stage p95 values must not be added. Projection catch-up was confirmed by a full watermark scan.

There were no database deadlocks, business Pod restarts or connection-pool timeouts. **One HTTP 500** recovered through retry. There were **63 MQ SDK throttled send attempts**, with no pending publication backlog remaining.

## Acceptance gaps and final status

| Criterion | Result |
|---|---|
| Sustained concurrency | Peak 9,997; only 91.10% of samples reached 9,800, below the required 99% |
| Observation completeness | Maximum concurrency sample gap: 1.057s, above one second; sustained-window coverage: 11 unique samples versus 16 required |
| Independent per-request MCP audit | Log collection disconnected; 2,678 requests still lacked complete start/completion records after recovery; failed |
| Query projection catch-up | 721.67s, within the 900-second observation threshold; passed |

Independent MCP counter deltas totaled 49,509 starts, 49,509 completions and zero cancellations, matching application results. Aggregate counts do not replace missing per-request audit records.

**Final status: business processing completed; overall performance acceptance failed.** Drivers were archived and deleted. The cluster is healthy and business resource sizes remain unchanged.
