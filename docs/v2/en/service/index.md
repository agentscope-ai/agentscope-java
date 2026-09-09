---
title: AgentScope Service
---

AgentScope Service provides a shared control plane for managed Agents, existing Agent applications and Coding Agent runtimes. Create an Agent and run a Session, or attach an existing runtime and coordinate work through Issues, Teams and workflows.

![AgentScope Service architecture](/imgs/agentservice/agentscope-service-architecture.png)

## Start with your goal

| Goal | Guide |
| --- | --- |
| Try a self-hosted installation | [Docker quickstart](/v2/en/service/quickstart) |
| Run your first Agent | [Your first Session](/v2/en/service/first-session) |
| Understand the platform objects | [Core concepts](/v2/en/service/concepts) |
| Coordinate multiple Agents | [Issues and Teams](/v2/en/service/teams) |
| Connect a Coding Agent computer | [Runtime Host](/v2/en/service/runtime-host) |
| Attach an existing application | [SDKs and application integration](/v2/en/service/integrations) |
| Deploy on Kubernetes | [Helm deployment](/v2/en/service/kubernetes) |
| Maintain an installation | [Backup, upgrade and recovery](/v2/en/service/operations) |

## What you deploy

The Gateway is the public entry point. The Control Plane (`aistiod`) serves the APIs and Dashboard. The Dataplane executes managed Harness Sessions. The Scheduler handles channels, scheduled work and dispatch. PostgreSQL holds durable state; persistent volumes hold workspaces and artifacts.

The service does not include model credits or third-party Coding Agent installations. After starting the platform, configure a model and execution Environment, or attach an existing runtime.

These guides cover the complete Service in standalone HTTP mode. Use each release's notes for published registry addresses and tested platforms. A candidate version in the source tree does not imply public artifact availability.
