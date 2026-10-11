---
title: "Account, Namespace, and permission APIs"
zh_link: /v2/zh/service/access
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

Service separates account identity, Namespace roles, resource operations, and work visibility. An application should check both whether a user may invoke an Agent and whether they may read a particular work item. Discovering an Agent, Team, or Workflow does not expose other users' private Issues, sessions, or files.

## Initialize accounts

Configure your own bootstrap administrator when deploying, then change its password. Platform administrators create daily-use accounts through account APIs. Business calls use ordinary accounts or published-service application credentials.

| Operation | API | Fields / response |
| --- | --- | --- |
| Login | `POST /api/auth/login` | `username`, `password`; returns `token` |
| Current identity | `GET /api/auth/me` | Current account and roles |
| Change own password | `POST /api/user/change-password` | `currentPassword`, `newPassword` |
| List / create accounts | `GET/POST /api/admin/users` | Create with `username`, optional `initialPassword`, `roles`; returns `user` and `generatedPassword` when generated |
| Reset a password | `PATCH /api/admin/users/{id}/password` | `newPassword` |
| Change platform roles | `PATCH /api/admin/users/{id}/roles` | `roles`, current account `version` |

Platform roles differ from Namespace roles below. Use stable account IDs, not display names. An administrator password reset can invalidate existing login credentials; obtain a new token when needed.

<Accordion title="Create accounts and maintain platform roles">

A platform administrator performs these calls with their own TOKEN. Omitting initialPassword makes the platform return generatedPassword; deliver it securely and have the user change it. The platform role user differs from the Namespace role member.

```bash
ACCOUNT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/admin/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<'JSON'
{
  "username": "report-reader",
  "roles": [
    "user"
  ]
}
JSON
)
USER_ID=$(jq -er '.user.userId' <<< "$ACCOUNT_JSON")
```

```bash
ACCOUNTS_JSON=$(
  curl -sS --fail-with-body -G "$BASE_URL/api/admin/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    --data-urlencode "q=report-reader"
)
```

Select the current account version by USER_ID from the returned array. The example retains ordinary user access. Granting administrator access requires a separate authorization decision and does not use Namespace role names.

```bash
USER_VERSION=$(jq -er --arg id "$USER_ID" '.[] | select(.userId == $id) | .version' <<< "$ACCOUNTS_JSON")
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/admin/users/$USER_ID/roles" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "version": $USER_VERSION,
  "roles": ["user"]
}
JSON
```

</Accordion>

## Namespace

A Namespace is a resource and authorization boundary. A Workspace provides files for Agent execution. An account can belong to multiple Namespaces; select the intended scope with request headers.

The examples use Bash, `curl`, and `jq`. Set `BASE_URL` to your Service address, `TOKEN` to a user Bearer token, and `TENANT` / `NAMESPACE` to your authorized scope; see [API authentication](/v2/en/service/api-reference). Run the following steps in the same Bash terminal:

```bash
set -euo pipefail
```


Read the available scope rather than assuming a shared namespace exists:

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/me/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/me/scope" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

`/me/namespaces` returns `items` containing `tenant`, `name`, `kind`, `roles`, `owner`, and `accessVersion`. `/me/scope` reports the default scope and choices. Keep tenant/namespace consistent across headers, queries, and JSON bodies.

A platform administrator can create a shared namespace. Set `OWNER_ID` to its owner's account ID:

```bash
jq -n --arg owner "$OWNER_ID" \
  '{name:"engineering",displayName:"Engineering",owner:$owner,members:{}}' > request.json
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @request.json
```

The response is `{namespace}`. Names contain lowercase letters, digits, and hyphens, start with a letter or digit, and are at most 63 characters. The `personal-` prefix is reserved. Owner defaults to the current administrator; the installation determines tenant. Personal and global namespaces have platform-managed membership and lifecycle.

## Configure members and roles

Managers read `GET /api/v1/namespaces/{name}`, then PUT `version` and the updated member map. `members` replaces the complete map, so retain existing members:

```bash
current=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
jq --arg user "$COLLEAGUE_ID" \
    '{version:.namespace.version,members:(.namespace.members + {($user):["member"]})}' \
    <<<"$current" > request.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/v1/namespaces/$NAMESPACE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @request.json
```

| Namespace role | Main purpose |
| --- | --- |
| `viewer` | Discover and read authorized resources |
| `member` | Use resources and work with Issues |
| `developer` | Configure and use resources |
| `operator` | Operational actions |
| `admin` | Manage membership, configuration, and resource permissions |
| `auditor` | Explicit work-audit access, not implicitly granted to admins |

Users can hold multiple roles. Resource policies further restrict discovery, use, and editing. Only the namespace owner or platform administrator can change auditor grants or archive and restore shared namespaces.

Namespace updates also accept `displayName` or `archived`. Reload after a 409 conflict rather than overwriting new changes with an old membership map.

## Configure individual resource access

Read resources and dependencies through `GET /api/v1/namespaces/{name}/resources`. An Agent's access endpoint returns the current user's `decisions`, configuration version, and optional `dependencyError`. Managers also receive `policy`:

```bash
permission=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/resources/agent/$AGENT_ID/access" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
printf '%s\n' "$permission" | jq .
```

A resource manager updates `{version,policy}` with PUT. For example, restrict discovery and use to a colleague:

