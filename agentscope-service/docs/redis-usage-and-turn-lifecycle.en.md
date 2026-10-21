# Redis usage and the Turn lifecycle

[中文](redis-usage-and-turn-lifecycle.md)

This document describes Redis usage by deployment, component and execution phase in the backported AgentScope Service implementation dated 2026-10-21; no live Redis inspection was performed. See also [RocketMQ topics and the Turn lifecycle](rocketmq-topics-and-turn-lifecycle.en.md).

## Deployments and components

Local development scripts, Compose, and Kubernetes/Helm deployments require a reachable Redis for Managed execution. The connection setting is `builder.managed.redis-uri` / `BUILDER_MANAGED_REDIS_URI`. Production deployments use external Redis; Helm does not install it.

| Component | Redis usage |
| --- | --- |
| Session Portal | Turn publication mutex; delivery of durably accepted Inputs to a Redis copy, including a fast cancellation marker |
| Brain | Session execution leases; input refill and cancellation checks; tool result mailbox publication, waiting, and repair; Resume publication mutex; child Agent tool leases; checkpoint administration and lifecycle cleanup leases; Harness MessageBus primitives |
| Hands | Tool execution leases and takeover checks; token checks for self-hosted Worker claims, renewals, and applicable callbacks; cloud Sandbox creation/reconnection mutex |
| Gateway and Control Plane | No direct use of these Redis business protocols in the current implementation |
| External MCP servers and self-hosted Workers | No requirement to connect directly to this Redis; self-hosted Workers claim, renew, and report through Hands HTTP APIs |

MCP, cloud Sandbox, local test execution, and self-hosted execution use the same server-side coordination infrastructure. Execution modes do not each define their own Redis topics.

## Purposes and data structures

Suffixes below are wrapped by `ManagedScope.key(...)`, giving `ma:{scopeHash}:<suffix>`. `scopeHash` derives from `tenant + namespace + sessionId`; braces provide a Redis Cluster hash tag.

| Phase | User | Key suffix or channel | Structure / operation | Purpose |
| --- | --- | --- | --- | --- |
| Turn publication | Session Portal | `publication` | String, `SET NX PX` | Short Session publication mutex; default TTL 60 seconds, ownership checked before send, released afterward |
| Turn inference and recovery | Runtime | `execution` | String token; token-checked Lua renewal/deletion | Active inference ownership per Session; default TTL 60 seconds with 15-second renewal, combined with database execution epochs to fence stale attempts |
| Runtime Resume publication | Runtime | `resume-publication` | String, `SET NX PX` | Short publication ownership while replicas scan pending Resumes; TTL 60 seconds |
| Tool execution and takeover | Hands; Runtime child Agent executor | `tool:<taskId>` | String token; ownership checks, renewal, release | Active execution coordination per task; default TTL 60 seconds, in-service execution loops renew every 15 seconds, self-hosted Workers renew through HTTP |
| User Input notification copy | Session Portal writes; Runtime refills and checks | `inputs:<turnId>`, `input-seen:<turnId>`, `cancel:<turnId>` | Stream, deduplication Hash, cancellation String; atomic Lua append | Notification copies and durable payload references for `STEER`, `INJECT`, `REPLY`, `CANCEL`; fast cancellation detection by the Runtime control loop |
| Tool result mailbox | Runtime publishes/repairs; Runtime waiters read | `tool-result:<taskHash>` and `:identity` | Single-result Stream (`MAXLEN=1`), identity String; `XREAD` | Notify inline waiters of an immutable committed result; each publication/repair refreshes a five-minute TTL, all waiters read the same result |
| Cloud Sandbox creation/reconnection | Hands | `sandbox-create` | String, `SET NX PX` | Short mutex for cloud Sandbox creation/restoration per Session; TTL 60 seconds, released afterward |
| Checkpoint administration | Runtime | `checkpoint:<operationId>` | Renewable String token | Coordinate checkpoint administration across replicas; default TTL 60 seconds with 15-second renewal, advancing durable operation records |
| Lifecycle cleanup | Runtime | `lifecycle-cleanup` | Renewable String token | Coordinate Session lifecycle cleanup across replicas; default TTL 60 seconds with 15-second renewal |
| Harness MessageBus | Harness/middleware in Runtime | `harness:queue:<hash>`, `harness:log:<hash>`, logical `harness:broadcast:<hash>` | List queues, Stream logs, Pub/Sub | SDK queue, readable message-log, and broadcast primitives; physical Pub/Sub channel is `ma:harness:notifications`, dispatched by logical key |

The Input Stream exposes an `XREAD` interface, but the current Turn path does not drive all Input application from Stream cursors. Harness reads pending Inputs in business order from PostgreSQL and checks Redis cancellation markers in its control loop. Durable Inputs are refilled approximately every two seconds; cancellation marker checks run approximately every 200 ms.

Ordinary Hands cancellation uses durable Control commands/cancellation tombstones. `ManagedToolExecutions` additionally reads the Redis cancellation marker only for paths not backed by `ManagedHandsStore`, such as Runtime child Agent tools. Ordinary Worker cancellation must not be portrayed as relying only on Redis markers.

Managed Harness construction injects the MessageBus implementation, but actual operations depend on SDK/middleware calls; queues, logs, and broadcasts are not mandatory on every Turn. These Stream message logs are not native SessionLog or checkpoints. Pub/Sub broadcasts have no durable replay guarantee.

## Redis sequence for a long-running tool

