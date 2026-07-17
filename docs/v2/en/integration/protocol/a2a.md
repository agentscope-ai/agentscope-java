# A2A (Agent-to-Agent)

AgentScope integrates with A2A 1.0 through the `org.a2aproject.sdk:*:1.0.0.Final` Java SDK. On the server, fine-grained AgentScope `AgentEvent` streams are mapped to A2A artifacts and task status updates. On the client, a remote A2A Agent is exposed as an ordinary AgentScope `Agent`.

## Modules

| Purpose | Artifact |
| --- | --- |
| Remote Agent client | `io.agentscope:agentscope-extensions-a2a-client` |
| Framework-neutral server | `io.agentscope:agentscope-extensions-a2a-server` |
| Spring Boot client/server integration | `io.agentscope:agentscope-a2a-spring-boot-starter` |
| Optional durable Redis HITL control plane | `io.agentscope:agentscope-extensions-a2a-hitl-redis` |

The client and server modules are independent; use either one alone. The Redis module is optional and is needed only when Redis is selected as the durable human-in-the-loop (HITL) control plane.

## Client: call a remote A2A Agent

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-client</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Pass an AgentCard directly

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import org.a2aproject.sdk.spec.AgentCard;

AgentCard card = AgentCard.builder()
        .name("remote-translator")
        .url("http://other-service:8080")
        // Configure the remaining required AgentCard fields.
        .build();

A2aAgent remote = A2aAgent.builder()
        .name("remote-translator")
        .agentCard(card)
        .build();

Msg result = remote.call(Msg.builder()
        .textContent("Translate to English: 你好")
        .build()).block();
```

### Discover the AgentCard through its well-known URL

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import java.util.Map;

WellKnownAgentCardResolver resolver = WellKnownAgentCardResolver.builder()
        .baseUrl("http://127.0.0.1:8080")
        .relativeCardPath("/.well-known/agent-card.json")
        .authHeaders(Map.of())
        .build();

A2aAgent remote = A2aAgent.builder()
        .name("remote")
        .agentCardResolver(resolver)
        .build();
```

`A2aAgent` is an `AgentBase`, so it composes with normal AgentScope flows such as Pipeline, MsgHub, and Subagent.

### Request context, metadata, and headers

Use a stable `RuntimeContext.userId` and `RuntimeContext.sessionId` when the remote Agent persists state. In a HITL flow, the same values identify the Agent state that is resumed later.

```java
import io.agentscope.core.a2a.agent.A2aRequestOptions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

RuntimeContext runtimeContext = RuntimeContext.builder()
        .userId("user-42")
        .sessionId("session-42")
        .build();

A2aRequestOptions options = A2aRequestOptions.builder()
        .runtimeContext(runtimeContext)
        .headers(Map.of("Authorization", "Bearer ..."))
        .metadata(Map.of("requestSource", "example"))
        .build();

Msg result = remote.call(
        List.of(Msg.builder().textContent("Hello").build()),
        options).block();
```

