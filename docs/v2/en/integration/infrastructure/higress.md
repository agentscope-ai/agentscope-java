# Higress AI Gateway

`agentscope-extensions-higress` brings tools published as MCP (Model Context Protocol) on [Higress](https://higress.io/) into AgentScope. Higress handles tool search, auth, rate-limiting, and observability at the gateway layer; the Agent only invokes the resulting tools.

## When to use

- You already run Higress as an AI gateway and want to feed its tools to an Agent.
- You want tool governance (routing, auth, quotas) decoupled from Agent business logic.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-higress</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import io.agentscope.extensions.higress.HigressToolkit;

// 1) Create a client against an MCP endpoint published by Higress
HigressMcpClientWrapper client = HigressMcpClientBuilder
    .create("higress")
    .streamableHttpEndpoint("http://gateway/mcp-servers/union-tools-search")
    .build();

// 2) Register with HigressToolkit (a Toolkit subclass that caches the Higress client)
HigressToolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(client).block();

// 3) Use it from an Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## Selectively enable tools

`HigressToolkit` reuses the standard `Toolkit` fluent registration API for finer-grained control by group / allowlist:

```java
toolkit.registration()
    .mcpClient(client)
    .enableTools(List.of("search-doc", "fetch-url"))
    .group("knowledge")
    .apply();
```

## Access the underlying MCP client

If you need to call Higress-specific extensions (e.g. tool search via `HigressToolSearchResult`):

```java
HigressMcpClientWrapper higressClient = toolkit.getHigressMcpClient();
```

> Tool governance (auth, rate-limiting, routing, observability) lives on the gateway, so you don't reimplement it on the Agent side.
