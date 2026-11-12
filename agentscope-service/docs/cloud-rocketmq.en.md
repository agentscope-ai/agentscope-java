# Alibaba Cloud RocketMQ

Use the same endpoint and authorized credentials in the workload Secrets for Agent Service, Agent Harness Runtime and Tool Worker:

```yaml
stringData:
  MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS: "<VPC endpoint>:8080"
  MANAGED_AGENTS_RUNTIME_MQ_ACCESS_KEY: "<instance username>"
  MANAGED_AGENTS_RUNTIME_MQ_SECRET_KEY: "<instance password>"
```

Keep credentials out of Git and Helm values. Both credential fields must be configured together. Omitting both preserves unauthenticated self-hosted Broker support. Set Helm `runtime.mq.ssl` according to the instance endpoint requirements.

The implementation requires five LITE parent Topics and consumer groups:

| Topic | Group |
| --- | --- |
| ma_turns_v3 | ma_brain_v3 |
| ma_tool_execute_v1 | ma_hands_execute_v1 |
| ma_tool_control_v1 | ma_hands_control_v1 |
| ma_tool_events_v3 | ma_brain_tool_events_v3 |
| ma_turn_resumes_v3 | ma_brain_resumes_v3 |

Groups must also support LiteTopic Shared and wildcard subscriptions, with FIFO Suspend/resume. NORMAL or FIFO Topics cannot directly replace these LITE Topics.

Enable the cloud LiteTopic capability; confirm or upgrade existing instances through a support ticket as described in the official documentation. Cloud management APIs differ from self-hosted mqadmin initialization. Do not directly reuse `managed-mq-init.sh`; the cloud service also does not require this project to create the internal Proxy heartbeat Topic.

Before switching, verify publish, consume, Suspend/resume, replica takeover and authentication using isolated test resources. Stop new admission and drain the old MQ and database outboxes, then switch all three roles together to avoid stranded messages. Verify text Turns, tool execution, cancellation, subagents and failure recovery before stopping the old MQ; retain its data for rollback.

