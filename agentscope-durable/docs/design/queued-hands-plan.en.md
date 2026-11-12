# Brain–Hands queued execution

[中文](queued-hands-plan.md)

Implemented in the Java Durable framework. The current cross-repository scope is recorded in the
[implementation plan](../archive/platform-implementation-plan.en.md) and [verification](../verification/verification.en.md).

- Runtime, HarnessAdapter, SessionBackend and Executor remain the four primary concepts.
- Hands uses an embeddable WorkerRuntime engine. An in-process Brain connects through
  `LocalWorkerExecutor`; Hands may also be exposed over HTTP or deployed as an independent MQ service.
- RocketMqRemoteExecutor and RocketMqWorker use three NORMAL topics derived from namespace and route: execution commands, controls and updates. Controls have an independent consumer/group so execution backpressure does not block cancellation.
- Durable intent authorizes execution. Hands commits admission before ACK; output/result publication is persisted and retried. Brain deduplicates continuous source events and repairs inbox-to-Session commits.
- CANCEL before EXECUTE creates a tombstone. Failed publication does not rerun tools. Fenced ownership and stable execution IDs protect receipts; external UNKNOWN results are not fabricated or blindly repeated.
- Output uses byte cursors, explicit gaps and a sealed final page. Large envelopes use shared ObjectStore references with scoped integrity checks. SSE reads SessionBackend public events, not raw tool transport.
- Brain and Hands may use separate stores. Worker replicas share their ledger; Brain replicas share delivery records and Backend. SDK clients, stores and providers remain host-owned.
- SQLite/HTTP/PostgreSQL contracts, SDK fault tests and real RocketMQ Broker/Proxy plus independent JVM recovery have passed. Production cloud/cluster/load acceptance remains separate.

See the [standalone echo example](../../durable-examples/queued/queued-hands/README.en.md) and
[queued Sandbox Hands example](../../durable-examples/queued/sandbox-hands/README.en.md). No Managed Agents dependency is required.
Offline protocol tests and real-broker acceptance of the new Sandbox example are recorded separately;
the existing framework acceptance above does not establish that the new example has been broker-tested.
