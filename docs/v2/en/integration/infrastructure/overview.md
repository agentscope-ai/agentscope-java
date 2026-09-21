---
title: Overview
---

These extensions plug AgentScope into the infrastructure you already run — gateways, registries, message buses, schedulers — so an Agent can be governed and scheduled like any other service.

| Extension | Middleware | Capability |
| --- | --- | --- |
| [Higress](/v2/en/integration/infrastructure/higress) | [Higress](https://higress.io/) AI gateway | Pull tools published as MCP on the gateway into the Toolkit |
| [Nacos](/v2/en/integration/infrastructure/nacos) | [Nacos](https://nacos.io/) | A2A AgentCard registry/discovery, prompt config center, skill repository |
| [Scheduler](/v2/en/integration/infrastructure/scheduler) | XXL-Job / Quartz | Run an Agent on a CRON schedule or fixed rate |

## Where this fits

- **Higress / Nacos** turn "gateway, registry" into horizontal capabilities of AgentScope.
- **Scheduler** addresses the "Agent isn't always triggered by humans — sometimes by a scheduler" use case.

These extensions are independent and can be combined freely.
