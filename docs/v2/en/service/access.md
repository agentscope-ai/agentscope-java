---
title: "Account, Namespace, and permission APIs"
zh_link: /v2/zh/service/access
---

<Note>
This is preview documentation. The official release is not yet available.
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

## Namespace

A Namespace is a resource and authorization boundary. A Workspace provides files for Agent execution. An account can belong to multiple Namespaces; select the intended scope with request headers.

The examples use Bash, `curl`, and `jq`. Set `SERVICE_URL` to your Service address, `TOKEN` to a user Bearer token, and `TENANT` / `NAMESPACE` to your authorized scope; see [API authentication](/v2/en/service/api-reference). Define this request helper:

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


Read the available scope rather than assuming a shared namespace exists:

```bash
api "$SERVICE_URL/api/v1/me/namespaces"
api "$SERVICE_URL/api/v1/me/scope"
```

`/me/namespaces` returns `items` containing `tenant`, `name`, `kind`, `roles`, `owner`, and `accessVersion`. `/me/scope` reports the default scope and choices. Keep tenant/namespace consistent across headers, queries, and JSON bodies.

A platform administrator can create a shared namespace. Set `OWNER_ID` to its owner's account ID:

```bash
api "$SERVICE_URL/api/v1/namespaces" --data "$(jq -n --arg owner "$OWNER_ID" \
  '{name:"engineering",displayName:"Engineering",owner:$owner,members:{}}')"
```

The response is `{namespace}`. Names contain lowercase letters, digits, and hyphens, start with a letter or digit, and are at most 63 characters. The `personal-` prefix is reserved. Owner defaults to the current administrator; the installation determines tenant. Personal and global namespaces have platform-managed membership and lifecycle.

## Configure members and roles

Managers read `GET /api/v1/namespaces/{name}`, then PUT `version` and the updated member map. `members` replaces the complete map, so retain existing members:

```bash
current=$(api "$SERVICE_URL/api/v1/namespaces/$NAMESPACE")
api "$SERVICE_URL/api/v1/namespaces/$NAMESPACE" -X PUT \
  --data "$(jq --arg user "$COLLEAGUE_ID" \
    '{version:.namespace.version,members:(.namespace.members + {($user):["member"]})}' \
    <<<"$current")"
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
permission=$(api "$SERVICE_URL/api/v1/namespaces/$NAMESPACE/resources/agent/$AGENT_ID/access")
printf '%s\n' "$permission" | jq .
```

A resource manager updates `{version,policy}` with PUT. For example, restrict discovery and use to a colleague:

```bash
api "$SERVICE_URL/api/v1/namespaces/$NAMESPACE/resources/agent/$AGENT_ID/access" -X PUT \
  --data "$(jq -n --arg user "$COLLEAGUE_ID" \
    --argjson version "$(jq '.version' <<<"$permission")" \
    '{version:$version,policy:{mode:"restricted",users:{($user):["discover","use"]}}}')"
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
