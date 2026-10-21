# master logic backport (2026-10-21)

[中文](master-sync-20261021.md)

The target is `/Users/ken/agentscope-3/agentscope-java` on `codex/automatic-acr-release`. The source is the standalone Service master (initial commit `2714354`; the tool commit-gate work was committed as `3c6bd55` during synchronization) plus its uncommitted implementation captured for this migration. The extraction baseline is the original working directory at `fbd32ca602647141d96d50ecfe9a3554cdca0cdd`, verified with the 2,224-file SHA-256 manifest and rename records.

The five target files changed since extraction were preserved: Helm values/planes, Gateway configuration, the cluster shell script and cluster guide. Existing Core, Harness, extension, workflow and unrelated edits were preserved. Changes are directly in the target checkout; no worktree, commit, push or deployment was performed.

Full component interactions are documented in [Redis and the Turn lifecycle](redis-usage-and-turn-lifecycle.en.md) and [RocketMQ and the Turn lifecycle](rocketmq-topics-and-turn-lifecycle.en.md).

## Scope

- Session event protocol, Turn/Input semantics, paged history, independent output, Java/Python/TypeScript clients and Console.
- Durable MCP batches, discovery and recovery, runtime continuation, result capture, worker leases and cancellation.
- Unified Turn FIFO routing, durable acknowledgements, MQ Suspend/ACK ordering, replica takeover and coordinated outbox publication.
- Broker ACK before transactional Execute authorization, Worker commit gates, execution fences, Session row locks and recovery across pre/post-commit crashes.
- Gateway idempotent creation retries, upstream reset recovery and Portal/Gateway shutdown draining.

Component directories, Java packages, Go module, SDK distribution names, CLI, images and `BUILDER_*` configuration retain AgentScope identities. Public scope headers remain `X-AgentScope-Tenant/Namespace`, internal headers remain `X-Builder-*`, and new event extensions use `AgentScope-Extensions: turn-inputs-v1` and `agentscope_service`.

## Self-hosted deployment and database upgrade

Self-hosted deployment remains the default. Source instance addresses, resource IDs, namespaces, image digests, cloud accounts, credentials, local environment files and cluster acceptance reports were excluded. Optional Helm `managed.mq.existingSecret`, `data.existingSecret` and `schema.dataExistingSecret` values are empty and `localDev=false`. Existing resource allocations, replica counts and Gateway idle-pool eviction settings are preserved.

The original 283 Service SQL files retain their extraction hashes. Migration ledgers remain `portal`, `dataplane` and `managed`, including the original `managed-hands.sql` filename/checksum. Five additive migrations are registered in order:

1. `managed-session-protocol.sql`
2. `managed-turn-ack.sql`
3. `managed-mcp-batch.sql`
4. `managed-tool-publication-lease.sql`
5. `managed-tool-submission.sql`

Stop admission and drain the old per-depth Turn Topics and unsent Execute/outbox work with the old version. Run schema Jobs with independent owner credentials, rerun `deploy/postgres-roles.sh`, then upgrade Brain and Hands together. Old Workers lack the authorization gate and must not consume new producers' commands. The new version does not scan and republish historical Execute proposals; draining is required.

Hands and Runtime must access the same `ma` authorization data space. Hands receives column-scoped reads for the commit gate and `UPDATE(execution_epoch)` on Session, required by PostgreSQL `SELECT FOR UPDATE`; the gate never updates that column. Checkpoints, native logs, Session configuration and authorization writes remain inaccessible. Brain/Portal still cannot read the Hands physical execution ledger. Actual PostgreSQL regression coverage checks these permissions.

## Follow-up incremental audit (2026-10-21)

Rechecked current master `3c6bd55` and its working tree against the manifest. Every recorded source hash is unchanged, with no new commits or runtime-code differences. The tool commit gate, durable HTTP MCP execution and pooling tests, and Redis/MQ lifecycle documents were included at the end of the previous synchronization. The only additional source files outside that scope are the explicitly excluded cloud-data configuration generator and its tests.

This follow-up aligns both READMEs with MQ-first tool authorization, Brain-side MCP discovery, durable direct continuation versus MQ Resume, and the lifecycle-document links. Existing target code and self-hosted configuration are preserved. The previously failing natural MQ receipt-expiry check remains unresolved. Only documentation and the manifest changed; relative links, hashes and change boundaries were checked without rerunning unchanged Java/Go/frontend suites.

## Verification

| Check | Result |
| --- | --- |
| Root Maven checks (`-T1 clean verify`, resumed after adaptation fixes) | All 99 modules passed across the initial clean build and resumed checks after adaptation fixes |
| Root `mvn -B -ntp -T1 spotless:check` | Passed |
| Actual PostgreSQL / Redis integration | Common: 550 tests, zero failures, seven optional fixture skips; dedicated database |
| Two-process HTTP MCP and durable recovery | 21 passed, including cross-process deduplication, disconnected lookup and pooling |
| PostgreSQL permissions and release/deployment scripts | 49 passed, including actual role-script execution and commit-gate/private-state isolation |
| Console | 189 passed; lint: zero errors, 97 existing warnings; build passed |
| Control Plane | `make build`, `make test` and `go vet ./...` passed |
| Python SDK / DSH | 120 / 32 passed; DSH TypeScript build passed |
| Helm / documentation | Helm lint passed; 16 documentation tests, site build validation and broken-link checks passed |

The build caught and corrected Portal scheduler Bean names and shared test-fixture paths during
backport adaptation. Gateway eviction tests explicitly use a 1s/1s test cycle. The target's existing
10s/5s deployment idle-pool policy is preserved, and the test still requires an observed connection close.

### MQ verification limits

The unmodified RocketMQ 5.5.1 initial-offset negative baseline remains unchanged. Positive checks used
the repository's existing MIN-offset test image: all five raw bus tests passed, including sixteen
nested calls on one callback slot, FIFO and retries. Only the `ma_push_bus_*` stress groups use 100ms
retries and a 1000-delivery budget, preventing Broker backoff from consuming the original 40-second
depth window. Completion, ordering, capacity and timeout assertions remain unchanged; production
initialization settings were preserved.

The real Turn ACK fixture now wires the Execute publisher required by the new protocol. Peer ACK and
original-consumer shutdown takeover passed, with terminal-to-T2 times of approximately 709ms and 716ms.
Natural expiry did not produce a fresh receipt within 65 seconds and remains a failing check. A temporary
no-renewal Proxy experiment also failed to receive the initial Turn; that experimental configuration was
not retained. These results do not constitute deployment-environment MQ acceptance.

No business-cluster deployment, LLM end-to-end acceptance or business-database upgrade was performed.
Dedicated local test containers are cleaned up after verification; existing user containers are preserved.
Source migration does not replace the running old version.

### Baseline for future synchronization

The [per-file manifest](master-sync-20261021.files.json) records source paths/hashes, pre/post-sync target
hashes and both repositories' commit identities for this incremental migration. The source contains
uncommitted work, so its master commit alone cannot reproduce the input. The manifest contains no
credentials or cloud configuration content and excludes its own recursive hash.