```bash
jq -n --arg user "$COLLEAGUE_ID" \
    --argjson version "$(jq '.version' <<<"$permission")" \
    '{version:$version,policy:{mode:"restricted",users:{($user):["discover","use"]}}}' > request.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/v1/namespaces/$NAMESPACE/resources/agent/$AGENT_ID/access" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @request.json
```

This replaces the resource policy; retain existing users, groups, and dependency grants you still need. `mode:"inherit"` inherits namespace roles; `restricted` uses resource grants. Actions are `discover`, `use`, `inspect`, `edit`, `publish`, and `manage`. Grantees must already belong to the namespace.

| Policy field | Purpose |
| --- | --- |
| `users` | Account IDs mapped to actions |
| `groups` | Namespace group IDs mapped to actions |
| `consumers` | Resources allowed to use this dependency, as `kind:id`; an actual dependency must exist |
| `exportTo` | Namespaces allowed to import a Workflow template, not cross-namespace execution rights |

Teams depend on member Agents; Managed Agents can depend on Memory, Vault, and Environment resources. Verify dependencies as well as the Team's own `use` permission.

Manage people groups through `GET/PUT /api/v1/namespaces/{name}/groups`. PUT accepts `{version,groups}`; each group contains `name`, account IDs in `members`, and `roles`. Access groups organize people; Teams orchestrate Agents.

## Work-specific sharing

Issue `access` supports `private`, `namespace`, or `shared`. Shared `members` maps account IDs to `reader` or `contributor`. Only the root Issue's creator can update sharing through `PUT /api/v1/issues/{issueId}/access`, using `{version,access}` from the latest Issue.

Sharing work does not grant access to another Agent or bypass namespace membership. Platform administrators are not default readers of all private work; use explicit audit authorization.

## Diagnose access failures

Check identity and namespace, then resource decisions and dependency errors, followed by Issue sharing. An undiscoverable resource can return 404; a prohibited operation on a visible resource can return 403. Inspect the correct account's decision rather than substituting an internal service token.

Members can request access through `POST /api/v1/namespaces/{name}/requests` with `version`, `resource` (`kind:id`), `action`, and `reason`. A manager decides through `/requests/{requestId}/review` with `{version,approve}`; query `/requests` for records.

Verify the [CRM proposal case](/v2/en/service/cases/in-product-delivery) with two ordinary accounts: one configures resources, the other invokes them and reads its own work. application keys exercise published scopes and contracts, not user Namespace permission checks. See [Console overview](/v2/en/service/console/index) for UI entry points.

<span id="curl-management"></span>

## Group, work sharing and access request examples

Use the user token and scope variables from [API setup](/v2/en/service/kubernetes#production-api-access).

Managing groups requires namespace management permission. PUT replaces the complete groups map. Add one group to the current response and retain other groups. COLLEAGUE_ID is an existing namespace member’s account ID.

```bash
GROUPS_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/groups" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
```

```bash
jq --arg user "$COLLEAGUE_ID" '{version,groups:(.groups + {"report-readers":{name:"Report readers",members:[$user],roles:["viewer"]}})}' <<< "$GROUPS_JSON" > groups-update.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/v1/namespaces/$NAMESPACE/groups" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @groups-update.json
```

<Accordion title="Share work as its root Issue creator">

Use the root Issue creator’s TOKEN and confirm the colleague is a member of this namespace. Retain existing shared members and add a read-only member using the current Issue version.

```bash
ISSUE_ID="YOUR_ROOT_ISSUE_ID"
```

```bash
ISSUE_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/issues/$ISSUE_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
```

```bash
jq --arg user "$COLLEAGUE_ID" '.issue | {version,access:(.access + {mode:"shared",members:((.access.members // {}) + {($user):"reader"})})}' <<< "$ISSUE_JSON" > issue-access.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/v1/issues/$ISSUE_ID/access" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @issue-access.json
```

</Accordion>

<Accordion title="Request and review resource access">

The requester reads the current version using their own TOKEN before applying. A manager then signs in as themselves, reviews the selected request and chooses approve. Do not reuse another user’s login token.

```bash
REQUESTS_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/requests" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
ACCESS_VERSION=$(jq -er '.version' <<< "$REQUESTS_JSON")
```

```bash
ACCESS_REQUEST=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/requests" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
   "version": $ACCESS_VERSION,
   "resource": "agent:$AGENT_ID",
   "action": "use",
   "reason": "Use this Agent to prepare the project report."
 }
JSON
)
ACCESS_REQUEST_ID=$(jq -er '.request.id' <<< "$ACCESS_REQUEST")
```

After review, the manager rereads the latest version. Submit approve:true only after deciding to grant access; use false to deny.

```bash
REQUESTS_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/requests" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
ACCESS_VERSION=$(jq -er '.version' <<< "$REQUESTS_JSON")
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/requests/$ACCESS_REQUEST_ID/review" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
   "version": $ACCESS_VERSION,
   "approve": true
 }
JSON
```

</Accordion>

<Accordion title="Change an account password">

For your own password change, write currentPassword and newPassword to protected password-change.json. For an administrator reset, write newPassword to password-reset.json. These use different identities; sign in again afterward as needed.

<Tabs>
<Tab title="Change own password">

```bash
chmod 600 password-change.json
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/user/change-password" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @password-change.json
```

</Tab>
<Tab title="Administrator reset">

```bash
USER_ID="ACCOUNT_ID_TO_RESET"
chmod 600 password-reset.json
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/admin/users/$USER_ID/password" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @password-reset.json
```

</Tab>
</Tabs>

</Accordion>
