---
title: Web 工具
description: 内置的 web_fetch 与 web_search 工具、搜索 provider 的选择，以及失败时如何降级
en_link: /v2/en/docs/harness/web-tools
---

## 它们做什么

HarnessAgent 默认注册两个 web 工具：

- **`web_fetch`** — 抓取一个 URL 并返回其文本，按调用方给定的 `max_chars` 截断。
- **`web_search`** — 搜索网络，返回标题、URL 和摘要。

两者都是只读的，因此在只读阶段 [Plan Mode](/v2/zh/docs/harness/plan-mode) 和权限系统都会放行。

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("researcher")
        .model(model)
        .workspace(workspace)
        .build();
```

`disableWebTools()` 会同时关闭这两个工具。该设置会传递给本地子 agent，因此子 agent 不会拿到父 agent
已经关闭的搜索工具。

## 搜索 provider

`web_search` 默认由 [Tavily](https://tavily.com/) 提供，在调用时从环境变量读取 `TAVILY_API_KEY`。

`.parallelWebSearch()` 改为使用 [Parallel 的 Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)。
Harness 在 `build()` 期间通过 Streamable HTTP 连接 `https://search.parallel.ai/mcp`，并把服务端的
`web_search` 注册为同一个面向模型的名字：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("researcher")
        .model(model)
        .workspace(workspace)
        .parallelWebSearch()
        .build();
```

查询会发送到 Parallel 的**匿名、限流**端点 —— 不需要账号或 API key，也不会发送任何凭据。请求带有
`agentscope-java/<version>` 的 `User-Agent` 用于汇总用量统计，不包含任何用户标识。搜索输入及随附的
metadata 会到达 Parallel，参见其[条款](https://parallel.ai/customer-terms)和
[隐私政策](https://parallel.ai/privacy-policy)。

两个 provider 的入参不同。Parallel 的 schema 同时要求 `objective` 和 `search_queries`，而 Tavily 接受
`query` 和可选的 `max_results`：

```json
{
  "objective": "Find the official AgentScope Java documentation",
  "search_queries": ["AgentScope Java documentation"]
}
```

`web_fetch` 不受该选择影响。

## Parallel 路径上的失败行为

这个选项是**失败开放（fail-open）**的。服务不可用、429，或 provider 侧改名，都只会让搜索能力降级，而不会让
agent 构建不出来。这在 `agentscope-distribution` 中尤其重要：agent 会按 session 重新物化，连接失败否则会
表现为 session 启动失败。

有两种出错情形，结局相同 —— 打一条 warning 日志，改为注册内置的 Tavily `web_search`，因此 `build()` 始终
返回一个带搜索工具的 agent：

| 失败情形 | Harness 的处理 |
| --- | --- |
| `initialize` / `tools/list` 失败（服务不可用、限流） | 打 warning 日志，回落到 Tavily |
| 握手成功但没有通告 `web_search` | 打 warning 日志，回落到 Tavily |

注意回落后的 Tavily 需要 `TAVILY_API_KEY` 才能真正搜索，所以正因为 Parallel 免 key 才选用它的 agent，应当把
这条 warning 当作需要处理的信号。

两次往返都有显式超时，而不是 MCP 客户端的默认值（初始化 30s / 请求 120s），避免无响应的端点把 `build()`
拖住数十秒：

| 超时 | 取值 |
| --- | --- |
| 初始化 | 10s |
| 请求 | 30s |

## 只读语义

MCP 工具的只读标记通常继承自服务端的 `annotations.readOnlyHint`。对于这个内置注册，Harness 会把
`web_search` **固定**为只读，不论端点如何上报，使其与被替换的 Tavily 工具行为一致：Plan Mode 在只读阶段
继续放行搜索；即使 Parallel 某天不再返回该 annotation，权限引擎也不会开始对它弹确认。

## 自定义 HTTP client

`webHttpClient(...)` 用于注入 `java.net.http.HttpClient` —— 适用于代理、自定义 TLS，或在服务端 HTTP/2
协商失败时强制 HTTP/1.1。

它只作用于 `web_fetch` 和 **Tavily** 版 `web_search`。Parallel 路径走 MCP transport，由其自带的 client
负责，因此 `webHttpClient(...)` 对它无效。

## 子 agent

本地子 agent 会继承所选的 provider。在 Parallel 路径上，它们还会**复用父 agent 的连接**：父 agent 在自己的
`build()` 中注册一次该工具，子 agent 直接注册同一个工具实例，而不重复握手。因此一轮里 fan-out 出多个子 agent
总共只有一对 `initialize` / `tools/list`，而不是每次 spawn 一对，spawn 延迟和配额压力都不随子 agent 数量增长。

子 agent 不持有该连接，所以关闭子 agent 不会断开父 agent 的连接。

如果父 agent 已经回落到 Tavily，子 agent 也沿用这个回落结果，而不会再去尝试父 agent 已经确认不可用的连接。
