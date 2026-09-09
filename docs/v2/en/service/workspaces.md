# Workspaces and Environments

A Workspace holds working material. An Environment determines where a Managed Agent executes tools. A Namespace determines ownership and access.

## Workspaces

Create a Workspace, add task files or skills, and bind it to an Agent. A developer's absolute laptop path is not automatically a valid server path.

Release deployments mount `/data/workspaces` into the control plane, Dataplane and Scheduler. Compose provides a shared volume; Kubernetes requires shared persistent storage. Database backups alone cannot recover these files.

## Environments

| Type | Tool execution location | Requirement |
| --- | --- | --- |
| Local | The Dataplane container or host | Explicit `BUILDER_ALLOW_LOCAL_ENVIRONMENT` opt-in |
| Sandbox | A configured sandbox service | Credentials, template and network connectivity |
| Self-hosted | Your Hands Worker | Worker connectivity, Environment credentials and heartbeat |

A Local container filesystem is not the user's computer filesystem. A Self-hosted Hands Worker and a Coding Agent Runtime Host have different responsibilities and credentials.

## Verify a binding

After creating an Environment, check the Agent binding. In a new Session, perform a simple file read and inspect its location and any errors. Verify that persistent files remain after a container or Pod restart.

See [Runtime Host](runtime-host.md) and [Backup and recovery](operations.md).
