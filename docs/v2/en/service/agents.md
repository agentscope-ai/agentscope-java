# Agents, tools and skills

An Agent is a reusable configuration. Choose its execution mode before configuring working material and capabilities.

## Choose an execution mode

| Mode | Use case | Prerequisites |
| --- | --- | --- |
| Managed | Platform-hosted Harness Sessions | Model, Environment and required resources |
| External application | An existing Agent process remains independently operated | Application SDK and a reachable contract endpoint |
| Hosted runtime | A Coding Agent runs on a selected host | Online Runtime Host and installed provider |

## Configure and verify

In Agents, configure the name, instructions and model. Attach the required Workspace, Memory, Vault and tools. Keep credentials out of shared instructions. Tool permission policies determine when human confirmation is required.

Skills hold reusable task instructions and supporting files. After changing a skill, verify file visibility, permissions and results in a new Session before using it in a Team.

Definitions, running instances and Sessions are separate objects. After updating configuration, check which version a new Session uses; do not assume all active Sessions switch immediately.

## Share access

Configure resource grants in the relevant Namespace and check access to dependent Workspaces, models and Vaults. Discovering an Agent does not grant access to all of its dependencies or other users' private work.

Continue with [Workspaces and Environments](workspaces.md) and [Accounts and permissions](access.md).
