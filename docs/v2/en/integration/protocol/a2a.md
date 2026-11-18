---
title: A2A
zh_link: /v2/zh/integration/protocol/a2a
---

`agentscope-extensions-a2a` implements version 1.0 of the [A2A protocol](https://a2aproject.github.io/A2A/), with the A2A Java SDK, and ships two sub-modules:

- `agentscope-extensions-a2a-client`: wraps a remote A2A Agent as a local `Agent` you can `call(...)` directly.
- `agentscope-extensions-a2a-server`: exposes a local `ReActAgent` as an A2A Server.

The two modules are independent — use either one alone.

A2A 0.3 is not supported: the client cannot call A2A 0.3 agents, and the server rejects requests from A2A 0.3 clients, which send no `A2A-Version` header, with a version error. The server does not support `ListTasks` yet, since it does not authenticate callers.

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
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;

AgentCard card = AgentCard.builder()
    .name("remote-translator")
    // The first interface is the preferred one.
    .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://other-service:8080")))
    // ...
    .build();

A2aAgent remote = A2aAgent.builder()
    .name("remote-translator")
    .agentCard(card)
    .build();

Msg result = remote.call(new UserMessage("Translate to English: 你好")).block();
```

### Stream the reply

`streamEvents` streams the events of a call: the text and thinking the remote agent streams arrive as `TextBlockDeltaEvent` and `ThinkingBlockDeltaEvent`, then `AgentResultEvent` carries the same message `call()` returns. Tool calls and tool results are only in the result message.

For completed tasks, the final result prefers output artifacts. When no artifacts are present, it uses the final status message, whether completion arrives as a full task or a streaming status update.

When the remote task stops to wait for the caller (`TASK_STATE_INPUT_REQUIRED` or `TASK_STATE_AUTH_REQUIRED`), the call ends with the status message of the task, which usually carries the question. The metadata of the reply has the task id under `MessageConstants.TASK_ID_METADATA_KEY` and the state under `MessageConstants.TASK_STATE_METADATA_KEY`. The task stays open on the remote agent.

Without streaming (the client config or the AgentCard does not stream), a reply that is a task still `SUBMITTED` or `WORKING` fails the call with an `IllegalStateException`, because `A2aAgent` does not poll tasks.

Cancelling a call, for example by disposing the subscription or with a `timeout` upstream, also cancels its remote task (`tasks/cancel`) once the task id is known, and closes the event stream of the default JSON-RPC transport. Events that arrive after the cancel are dropped. Transports configured with `clientTransports` keep their own HTTP client, so their streams stay open until the remote ends the task.

```java
remote.streamEvents(new UserMessage("Translate to English: 你好"))
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

### Sessions

Each session, set by the user and session id of the `RuntimeContext`, keeps its own history in its `AgentState`, and its calls run one at a time; calls without a session id share the session named after the agent. `interrupt(RuntimeContext)` cancels the remote task of the call in progress in that session.

```java
RuntimeContext context = RuntimeContext.builder().userId("alice").sessionId("chat-1").build();
Msg reply = remote.call(List.of(new UserMessage("Translate to English: 你好")), context).block();
List<Msg> history = remote.getAgentState(context).getContext();
```

The identity travels with every call: the session id is sent as the A2A native `contextId`, and the user id as the envelope metadata key `_agentscope_user_id` — an AgentScope server reads both back into its `RuntimeContext`. A non-AgentScope peer ignores the extra metadata key, and an anonymous call (no user id) sends none.

### Middleware

`A2aAgent` runs `MiddlewareBase` at two points: `onAgent` wraps the whole call, and `onReasoning` wraps the exchange with the remote agent. The input messages of `onReasoning` are the messages sent to the remote agent, and its events are the text and thinking the remote agent streams. The other extension points do not apply to a remote agent.

```java
A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(resolver)
    .middleware(new MiddlewareBase() {
        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent, RuntimeContext ctx, AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            long start = System.currentTimeMillis();
            return next.apply(input).doFinally(signal ->
                    log.info("remote call took {} ms", System.currentTimeMillis() - start));
        }
    })
    .build();
```

### Auto-discover via well-known

```java
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

WellKnownAgentCardResolver resolver = new WellKnownAgentCardResolver(
    "http://127.0.0.1:8080",
    "/.well-known/agent-card.json",
    Map.of()
);

A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(resolver)
    .build();
```

`A2aAgent` is a subclass of `AgentBase`, so it composes naturally with Pipeline, MsgHub, Subagent, etc.

## Server: expose a ReActAgent as an A2A Server

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-server</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Build the server

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportProperties;

ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("backend-agent")
    .model(model);

AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(agentBuilder)
    .transportProperties(new JsonRpcTransportProperties())
    // .agentCard(customCard)
    // .agentRegistry(myRegistry)
    .build();

// Delegate inbound requests to the transport wrapper from your web framework
TransportWrapper wrapper = server.getTransportWrapper("JSONRPC");
// ... Spring/Quarkus controller calls wrapper.handleRequest(body, headers, metadata):
// it returns a JSON string, or for streaming requests a Flux<String> whose elements are written
// as SSE events. Serve server.getAgentCardJson() at the agent card endpoint.

server.postEndpointReady();   // call after the web server is listening — triggers registration etc.

// On shutdown: stop the threads of the request handler and the thread pools the server created.
// Running executions are asked to stop (interrupted); close() does not wait for them. An executor
// you set with executor(...) is left to you. The Spring Boot starter does this for you.
server.close();
```

`AgentScopeA2aServer` does not bind a port or expose endpoints itself; it only assembles components and the request-handling chain. You wire transport into Spring Boot, Quarkus, Vert.x, etc. as you prefer.

For streaming requests and requests with `returnImmediately=true`, a custom `AgentRunner` may return only an `AgentResultEvent`. If no artifact has been streamed, the server publishes a non-empty final message as one complete artifact when the event stream finishes successfully. If artifacts have already been streamed, the final snapshot is not appended again. With `completeWithMessage=true`, the final-only message is also included in the completed task status.

### Request identity and session identifiers

The server fills `AgentRequestOptions.sessionId` for the `AgentRunner` from the SDK-resolved A2A `contextId`. Clients can use the standard protocol field without adding a custom metadata key. If the client omits `contextId`, the SDK generates one.

For compatibility, a non-null, non-blank `message.metadata["sessionId"]` overrides this mapping, even when it differs from `contextId`. This preserves existing session keys when the SDK generates a new context ID. Null, empty, or whitespace-only metadata values fall back to `contextId`. The protocol message's `contextId` is not changed.

`AgentRequestOptions.userId` is read from the envelope metadata key `_agentscope_user_id` that an AgentScope client sets from its `RuntimeContext`. The flat `message.metadata["userId"]` key still works as a deprecated fallback for older clients; without either, the user id stays empty.

The built-in `BaseReActAgentRunner` implementations pass both ids into the agent's `RuntimeContext`, so session state, session logs, and per-call middlewares see the caller's `(userId, sessionId)` slot. `AgentRunner` implementations receive the ids via `AgentRequestOptions` and decide themselves.

The envelope user id is a delegation claim — it names whom the caller says it acts for, and any client can set it. It keys per-user agent state, session logs, and the identity tools see, so without a resolver treat it as untrusted: a caller claiming another user's id lands in that user's `(userId, sessionId)` slots. When the transport already verifies the caller (mTLS, OAuth), give the server a resolver so the verified identity wins — and let a failing resolver throw to reject the call:

```java
AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(agentBuilder)
    .contextResolver(context -> {
        String verified = verifiedUserIdOf(context.getCallContext()); // mTLS cert / OAuth token
        return verified == null ? null
            : RuntimeContext.builder().userId(verified).build();
    })
    .build();
```

Priority: resolver (transport-verified) &gt; envelope-asserted user id &gt; none. A resolver that returns `null`, or a context without a user id, leaves the assertion in place; when the two disagree the server logs a warning and uses the verified one.

This mapping supplies the runner's request options; it does not automatically enable session persistence. A custom runner can use the identifiers when setting up its runtime context or state store.

### Tool confirmation (`input-required`)

When the agent stops to ask the user to confirm a tool call ([User confirmation](/v2/en/docs/building-blocks/agent#user-confirmation)), the task goes to `TASK_STATE_INPUT_REQUIRED` instead of being completed. The status message carries each pending tool call as a data part: the data is the tool input, and the part metadata holds `_agentscope_tool_call_id` and `_agentscope_tool_name`. A blocking request returns the task. A streaming request sends the status update and, because the task is not finished, keeps the stream open until the client leaves it.

To continue, send a message with the same `taskId` and `contextId`, and put the confirmation into the metadata of the AgentScope message under `agentscope_confirm_results`:

```java
Message confirmation = Message.builder()
    .role(Message.Role.ROLE_USER)
    .taskId(pausedTaskId)
    .contextId(contextId)
    .parts(new TextPart("approved", Map.of("_agentscope_msg_id", "m1")))
    .metadata(Map.of("m1", Map.of("agentscope_confirm_results", List.of(
        Map.of(
            "confirmed", true,   // false denies the call
            "toolCall", Map.of(
                "type", "tool_use",
                "id", toolCallId,
                "name", toolName,
                "input", toolInput))))))
    .build();
```

The server builds a new agent for every request, so the paused run is restored from the agent's state store by `(userId, sessionId)`. Set `stateStore(...)` on the agent builder ([AgentStateStore](/v2/en/docs/building-blocks/agent#configuring-state-persistence-agentstatestore)); without one, the built-in runners fail the task before the agent runs. A custom `AgentRunner` has to restore the paused run itself.

A follow-up without a confirmation, or with a confirmation for other tool calls, does not run the agent: the task stays `input-required` and the pending request is sent again. Any other error while the run resumes fails the task.

Send the confirmation on the paused task. A confirmation sent as a new task in the same context also resumes the run, but the paused task stays `input-required`. Cancelling a paused task does not clear the paused run from the state store. Runs that stop for external tool execution are not reported as `input-required` yet.

### Optional components

- `TaskStore` / `QueueManager`: task and event queue stores; in-memory by default, swap for persistent versions in production.
- `PushNotificationConfigStore` / `PushNotificationSender`: outbound notifications. Push notifications are not supported yet: the agent card declares none, and push configs sent with a message are ignored.
- `AgentRegistry`: register `AgentCard` to an external registry such as Nacos (see [Nacos](/v2/en/integration/infrastructure/nacos)).

## Spring Boot Starter

If you're on Spring Boot, prefer `agentscope-spring-boot-starter-a2a-server` — it auto-configures the server and controller. See [Quickstart](/v2/en/docs/quickstart).

Configure the agent card under `agentscope.a2a.server.card`, with the field names of the A2A agent card. For example, to declare how clients authenticate:

```yaml
agentscope:
  a2a:
    server:
      card:
        provider:
          organization: My Company
          url: https://example.com
        security-schemes:
          bearerAuth:
            type: http          # one of apiKey, http, oauth2, openIdConnect, mutualTLS
            scheme: bearer
            bearer-format: JWT
        security:
          - bearerAuth: []
```

OAuth 2.0 schemes support the `authorization-code` (with `pkce-required`), `client-credentials` and `device-code` flows. A2A 1.0 has no `implicit` or `password` flow; configuring one fails the startup.

When a map key contains special characters, such as the OAuth scope name `read:user` or a URL, write it quoted in brackets, for example `"[read:user]": Read the user`. Without brackets, characters such as `:` and `/` are dropped, and a `security-schemes` name containing `.` is read as a nested path, so that scheme is not applied.

The starter writes A2A JSON with the A2A SDK, so the application's own Jackson settings (such as `spring.jackson.property-naming-strategy`) do not affect it.