Runtime and Worker each represent replica sets. MQ topics share one participant to emphasize Redis publication mutexes, execution leases, notifications, and reacquisition during recovery.

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant S as Session Portal
    participant R as Brain
    participant K as Redis
    participant Q as RocketMQ
    participant W as Hands
    participant D as PostgreSQL

    C->>S: Submit Turn
    S->>D: Durably admit Turn
    S->>K: SET publication token NX PX 60000
    K-->>S: Acquire short publication ownership
    S->>Q: Publish ma_turns_v3
    Q-->>S: Broker ACK
    S->>D: Confirm queued
    S->>K: Release publication after token check
    S-->>C: Return turnId

    Q->>R: Deliver Turns message
    R->>K: Acquire execution token
    R->>D: Establish execution epoch and load state
    Note over R,K: Default renewal every 15 seconds, TTL 60 seconds
    R->>R: Run inference and request a tool
    R->>Q: Publish ma_tool_execute_v1 and receive ACK
    R->>D: Atomically commit tool authorization and checkpoint
    Q->>W: Deliver Execute, check authorization and persist admission
    W->>K: Acquire tool:taskId token
    W->>D: Record tool claim and token
    W->>W: Invoke external tool and collect output
    Note over W,K: Independently renew tool token during execution

    R->>K: XREAD tool result mailbox with short wait
    K-->>R: No result yet
    R->>D: Wait window expires, save suspension and checkpoint
    R->>K: Release execution token
    R-->>Q: Suspend original Turns message
    Note over R,W: Runtime yields active execution ownership; tool may continue

    opt Client submits cancellation Input
        C->>S: Submit CANCEL
        S->>D: Durably admit cancellation Input
        S->>K: Atomically append Input, deduplication Hash, cancellation marker
        R->>D: Cancellation forwarder scans durable intent
        R->>Q: Publish ma_tool_control_v1
        Q->>W: Persist cancellation control command
        Note over W,D: Worker handles external call using durable cancellation facts
    end

    W->>D: Save tool result and pending events
    W->>Q: Publish ma_tool_events_v3
    W->>K: Release tool:taskId after token check
    Q->>R: Deliver tool result event
    R->>D: Commit result and create continuation when ready
    R->>K: Write result mailbox and identity, refresh five-minute TTL
    Note over R,K: Mailbox serves waiters; durable result remains in PostgreSQL

    alt All suspended tasks are mcp.batch
        R->>D: Read dispatch_mode=direct continuations
        R->>R: ContinuationLoop schedules recovery
    else Tool recovery through MQ
        R->>K: Acquire resume-publication token
        R->>Q: Publish ma_turn_resumes_v3
        Q-->>R: Broker ACK
        R->>D: Mark Resume queued
        R->>K: Release resume-publication token
        Q->>R: Deliver Resume
    end

    R->>K: Reacquire execution token
    R->>D: Establish recovery epoch, read checkpoint and results
    R->>R: Continue Harness inference
    R->>D: Commit final output, terminal state, and Turns ACK intent
    R->>K: Release execution token
    R-->>Q: Acknowledge original Turns after terminal commit
```

Fast tools may let Runtime continue within its original execution attempt through the result mailbox, without suspension. `mcp.batch` recovery is driven by durable database records; the Redis mailbox is not its sole recovery trigger. A background scan can retry direct recovery after a missed event callback wakeup.

Sandbox creation, checkpoint administration, lifecycle cleanup, and SDK MessageBus operations are conditional and are not shown as mandatory steps on every Turn. Input-wait recovery retains the Resumes topic and also reacquires the Session execution lease.

## Durability and failure boundaries

- PostgreSQL retains Turn/Input admission, tool authorization/results, checkpoints, suspensions, continuations, and terminal states. Redis leases do not replace database epochs, authorization checks, or idempotency.
- Input copies and tool result mailboxes can be rebuilt from PostgreSQL. A Stream cursor is not an Input application receipt; mailbox expiry is not a tool failure.
- Redis unavailability or token loss prevents safe execution under old ownership. Guards invalidate stale attempts and attempt interruption. Actual external outcomes still require durable facts and recovery protocols; lease loss alone must not trigger blind replay.
- SDK queues/logs and Pub/Sub do not promise the database rebuild behavior of Inputs and tool result mailboxes.
- Redis renewal is not RocketMQ message renewal or ACK. Suspension releases the execution token while the original Turns message remains pending terminal completion.
- Control and blocking-read connections are separate. The default blocking-read concurrency limit is 64, keeping `XREAD` off the lease renewal connection.

## Implementation entry points

- [Redis client, leases, Input copy, and broadcasts](../service-common/src/main/java/io/agentscope/builder/runtime/managed/RedisManagedInfrastructure.java)
- [Shared Redis configuration](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedInfrastructureConfiguration.java)
- [Turn publication mutex](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedTurnPublisher.java)
- [Session execution guard](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedExecutionGuard.java)
- [Input delivery and refill](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedInputDelivery.java)
- [Harness cancellation checks and Input handling](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedHarnessTurns.java)
- [Tool execution and renewal](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedToolExecutions.java)
- [Tool result mailbox](../service-common/src/main/java/io/agentscope/builder/runtime/managed/RedisManagedToolResultDelivery.java)
- [Mailbox repair](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedToolResultRelay.java)
- [Runtime loop and result notification wiring](../service-dataplane/src/main/java/io/agentscope/builder/runtime/ManagedBrainConfiguration.java)
- [Self-hosted Worker protocol](../service-hands/src/main/java/io/agentscope/builder/hands/ManagedWorkerService.java)
- [Cloud Sandbox mutex](../service-hands/src/main/java/io/agentscope/builder/hands/ManagedSandboxProviders.java)
- [Harness MessageBus](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedRedisMessageBus.java)
