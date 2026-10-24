---
title: "Vault: credentials for tools"
description: "Store tool credentials, attach Vaults through Agent defaults or Session creation, and verify MCP authentication and rotation."
zh_link: /v2/zh/service/vault
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

A Vault stores credentials an Agent uses to access external tools. Save the credentials, then include the Vault ID in the Agent's `defaultVaultIds` or select it through `vaultIds` when creating a Session. At runtime, credentials from the attached Vaults are resolved for matching MCP connections. Creating a Vault or saving a secret does not itself authenticate an Agent.

The Agent definition declares connection addresses and tools, while Vaults hold authentication material. This lets different Sessions using the same Agent use different authorizations. Tool credentials authenticate access to external systems; the platform `TOKEN` or Application key authenticates an application calling Service. Public resource APIs return credential metadata after creation, without returning the secret again.

Use the local URL and default scope variables from the [API identity setup](/v2/en/service/create-managed-agent#api-setup). Before verification, configure a Managed Agent's MCP connection using the [tool guide](/v2/en/service/tools) and retain `AGENT_ID`. This page uses the connection name `reports` and variable `REPORTS_TOKEN`; adapt both names and the service URL to your actual connection.

## Create a Vault and add a credential

Create a Vault for the report-service credentials and retain the returned `VAULT_ID`. Credentials added next belong to this collection. Session creation attaches the Vault ID, while rotating an individual credential uses that credential's own ID.

```bash
set -euo pipefail

VAULT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/vaults" \
    -H "Content-Type: application/json" \
    --data-binary @- <<'JSON'
{
  "displayName": "Report service credentials",
  "metadata": {
    "purpose": "reports"
  }
}
JSON
)
VAULT_ID=$(jq -er '.id' <<< "$VAULT_JSON")
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
chmod 600 credential.json

CREDENTIAL_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/vaults/$VAULT_ID/credentials" \
    -H "Content-Type: application/json" \
    --data-binary @credential.json
)
CREDENTIAL_ID=$(jq -er '.id' <<< "$CREDENTIAL_JSON")
```

Retain the credential `id` for updates, checks, and deletion. Rotate by PATCHing that credential's `secret`; the request does not issue a new token in the external system.

## Configure a connection

Choose the credential type for the MCP connection, bind the resource to the Agent, configure its connection, and verify authentication with a read-only tool call.

| Type | Application |
| --- | --- |
| Bearer / MCP OAuth | Target matches a connection name or complete endpoint URL, including its path |
| Environment variable | Substitutes explicitly referenced `${VARIABLE}` values in MCP headers, environment or query parameters |
| Generic secret | Storage only; does not automatically inject into arbitrary tools |

OAuth content requires `access_token` and can include refresh information as needed. Saving a credential does not grant external permissions.

## Bind credentials to a Managed Agent

The Agent definition's `mcpServers` declares the connection, and a matching `mcp_toolset` in `tools` selects the tools it exposes. The Vault supplies credentials when that connection runs. Saving a credential does not add tools to an Agent or establish an MCP connection. See [how tools belong to an Agent](/v2/en/service/tools#how-tools-belong-to-an-agent) for the complete declaration and binding path.

To attach this Vault by default in new Sessions, set `defaultVaultIds` to a list containing `VAULT_ID` and follow the complete GET/PATCH procedure in [setting Agent defaults](/v2/en/service/managed-agent-configuration#set-default-resources-on-an-agent). It preserves other definition fields and includes the current version. The array represents the complete default collection, so include other Vaults you want to retain. Changing this default does not change existing Session mounts.

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

Add the fields to a connection in the Agent's `mcpServers` and select the matching HTTP transport. In the same definition's `tools`, associate an `mcp_toolset` through `mcpServerName: "reports"` and enable the required tools. Save the definition, create a new Session with this Vault attached, and verify a read-only call. With `static_bearer`, instead set Target to `reports` or the full endpoint; the resolver supplies the Bearer header without this placeholder. Use one clear authentication method per connection to avoid competing credentials for the same target.

## Select Vaults for a Session

After the Agent has the matching MCP configuration, create a Session with this Vault attached. The request explicitly supplies `vaultIds`, affecting this Session without changing the Agent defaults. If the Agent already sets `defaultVaultIds`, omit the field to inherit that collection. An explicit `[]` attaches none of the default Vaults.

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: vault-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
  "vaultIds": [
    "$VAULT_ID"
  ]
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s\n' "$SESSION_JSON" | jq '{id, target, vaultIds}'
```

The returned `vaultIds` should contain the created Vault ID. This confirms resource selection, but you still need to [submit a Turn](/v2/en/service/service-api#submit-a-turn) requesting a real read-only MCP call to verify authentication. Follow [tool verification](/v2/en/service/tools#verify-that-the-binding-takes-effect) to inspect calls, returned data, and Session errors. If the external system rejects access, check the connection name or URL, credential type, Header placeholders, and actual authorization scope.

`vaultIds` replaces the complete list for this Session rather than appending to Agent defaults. To combine Vaults, supply all their IDs and avoid competing credentials for the same connection or variable name. Each Vault in the request must be accessible to the calling identity.

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

Open the returned `authorizationUrl` in a browser and complete provider authorization. After callback success, the initiating user must call complete to save the credential; creating a connection alone does not authorize it. The flow checks its browser-bound cookie and initiator identity, so it is not an unattended token import. GitHub connections use `provider:"github"` and require administrator integration settings; see [Integrations](/v2/en/service/api-reference#integrations).

<Accordion title="Complete OAuth authorization through the API">

OAuth requires a configured public callback origin and the actual callbackUrl registered with the provider. This example uses a public client with PKCE. Replace example domains and CLIENT_ID and use the provider’s required scope. For client_secret_basic or client_secret_post, send clientSecret in a protected JSON file instead of copying this public-client configuration.

Click Connect on the Vault OAuth connection in Console, then authorize with the provider in that browser. The `/authorize` response sets an HttpOnly cookie. Starting with curl and copying its URL into a browser loses that cookie and makes the callback fail. A custom frontend must also start authorization in the browser and retain the returned flowId before querying or completing it.

```bash
FLOW_ID="FLOW_ID_FROM_BROWSER_AUTHORIZE_RESPONSE"
```

Click Connect on the Vault OAuth connection in Console, then authorize with the provider in that browser. The `/authorize` response sets an HttpOnly cookie. Starting with curl and copying its URL into a browser loses that cookie and makes the callback fail. A custom frontend must also start authorization in the browser and retain the returned flowId before querying or completing it.

```bash
FLOW_ID="FLOW_ID_FROM_BROWSER_AUTHORIZE_RESPONSE"
```

Use the same user TOKEN that initiated authorization. Query and complete only after the callback succeeds. Console normally completes this step automatically; a custom frontend can use the interfaces below.

```bash
curl -sS --fail-with-body "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections/$CONNECTION_ID/flows/$FLOW_ID"
```

```bash
CONNECTED_OAUTH=$(
  curl -sS --fail-with-body -X POST "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections/$CONNECTION_ID/flows/$FLOW_ID/complete"
)
CREDENTIAL_ID=$(jq -er '.credentialId' <<< "$CONNECTED_OAUTH")
```

Cancel an abandoned pending flow; disconnect an already connected account. These are alternatives to completing authorization.

<Tabs>
<Tab title="Cancel">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections/$CONNECTION_ID/flows/$FLOW_ID/cancel"
```

</Tab>
<Tab title="Disconnect">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections/$CONNECTION_ID/disconnect"
```

</Tab>
</Tabs>

</Accordion>

## Validate and rotate

`validate` checks local decryption and attempts a bounded reachability probe for HTTP(S) targets; it does not send the secret to verify provider permissions. `ok:true` does not establish external authorization. Inspect the checks and perform an actual tool call. Confirm the replacement token externally, then PATCH that credential's `secret`. A Session retains the Vault ID, and later runtime resolution reads the available credentials; it does not freeze the creation-time secret into the Agent definition. Verify rotation with a new read-only call. Requests already sent to external systems are not recalled by rotation. Inspect consumers before deletion to avoid interrupting several Agents.

Keep secrets out of Instructions, AGENTS.md, conversations and public examples. Encrypted Vault data depends on the deployment master key; recovery requires both the database and the original key.

For failures, check the exact Target, explicit variable references, Vault binding and external permissions. Do not paste secrets into Chat for diagnosis.

Next: [Agent tools](/v2/en/service/tools) · [Backup and recovery](/v2/en/service/operations).

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/vault.png" alt="Vault and credential metadata list" />
</Frame>

Create collections and maintain credentials under **Resources → Vault**, then choose defaults on the Managed Agent's **Runtime configuration → Session defaults → Default vaults**. New Sessions can also select their own Vaults. The screenshot contains synthetic metadata only; a Vault appearing in the resource list does not establish that the current Agent or Session has it attached.

## Management APIs

Local examples use the default scope without authentication headers. See [production deployment](/v2/en/service/kubernetes#production-api-access) for production grants.

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

<span id="curl-management"></span>

## Query and maintain credentials

Use the platform identity and scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup). Use resource IDs returned by creation or lookup.

```bash
curl -sS --fail-with-body "$BASE_URL/api/vaults/$VAULT_ID/credentials"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/vaults/$VAULT_ID/credentials/$CREDENTIAL_ID/validate"
```

<Accordion title="Update or delete a credential">

Write the new secret to local `credential-update.json`, for example `{"secret":"YOUR_NEW_SECRET"}`. Create a valid replacement at the external service before updating and validating. Deletion is a separate operation that removes the stored credential.

```bash
chmod 600 credential-update.json

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/vaults/$VAULT_ID/credentials/$CREDENTIAL_ID" \
  -H "Content-Type: application/json" \
  --data-binary @credential-update.json
```

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/vaults/$VAULT_ID/credentials/$CREDENTIAL_ID"
```

</Accordion>


<Accordion title="Maintain OAuth connection configuration">

OAuth connection lists do not return the client secret. Prepare oauth-update.json with complete provider settings before updating a connection. Supply a new clientSecret when replacing it, rather than a masked value. Configuration changes invalidate pending flows.

```bash
curl -sS --fail-with-body "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections"
```

```bash
chmod 600 oauth-update.json

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/vaults/$VAULT_ID/oauth-connections/$CONNECTION_ID" \
  -H "Content-Type: application/json" \
  --data-binary @oauth-update.json
```

</Accordion>

<Accordion title="Maintain the Vault name and lifecycle">

Check Agent and Session consumers first. Renaming does not rotate credentials; archive and deletion are separate maintenance operations.

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/vaults" \
  --data-urlencode "limit=25" \
  --data-urlencode "offset=0"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/vaults/$VAULT_ID"
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/vaults/$VAULT_ID" \
  -H "Content-Type: application/json" \
  --data-binary @- <<'JSON'
{
  "displayName": "Report credentials updated"
}
JSON
```

<Tabs>
<Tab title="Archive">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/vaults/$VAULT_ID/archive"
```

</Tab>
<Tab title="Delete">

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/vaults/$VAULT_ID"
```

</Tab>
</Tabs>

</Accordion>
