# 基础设施 / 中间件

这一组扩展把 AgentScope 接到企业里常见的基础设施上，让 Agent 能像普通服务一样被治理、被调度、被发现。

| 扩展 | 中间件 | 主要能力 |
| --- | --- | --- |
| [Higress](higress.md) | [Higress](https://higress.io/) AI 网关 | 通过 MCP 把网关上的工具引入 Toolkit |
| [Nacos](nacos.md) | [Nacos](https://nacos.io/) | A2A AgentCard 注册发现、Prompt 配置中心、Skill 仓库 |
| [RocketMQ](rocketmq.md) | [Apache RocketMQ](https://rocketmq.apache.org/) | 通过 MQ 消费/响应 A2A 请求，无需暴露 HTTP |
| [Scheduler](scheduler.md) | XXL-Job / Quartz | 让 Agent 按 CRON 或固定速率定时跑 |

## 整体定位

- **Higress / Nacos / RocketMQ** 把"接入网关、注册中心、消息总线"作为 AgentScope 的横向能力。
- **Scheduler** 解决"Agent 不一定是被人类触发，可能由调度器周期性触发"的需求。

各扩展之间相互独立，可以按需组合。
