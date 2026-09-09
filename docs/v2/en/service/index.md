# AgentScope Service

AgentScope Service provides a shared control plane for managed Agents, existing Agent applications and Coding Agent runtimes. Create an Agent and run a Session, or attach an existing runtime and coordinate work through Issues, Teams and workflows.

![AgentScope Service architecture](../../../imgs/agentservice/agentscope-service-architecture.png)

## Start with your goal

| Goal | Guide |
| --- | --- |
| Try a self-hosted installation | [Docker quickstart](quickstart.md) |
| Run your first Agent | [Your first Session](first-session.md) |
| Understand the platform objects | [Core concepts](concepts.md) |
| Coordinate multiple Agents | [Issues and Teams](teams.md) |
| Connect a Coding Agent computer | [Runtime Host](runtime-host.md) |
| Attach an existing application | [SDKs and application integration](integrations.md) |
| Deploy on Kubernetes | [Helm deployment](kubernetes.md) |
| Maintain an installation | [Backup, upgrade and recovery](operations.md) |

## What you deploy

The Gateway is the public entry point. The Control Plane (`aistiod`) serves the APIs and Dashboard. The Dataplane executes managed Harness Sessions. The Scheduler handles channels, scheduled work and dispatch. PostgreSQL holds durable state; persistent volumes hold workspaces and artifacts.

The service does not include model credits or third-party Coding Agent installations. After starting the platform, configure a model and execution Environment, or attach an existing runtime.

These guides cover the complete Service in standalone HTTP mode. Use each release's notes for published registry addresses and tested platforms. A candidate version in the source tree does not imply public artifact availability.
