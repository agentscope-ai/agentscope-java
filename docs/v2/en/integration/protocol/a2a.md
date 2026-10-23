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

// On shutdown: stop the threads of the request handler. Running executions are asked to stop
// (interrupted); close() does not wait for them. The Spring Boot starter does this for you.
server.close();
```

`AgentScopeA2aServer` does not bind a port or expose endpoints itself; it only assembles components and the request-handling chain. You wire transport into Spring Boot, Quarkus, Vert.x, etc. as you prefer.

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
