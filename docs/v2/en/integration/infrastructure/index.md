# Infrastructure / Middleware

These extensions plug AgentScope into the infrastructure you already run — gateways, registries, message buses, schedulers — so an Agent can be governed and scheduled like any other service.

| Extension | Middleware | Capability |
| --- | --- | --- |
| [Higress](higress.md) | [Higress](https://higress.io/) AI gateway | Pull tools published as MCP on the gateway into the Toolkit |
| [Nacos](nacos.md) | [Nacos](https://nacos.io/) | A2A AgentCard registry/discovery, prompt config center, skill repository |
| [RocketMQ](rocketmq.md) | [Apache RocketMQ](https://rocketmq.apache.org/) | A2A request/response over a message broker, no HTTP exposure |
| [Scheduler](scheduler.md) | XXL-Job / Quartz | Run an Agent on a CRON schedule or fixed rate |

## Where this fits

- **Higress / Nacos / RocketMQ** turn "gateway, registry, message bus" into horizontal capabilities of AgentScope.
- **Scheduler** addresses the "Agent isn't always triggered by humans — sometimes by a scheduler" use case.

These extensions are independent and can be combined freely.