Official reference: [LiteTopic](https://help.aliyun.com/zh/apsaramq-for-rocketmq/cloud-message-queue-rocketmq-5-x-series/developer-reference/litetopic).

## ACS verification on 2026-10-20

Pod `agencore-ma/managed-agents-cloud-mq-probe` used the production Java SDK 5.2.0, VPC endpoint and Secret credentials. QueryRoute for all five parent Topics received cloud response `40100`, explicitly reporting `the topic of <topic> is not found`. Requests reached the cloud service; consumer permissions and LiteTopic capabilities remain unverified. Create the LITE Topics, configure consumer groups and authorize access in the console before continuing verification. Business deployments have not switched.

## Cloud wildcard configuration verified on 2026-10-21

For instance `rmq-cn-mhb50f8n501`, all five business parent Topics use `LITE`. Configure groups with `messageModel=CLUSTERING`, the matching `topicName`, and `deliveryOrderType=Orderly`. Do not use `LITE_SELECTIVE`: it requires individual client `subscribeLite` calls and does not provide this application's automatic Session LiteTopic consumption. Omit `exclusive` for CLUSTERING; including it returns `InvalidExclusive`.

Ordered delivery requires `FixedRetryPolicy` (1 second and 16 maximum retries in this deployment). Parent Topics use `liteTopicExpiration=-1`; do not reuse the self-hosted Broker expiration range.

An isolated test Topic verified delivery from new LiteTopics without `subscribeLite`, redelivery after `ConsumeResultSuspend`, and suspended-message takeover between two independent LitePushConsumers while preserving A/B completion order. Credentials remain in a Kubernetes Secret.

Migration images pin the existing ACS image digests and replace only `ManagedInfrastructureConfiguration.class` inside the common JAR to enable credentials. Entry-by-entry comparison confirms all other JAR/class contents remain unchanged, preserving the deployed ACK and tool discovery changes. The changes originated on `codex/cloud-rocketmq` and are now integrated into the main master working directory.

Initialize with `python3 managed-agents/deploy/managed-cloud-mq-init.py --instance <instance-id> --region cn-hangzhou`. Repeated runs preserve compatible resources and stop on incompatible existing resources without deleting them. Set Helm `runtime.mq.existingSecret=managed-agents-cloud-mq`; this Secret is appended only for the three MQ roles and overrides their original workload Secret MQ environment variables.

## ACS cutover outcome

All six Agent Service, Agent Harness Runtime and Tool Worker Pods now use `rmq-cn-mhb50f8n501`, with two healthy replicas per role. Gateway was restarted to clear stale backend connections during cutover.

Acceptance Session: `1fe4a633-9444-5463-9327-e1cc35b9ce1f`. A text Turn completed. A real 90-second MCP call executed once (generation=1), producing one suspension and one resume. T2 stayed queued until T1 completed and then succeeded. Cancellation verified Worker control-message receipt, its durable cancellation fact and a cancelled Turn. The already-running external MCP operation still returned its real succeeded outcome; this does not establish immediate physical cancellation.

As requested, retain the old MQ and Session `ae0f0db1-1c84-55de-8fce-8a259ea618b2`, with one waiting_tool Turn, one accepted Turn and an unknown tool. It was neither cancelled nor rerun and is excluded from successful migration acceptance.

Three credential tests, 46 release tests, formatting checks, Helm lint/render and all six Pods' endpoint, credential-presence, JAR SHA256 and health checks passed. The initial full Java verify had one sandbox workspace restore timing assertion failure; rerunning that test class and the credential tests passed without changing sandbox logic.

Private local evidence is under `managed-agents/release/dist/cloud-mq-cutover-20261021/`, including acceptance, Pod, cloud-resource and image-publication reports plus pre-cutover Helm configuration. Sources are now integrated into the main master working directory, without a Git commit yet.

Sources have now been merged into the main master working directory (no Git commit yet). Helm defaults to `managed-agents-cloud-mq`, so future package deployments do not need an extra MQ Secret override. Pre-create the Secret; a missing Secret prevents Pod startup instead of silently falling back to self-hosted MQ.

For a new environment, copy `deploy/cloud-mq.env.example` to a private file with restricted permissions, fill the instance username/password, then run `kubectl -n agencore-ma create secret generic managed-agents-cloud-mq --from-env-file=<private-file>`. The Secret already exists on the current cluster. Images do not embed endpoints or passwords; the Secret selects the target instance.

The master integration also includes cloud-mq.env.example, the cloud initialization script and both configuration documents in the Kubernetes distribution; installation instructions require the MQ Secret first. Main-directory validation passed three credential tests, 47 release tests, formatting and Helm lint. Full clean verify hit the existing ManagedSessionProtocolTest.inputNotificationsDeduplicateAcrossSourcesInEitherOrder event-order assertion in common (506 tests, one failure, 150 skipped), outside these MQ configuration changes.

## Execute publication before task authorization

Ordinary tool calls preserve a complete inert proposal (`authorized=false`), send Execute and obtain a Broker ACK, then commit the checkpoint, `tool_task` and authorization receipt (`authorized=true`) under the Session row lock. The historical `ma.tool_command_outbox` name remains, but the table is now a proposal/receipt ledger. There is no background Execute publication scan or publication lease. A failed proposal write or uncertain MQ response cannot authorize the task.

Worker reads Runtime admission facts, without reading checkpoint contents or coordinating inference. Under the same Session lock, it verifies the authorized receipt and task: committed tasks are admitted durably and acknowledged; an uncommitted attempt with a current execution fence returns a short Suspend; an uncommitted command is discarded only after durable epoch/token replacement or Turn termination. Neither a missing row, lease expiry nor a database error proves rollback. Database outages retain the MQ message. A committed task takes precedence over epoch changes.

The original TURNS message remains until Turn completion. After a pre-commit R1 crash, TURNS redelivery lets R2 acquire the execution fence and recover that Turn's complete proposal, preserving the batch, logical call identities, arguments and configuration. R2 obtains its own Execute ACK before task authorization. A post-commit crash restores the original task; even a lost database commit response does not authorize a second physical execution. Worker execution persistence, lease takeover, result events and idempotency remain intact. Admission checks committed cancellation inputs and persists a cancellation tombstone before waking execution. Independent cancellation-intent forwarding remains active for already admitted work and later cancellations. Session sequencing continues to prevent T2 from passing unfinished T1.

The additive `managed-tool-submission.sql` migration marks historical commands authorized by default. Before upgrading, stop admission and drain unsent commands with the old version; this version does not scan and resend historical Execute rows. Apply the schema migration, then switch Runtime and Worker together. Old Workers do not implement the authorization gate and must not consume commands from new producers. Worker requires access to the shared Runtime authorization tables and permission to lock the Session row.

Local protocol tests: `ManagedToolSubmissionTest` and `ManagedPostgresToolSubmissionIntegrationTest`. Actual HTTP MCP recovery before/after commit: `ManagedMcpHttpIntegrationTest`. Enable PostgreSQL tests only against a dedicated local database using `MANAGED_DATABASE_TEST_URI`.
