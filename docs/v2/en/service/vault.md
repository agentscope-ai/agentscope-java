---
title: "Vault: credentials for tools"
zh_link: /v2/zh/service/vault
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Vault stores credentials for Agent tool connections and is managed through `/api/vaults`. After writing a secret, public resource APIs return only metadata such as type, label, and target. Tool credentials are managed separately from platform tokens and Endpoint keys used to call the Agent API.

## Management APIs

Use a platform user Bearer token with `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; prepare variables as in the [API quickstart](/v2/en/service/first-session). Reads require inspect, mutations require edit, and creation requires namespace resource creation rights. Listings are filtered to inspectable resources; Agent binding also checks dependency access.

| Operation | API | Parameters and response |
| --- | --- | --- |
| List and create | `GET/POST /api/vaults` | GET returns an array; POST takes `displayName`, optional `metadata`, and returns a Vault object |
| Read and update | `GET/PATCH /api/vaults/{id}` | `id`, `displayName`, `metadata`, `ownerId`, timestamps; PATCH updates the name or replaces metadata |
| Archive and delete | `POST /api/vaults/{id}/archive`, `DELETE /api/vaults/{id}` | Archive returns `id`, `archivedAt`; deletion returns 204 |
| List and add credentials | `GET/POST /api/vaults/{id}/credentials` | POST takes `type`, `label`, `target`, and nonempty `secret`; response excludes the secret |
| Update or rotate credentials | `PATCH /api/vaults/{id}/credentials/{credentialId}` | Optional `label`, `target`, `secret`; a nonempty secret replaces the old value, while type is immutable |
| Delete a credential | `DELETE /api/vaults/{id}/credentials/{credentialId}` | Returns 204 |
| Check a credential | `POST /api/vaults/{id}/credentials/{credentialId}/validate` | Returns `ok`, `checks`, `checkedAt` |

Vault listings support `limit` (1–500), `offset` (requires limit), and `X-Total-Count`. Vault and static credential PATCH currently have no version condition; inspect current metadata before updating and avoid concurrent overwrites. Credential responses contain `id`, `type`, `label`, `target`, and `createdAt`.

## Create a Vault and add a credential

```bash
VAULT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/vaults" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"displayName":"Reports credentials","metadata":{"purpose":"reports"}}')
VAULT_ID=$(printf '%s' "$VAULT_JSON" | jq -er '.id')
```

Prepare `credential.json` readable only by your user, replacing the placeholder with a token issued by the external service. Do not commit the real file:

```json
{
  "type": "environment_variable",
  "label": "Reports token",
  "target": "REPORTS_TOKEN",
  "secret": "YOUR_TOOL_TOKEN"
}
```

```bash
curl --fail-with-body -sS "$BASE_URL/api/vaults/$VAULT_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data-binary @credential.json
```

Retain the credential `id` for updates, checks, and deletion. Rotate by PATCHing that credential's `secret`; the request does not issue a new token in the external system.

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/vault.png" alt="Vault and credential metadata list" />
</Frame>

The same resources appear under **Resources → Vault**. The screenshot contains synthetic metadata only. See [Console](/v2/en/service/console/index) for graphical entry points.

## Configure a connection

Choose the credential type for the MCP connection, bind the resource to the Agent, configure its connection, and verify authentication with a read-only tool call.

| Type | Application |
| --- | --- |
| Bearer / MCP OAuth | Target matches a connection name or complete endpoint URL, including its path |
| Environment variable | Substitutes explicitly referenced `${VARIABLE}` values in MCP headers, environment or query parameters |
| Generic secret | Storage only; does not automatically inject into arbitrary tools |

OAuth content requires `access_token` and can include refresh information as needed. Saving a credential does not grant external permissions.

## Bind credentials to a Managed Agent

Set `defaultVaultIds` and `mcpServers` through the [Agent definition API](/v2/en/service/managed-agent-configuration). Read and retain existing fields and include the current definition version when updating. Managed Session API `vaultIds` overrides the defaults: omission inherits bindings, while `[]` mounts no default Vault.

The API type values are `static_bearer`, `mcp_oauth`, `environment_variable` and `api_key`. The `api_key` type is generic storage; it does not automatically configure model authentication or inject into tools. Environment-variable credentials are not globally exported to the Dataplane or arbitrary Shell processes either.

For example, create an `environment_variable` credential with Target `REPORTS_TOKEN` and the external service's token as Secret. Reference it explicitly in the MCP connection. This connection fragment uses a placeholder URL; replace it with your service endpoint:

```json
{
  "name": "reports",
  "url": "https://reports.example.com/mcp",
  "headers": {
    "Authorization": "Bearer ${REPORTS_TOKEN}"
  }
}
```

Add the fields to a connection in the Agent's `mcpServers` and select the matching HTTP transport. Start a new Session and call a read-only tool. With `static_bearer`, instead set Target to `reports` or the full endpoint; the resolver supplies the Bearer header without this placeholder. Use one clear authentication method per connection to avoid competing credentials for the same target.

## OAuth connections

Use Vault OAuth connection APIs when a user needs to authorize access on the provider's site. Paths below are relative to `/api/vaults/{vaultId}`:

| Operation | API and fields |
| --- | --- |
| Create and list | `POST/GET /oauth-connections`; generic connections take `serverName`, `endpoint`, `authorizationEndpoint`, `tokenEndpoint`, `clientId`, `authMethod`, `scope`, and provider-dependent `clientSecret`, `resource`, or other settings |
| Update configuration | `PATCH /oauth-connections/{connectionId}` |
| Start authorization | `POST /oauth-connections/{connectionId}/authorize`; returns `flowId`, `authorizationUrl`, `expiresAt` |
| Read flow status | `GET /oauth-connections/{connectionId}/flows/{flowId}` |
| Confirm persistence | `POST /oauth-connections/{connectionId}/flows/{flowId}/complete`; returns `connected`, `vaultId`, `credentialId` |
| Cancel or disconnect | `POST .../flows/{flowId}/cancel`, `POST /oauth-connections/{connectionId}/disconnect` |

Open the returned `authorizationUrl` in a browser and complete provider authorization. After callback success, the initiating user must call complete to save the credential; creating a connection alone does not authorize it. The flow checks its browser-bound cookie and initiator identity, so it is not an unattended token import. GitHub connections use `provider:"github"` and require administrator integration settings; see [Integrations](/v2/en/service/integrations).

## Validate and rotate

`validate` checks local decryption and attempts a bounded reachability probe for HTTP(S) targets; it does not send the secret to verify provider permissions. `ok:true` does not establish external authorization. Inspect checks and verify an actual tool call. Confirm the replacement token externally, PATCH the credential, and test again. Inspect consumers before deletion to avoid interrupting several Agents.

Keep secrets out of Instructions, AGENTS.md, conversations and public examples. Encrypted Vault data depends on the deployment master key; recovery requires both the database and the original key.

For failures, check the exact Target, explicit variable references, Vault binding and external permissions. Do not paste secrets into Chat for diagnosis.

Next: [Agent tools](/v2/en/service/agents) · [Backup and recovery](/v2/en/service/operations).
