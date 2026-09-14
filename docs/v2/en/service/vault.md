---
title: "Vault: credentials for tools"
---

[简体中文](/v2/zh/service/vault)

**Resources → Vault** stores credentials for Agent tool connections. Secrets are write-only in the UI; after saving, it shows metadata such as type, label and target.

## Configure a connection

Create a Vault and select **Add credential**. Choose the type and enter Label, Target and Secret. Bind the Vault to the Agent and configure its MCP tool. Verify authentication with a read-only call.

| Type | Application |
| --- | --- |
| Bearer / MCP OAuth | Target matches a connection name or complete endpoint URL, including its path |
| Environment variable | Substitutes explicitly referenced `${VARIABLE}` values in MCP headers, environment or query parameters |
| Generic secret | Storage only; does not automatically inject into arbitrary tools |

OAuth content requires `access_token` and can include refresh information as needed. Saving a credential does not grant external permissions.

## Validate and rotate

Use Validate to check a credential and Rotate to replace its secret. Confirm the new credential with the external system, then verify a real tool call. Inspect consumers before deletion to avoid interrupting several Agents.

Keep secrets out of Instructions, AGENTS.md, conversations and public examples. Encrypted Vault data depends on the deployment master key; recovery requires both the database and the original key.

For failures, check the exact Target, explicit variable references, Vault binding and external permissions. Do not paste secrets into Chat for diagnosis.

Next: [Agent tools](/v2/en/service/agents) · [Backup and recovery](/v2/en/service/operations).
