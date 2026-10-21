# Alibaba Cloud RocketMQ

Use the same endpoint and authorized credentials in the workload Secrets for Session Portal, Brain and Hands:

```yaml
stringData:
  BUILDER_MANAGED_MQ_ENDPOINTS: "<VPC endpoint>:8080"
  BUILDER_MANAGED_MQ_ACCESS_KEY: "<instance username>"
  BUILDER_MANAGED_MQ_SECRET_KEY: "<instance password>"
```

Keep credentials out of Git and Helm values. Both credential fields must be configured together. Omitting both preserves unauthenticated self-hosted Broker support. Set Helm `managed.mq.ssl` according to the instance endpoint requirements.

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

## Explicit optional configuration

Self-hosted deployment remains the default. `managed.mq.existingSecret`, `data.existingSecret` and `schema.dataExistingSecret` are empty. No cloud resource, endpoint, namespace, account or image is preconfigured.

To use a separately managed MQ installation, copy `deploy/cloud-mq.env.example` to a private file and fill your own endpoint and credentials. Create a Secret in your chosen namespace and explicitly set `managed.mq.existingSecret=<your-secret>`. It is loaded only by Portal, Brain and Hands. Keep migration credentials in a separate Secret.

The optional initializer requires your own instance and region:

```sh
python3 agentscope-service/deploy/managed-cloud-mq-init.py --instance <your-instance> --region <your-region>
```

## Execute publication before task authorization

Ordinary tool calls preserve a complete inert proposal (`authorized=false`), send Execute and obtain a Broker ACK, then commit the checkpoint, `tool_task` and authorization receipt (`authorized=true`) under the Session row lock. The historical `ma.tool_command_outbox` name remains, but the table is now a proposal/receipt ledger. There is no background Execute publication scan or publication lease. A failed proposal write or uncertain MQ response cannot authorize the task.

Worker reads Runtime admission facts, without reading checkpoint contents or coordinating inference. Under the same Session lock, it verifies the authorized receipt and task: committed tasks are admitted durably and acknowledged; an uncommitted attempt with a current execution fence returns a short Suspend; an uncommitted command is discarded only after durable epoch/token replacement or Turn termination. Neither a missing row, lease expiry nor a database error proves rollback. Database outages retain the MQ message. A committed task takes precedence over epoch changes.

The original TURNS message remains until Turn completion. After a pre-commit R1 crash, TURNS redelivery lets R2 acquire the execution fence and recover that Turn's complete proposal, preserving the batch, logical call identities, arguments and configuration. R2 obtains its own Execute ACK before task authorization. A post-commit crash restores the original task; even a lost database commit response does not authorize a second physical execution. Worker execution persistence, lease takeover, result events and idempotency remain intact. Admission checks committed cancellation inputs and persists a cancellation tombstone before waking execution. Independent cancellation-intent forwarding remains active for already admitted work and later cancellations. Session sequencing continues to prevent T2 from passing unfinished T1.

The additive `managed-tool-submission.sql` migration marks historical commands authorized by default. Before upgrading, stop admission and drain unsent commands with the old version; this version does not scan and resend historical Execute rows. Apply the schema migration, then switch Runtime and Worker together. Old Workers do not implement the authorization gate and must not consume commands from new producers. Worker requires access to the shared Runtime authorization tables and permission to lock the Session row.

Local protocol tests: `ManagedToolSubmissionTest` and `ManagedPostgresToolSubmissionIntegrationTest`. Actual HTTP MCP recovery before/after commit: `ManagedMcpHttpIntegrationTest`. Enable PostgreSQL tests only against a dedicated local database using `MANAGED_DATABASE_TEST_URI`.