Headers are transport-level HTTP headers. They do not provide an in-task authentication resume contract; see [Compatibility and limitations](#compatibility-and-limitations).

## Server: expose an Agent as an A2A server

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-server</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Build a framework-neutral server

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.TransportWrapper;

ReActAgent.Builder agentBuilder = ReActAgent.builder()
        .name("backend-agent")
        .model(model);

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
        .deploymentProperties(8080)
        .build();

TransportWrapper<?, ?> wrapper = server.getTransportWrapper("JSONRPC");
// Forward requests from the chosen web framework to the wrapper.

server.postEndpointReady();
```

When deployment properties are present and no transport is configured, JSON-RPC is selected by default. A custom transport can be added with `withTransport(TransportProperties)`.

`AgentScopeA2aServer` assembles the request chain but does not open a network port. Connect the selected `TransportWrapper` to Spring Boot, Quarkus, Vert.x, or another web framework, then call `postEndpointReady()` after the endpoint is reachable.

### Custom AgentRunner

Use `AgentEvent`, not the deprecated `Event` stream surface:

```java
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Flux;

final class MyRunner implements AgentRunner {
    private final ReActAgent agent;
    private final ConcurrentMap<String, RuntimeContext> running = new ConcurrentHashMap<>();

    MyRunner(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public Flux<AgentEvent> streamEvents(
            List<Msg> requestMessages,
            AgentRequestOptions options) {
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(options.getSessionId())
                .userId(options.getUserId())
                .build();
        if (running.putIfAbsent(options.getTaskId(), context) != null) {
            return Flux.error(new IllegalStateException("duplicate taskId"));
        }
        return agent.streamEvents(requestMessages, context)
                .doFinally(signal -> running.remove(options.getTaskId(), context));
    }

    @Override
    public void stop(String taskId) {
        RuntimeContext context = running.get(taskId);
        if (context != null) {
            // Interrupt with the exact RuntimeContext used by streamEvents.
            agent.interrupt(context);
        }
    }

    @Override
    public String getAgentName() { return agent.getName(); }

    @Override
    public String getAgentDescription() { return agent.getDescription(); }
}
```

A runner that supports durable HITL must also return `HitlDurabilityCapability.DURABLE` from `hitlDurabilityCapability()` and expose its actual shared `AgentStateStore` from `actualAgentStateStore()`.

### Optional components

- `TaskStore` persists A2A Tasks. The default implementation is in-memory.
- `QueueManager` manages live event queues; it is not a durable event replay log.
- `A2aHitlDurabilityBinding` is the composite SPI for a durable HITL control plane.
- `PushNotificationConfigStore` and `PushNotificationSender` provide outbound notifications.
- `AgentRegistry` publishes the `AgentCard` to a registry such as [Nacos](../infrastructure/nacos.md).

## Spring Boot starter

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

For ordinary A2A, enable the server and leave HITL disabled:

```yaml
agentscope:
  a2a:
    server:
      enabled: true
      hitl:
        enabled: false
```

HITL defaults to `false`. Ordinary A2A requires no HITL binding, Redis dependency, or special `AgentStateStore` wiring. See the [Quickstart](../../docs/quickstart.md) for the other server and AgentCard properties.

## Human-in-the-loop pause and resume

AgentScope HITL uses separate A2A turns:

```text
call -> input-required + A2aHandoff -> current stream ends
resume or cancel -> new A2A request on the same task/context -> new terminal result
```

The original SSE connection does not remain open across a pause, restart, or replica change.

### Detect a handoff

```java
import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.message.Msg;
import java.util.Optional;

Msg paused = remote.call(Msg.builder()
        .textContent("Run the guarded operation")
        .build()).block();

Optional<A2aHandoff> maybeHandoff = A2aHandoff.tryFrom(paused);
if (maybeHandoff.isEmpty()) {
    // The call completed normally, or the peer returned only standard A2A fields.
    return;
}

A2aHandoff handoff = maybeHandoff.orElseThrow();
```

`tryFrom(Msg)` recognizes only the typed handoff enhancement that the local `A2aAgent` attaches. It does not infer HITL capabilities from arbitrary peer metadata. `A2aHandoff` is Jackson serializable, so it can be handed to a fresh client later.

The `resumeToken` is a secret bearer capability. `A2aHandoff.toString()` redacts it, but serialized JSON still contains it and must not be logged.

### Approve or deny a tool

```java
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

A2aPendingTool pending = handoff.pendingTools().get(0);

A2aUserConfirmation approval = new A2aUserConfirmation(
        pending.toolCallId(),
        true,
        Map.of("city", "Shanghai"), // null keeps the original input
        List.of());                  // optional PermissionRule values

Msg completed = remote.resume(handoff, List.of(approval)).block();
```

Set `approved` to `false` to deny the tool. `modifiedInput` may replace only the pending tool's input; the server preserves the original tool ID and name.

Application-facing JSON for the typed response looks like this. This is not raw A2A wire JSON:

```json
{
  "responseType": "user-confirmation",
  "toolCallId": "call-1",
  "approved": true,
  "modifiedInput": {
    "city": "Shanghai"
  },
  "permissionRules": []
}
```

### Supply an externally executed tool result

```java
import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Map;

A2aPendingTool pending = handoff.pendingTools().get(0);

A2aExternalToolResponse externalResult = new A2aExternalToolResponse(
        pending.toolCallId(),
        ToolResultState.SUCCESS,
        List.of(TextBlock.builder().text("external result").build()),
        Map.of("provider", "example"));

Msg completed = remote.resume(handoff, List.of(externalResult)).block();
```

Text, data, and error results can be represented with normal AgentScope `ContentBlock` values and `ToolResultState`. Sensitive HITL metadata keys are stripped from external-result metadata.

```json
{
  "responseType": "external-tool-response",
  "toolCallId": "call-1",
  "state": "success",
  "outputBlocks": [
    {
      "type": "text",
      "text": "external result"
    }
  ],
  "metadata": {
    "provider": "example"
  }
}
```

### Persist and cancel a handoff

The serializable handoff shape is:

```json
{
  "taskId": "task-123",
  "contextId": "session-42",
  "handoffId": "handoff-123",
  "type": "USER_CONFIRM",
  "expiresAt": "2030-01-01T00:00:00Z",
  "pendingTools": [
    {
      "toolCallId": "call-1",
      "toolName": "lookup",
      "originalInput": {"city": "Beijing"},
      "prompt": "Allow this lookup?"
    }
  ],
  "resumeToken": "<secret; do not log>"
}
```

Cancel an open handoff with explicit request options (there is no no-options overload):

```java
import io.agentscope.core.a2a.agent.A2aRequestOptions;
import io.agentscope.core.message.Msg;

Msg canceled = remote.cancelHandoff(
        handoff,
        A2aRequestOptions.empty()).block();
```

Cancel atomically consumes only an `OPEN` handoff. It is different from interrupting an ordinary A2A turn that is already executing.

### Exact-set validation and retry-safe errors

- One resume must answer every current pending `toolCallId` exactly once.
- Missing, additional, duplicate, or wrong response types are rejected before Agent execution.
- A wrong task, context, handoff, token, response set, expired capability, or competing lease is rejected at request admission. The public A2A error is `InvalidParamsError` with `A2A HITL resume request was rejected`.
- A non-expiry rejection before claim leaves the Task `input-required` and the handoff `OPEN`, so the original valid capability can be retried. An expired handoff becomes `EXPIRED` and cannot be resumed.
- A request against a terminal Task may return `UnsupportedOperationError`.
- After a successful claim, setup or Agent execution failure is a real Task failure and may leave the handoff `RECOVERY_REQUIRED`; automatic replay is not safe.

Internal rejection reasons and token/digest material are never returned to the caller.

## HITL storage modes

### Local mode

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: local
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
```

Local mode supplies in-process Task storage, handoff coordination, and session leases. Use it for development or a single process only: it does not survive restart and cannot resume on another replica.

The framework-neutral equivalent is:

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import java.time.Duration;

HitlServerProperties hitl = HitlServerProperties.builder()
        .enabled(true)
        .durability(HitlServerProperties.Durability.LOCAL)
        .taskTtl(Duration.ofDays(30))
        .handoffTtl(Duration.ofDays(7))
        .executionLeaseTtl(Duration.ofMinutes(1))
        .build();

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
        .deploymentProperties(8080)
        .hitlServerProperties(hitl)
        .build();
```

### Durable mode and state ownership

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: durable
        coordination-provider: redis
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
```

Durable HITL has two distinct state authorities:

| State | Owner |
| --- | --- |
| A2A Task/history, handoff, token digest, admission claim, session lease, TTL | `A2aHitlDurabilityBinding` |
| Agent context, memory, pending tools, reasoning checkpoint | the application's `AgentStateStore` |

The starter verifies the A2A control plane through the binding. It rejects obvious local Agent-state stores (`InMemoryAgentStateStore` and `JsonFileAgentStateStore`), but it does not prove that a database or custom `AgentStateStore` is reachable from every replica.

The application must ensure that:

- every replica uses the same shared Agent-state backend;
- `RuntimeContext.userId` and `RuntimeContext.sessionId` resolve the same state on every replica;
- a custom runner declares `DURABLE` and exposes its actual store; and
- a deployment test pauses on replica A, stops A, and resumes on replica B.

Redis is not required for Agent state. The optional Redis provider below stores only the A2A control plane; the application's shared Agent state may use another implementation.

### Optional Redis control-plane provider

For clarity, declare the main starter, the provider, and a compatible Redisson client. Use the
Redisson version managed by your application or platform:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-hitl-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>${redisson.version}</version>
</dependency>
```

The provider's Redisson dependency is optional and is not propagated to the application. An
existing compatible Redisson Spring integration can be used instead of the direct dependency
above. In either case, the application must provide exactly one `RedissonClient`. The provider
does not choose, create, or close it, and it never inspects or replaces the application's
`AgentStateStore`.

```java
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;

@Bean
RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress("redis://127.0.0.1:6379");
    return Redisson.create(config);
}
```

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: durable
        coordination-provider: redis
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
        redis:
          namespace: my-service:a2a:hitl:
          claim-recovery-timeout: 2m
          reconciler-interval: 30s
```

Redis auto-configuration activates only when HITL is enabled, durability is `durable`, `coordination-provider` is `redis`, exactly one `RedissonClient` exists, and the application has not supplied its own `A2aHitlDurabilityBinding`. Give every service/environment an explicit, isolated namespace.

The provider verifies TaskStore write/read/delete and TTL behavior, handoff claim/replay handling, digest-only token storage, lease ownership, and a coherent Redis namespace. Claim recovery moves abandoned claimed handoffs to `RECOVERY_REQUIRED`; it does not replay an external side effect.

### Custom durable binding

Non-Redis providers implement the composite SPI and expose one A2A component bean. A
provider-owned backend/factory creates the three components so they cannot be mixed across
backends or registered as loose Spring beans:

```java
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MyDurableHitlConfiguration {
    @Bean
    A2aHitlDurabilityBinding durableHitlBinding(MyControlPlaneBackend backend) {
        TaskStore taskStore = backend.createTaskStore();
        HitlResumeCoordinator coordinator = backend.createResumeCoordinator();
        HitlSessionLease lease = backend.createSessionLease();
        return new MyDurableHitlBinding(taskStore, coordinator, lease, backend);
    }
}

interface MyControlPlaneBackend {
    TaskStore createTaskStore();
    HitlResumeCoordinator createResumeCoordinator();
    HitlSessionLease createSessionLease();

    HitlDurabilityVerification verifyControlPlane(
            TaskStore taskStore,
            HitlResumeCoordinator coordinator,
            HitlSessionLease lease);

    default void start() {}
    default void close() {}
}

final class MyDurableHitlBinding implements A2aHitlDurabilityBinding {
    private final TaskStore taskStore;
    private final HitlResumeCoordinator coordinator;
    private final HitlSessionLease lease;
    private final MyControlPlaneBackend backend;

    MyDurableHitlBinding(
            TaskStore taskStore,
            HitlResumeCoordinator coordinator,
            HitlSessionLease lease,
            MyControlPlaneBackend backend) {
        this.taskStore = taskStore;
        this.coordinator = coordinator;
        this.lease = lease;
        this.backend = backend;
    }

    public TaskStore taskStore() { return taskStore; }
    public HitlResumeCoordinator resumeCoordinator() { return coordinator; }
    public HitlSessionLease sessionLease() { return lease; }
    public HitlDurabilityVerification verify() {
        return backend.verifyControlPlane(taskStore, coordinator, lease);
    }
    public void start() { backend.start(); }
    public void close() { backend.close(); }
}
```

`MyControlPlaneBackend` represents provider-owned connection/factory code, not an AgentScope API.
Its `verifyControlPlane` method must perform real, synchronous checks that prove TaskStore
write/read/delete and TTL, coordinator claim/replay behavior, lease ownership and expiry, shared
backend/namespace topology, and backend reachability, throwing if any check fails. A
`HitlDurabilityVerification` record is the safe result of those checks, not a substitute for them.

Durable mode requires exactly one composite binding and rejects standalone `TaskStore`,
`HitlResumeCoordinator`, or `HitlSessionLease` beans alongside it. Only the composite binding is
registered as an A2A Spring component. Provider contract tests should
call `A2aHitlDurabilityBindingContract.verify(binding)`, which performs the generic checks and
invokes the binding's provider-specific `verify()` implementation.

## Security and operational boundaries

- A resume token is a high-entropy, one-time bearer capability, not a business authentication or authorization policy.
- The server stores a digest bound to the Task, context, handoff, execution key, and complete pending-tool set; it never stores the plaintext token. The client attaches plaintext only to the caller-local `A2aHandoff` after hooks, tracing, and memory processing.
- Never put serialized `A2aHandoff` JSON in logs, ordinary `call(...)` messages, traces, or Task metadata. Store it only in an access-controlled secret store and expire it according to `expiresAt`.
- Do not edit a handoff's coordinates or pending tools in application code. Resume and cancel validate the complete persisted binding.
- Admission is single-winner, but external side effects are not guaranteed exactly once across an unknown crash point after claim.
- Durable control-plane state does not persist or replay a live SSE stream. Resume is a new request.

Use one AgentCard for one logical Agent by default. The current HITL execution key uses the runner name as `logicalAgentId`. If one card routes to multiple Agents, the application must guarantee non-colliding logical identities and the same Agent-state recovery strategy on every replica. If it cannot guarantee that, expose separate cards.

## Compatibility and limitations

- This integration uses A2A Java SDK `1.0.0.Final` (`org.a2aproject.sdk.*`), not the former `io.a2a.*` / `io.github.a2asdk` packages.
- Standard text, artifact, and status interoperability is best-effort with ordinary A2A 1.0 peers. Typed `A2aHandoff` requires the AgentScope handoff metadata contract; `tryFrom` returns empty when that local enhancement cannot be built.
- Typed resume supports `USER_CONFIRM` and `EXTERNAL_EXECUTION` only.
- In-task `auth-required` resume is unsupported because AgentScope has no matching resume contract. When a peer returns `TASK_STATE_AUTH_REQUIRED`, `A2aAgent` completes exceptionally; it does not synthesize a `Msg` or publish a post hook. Transport headers remain available through `A2aRequestOptions`.
- One `A2aAgent` instance permits only one active subscribed call at a time. Serialize calls or use separate instances for concurrency.
- Local mode is single-process only. Durable mode strongly verifies the A2A control plane, while cross-replica Agent-state reachability remains the application's responsibility.
- Durable mode provides single resume admission, not persistent event streams or universal exactly-once external side effects.
