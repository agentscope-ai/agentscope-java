---
title: "账号、Namespace 与权限 API"
en_link: /v2/en/service/access
---

<Note>
本页使用 `2.1.0-BETA1` 预发布版本。
</Note>

Service 的权限分为账号身份、Namespace 成员角色、资源操作权限和工作可见性。业务应用应分别检查“用户能否调用这个 Agent”和“用户能否查看这项工作”。Agent、Team 或 Workflow 可见，并不代表其他用户的私人 Issue、会话和文件也可见。

## 初始化账号

部署时配置自己的 bootstrap 管理员，首次登录后更改密码。平台管理员通过账号 API 建立日常账号；业务调用使用日常账号或应用凭据。

| 操作 | API | 关键参数 / 返回 |
| --- | --- | --- |
| 登录 | `POST /api/auth/login` | `username`、`password`；返回 `token` |
| 当前身份 | `GET /api/auth/me` | 当前账号与角色 |
| 修改自己的密码 | `POST /api/user/change-password` | `currentPassword`、`newPassword` |
| 列出 / 创建账号 | `GET/POST /api/admin/users` | 创建传 `username`、可选 `initialPassword`、`roles`；返回 `user`，自动生成密码时另返回 `generatedPassword` |
| 管理员重置密码 | `PATCH /api/admin/users/{id}/password` | `newPassword` |
| 管理员修改平台角色 | `PATCH /api/admin/users/{id}/roles` | `roles`、当前账号 `version` |

平台角色与下文 Namespace 角色是两组权限。账号引用使用返回的稳定 ID，不使用可修改的显示名称。管理员重置密码后，原登录凭据可能失效，应重新登录获取 token。

<Accordion title="创建账号与维护平台角色">

以下由平台管理员使用自己的 TOKEN 执行。创建时省略 initialPassword，平台会返回一次性 generatedPassword；妥善交付后让用户修改密码。平台角色 user 与 Namespace 角色 member 不同。

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

从返回数组按 USER_ID 读取当前账号版本，下面示范保留普通用户角色。授予管理员权限应作为独立授权决定，不能通过套用 Namespace 角色实现。

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

Namespace 是资源与授权边界，Workspace 是 Agent 读写文件的环境，两者职责不同。账号可同时属于多个空间，请求通过空间 header 选择操作范围。

以下示例使用 Bash、`curl` 和 `jq`。先按[认证与空间](/v2/zh/service/api-reference#认证与空间)准备 `BASE_URL`（Service 地址）、`TOKEN`（用户 Bearer token）、`TENANT`、`NAMESPACE`，在同一个 Bash 终端执行后续步骤：

```bash
set -euo pipefail
```


先读取当前可用空间，不假设安装中一定存在某个共享空间：

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

`/me/namespaces` 返回 `items`，每项包含 `tenant`、`name`、`kind`、`roles`、`owner`、`accessVersion`。`/me/scope` 返回当前默认范围及可选空间。后续请求的 header、query 和 JSON 中的 tenant/namespace 要保持一致。

平台管理员可创建共享空间。下面将 `OWNER_ID` 设置为空间所有者的账号 ID：

```bash
jq -n --arg owner "$OWNER_ID" \
  '{name:"engineering",displayName:"研发空间",owner:$owner,members:{}}' > request.json
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @request.json
```

创建响应为 `{namespace}`。`name` 使用小写字母、数字和连字符，以字母或数字开头，最长 63 字符，不能使用保留的 `personal-` 前缀。owner 省略时使用当前管理员；tenant 由当前安装决定。个人空间和全局空间由平台管理，不能按普通共享空间修改成员和生命周期。

## 配置成员和角色

空间管理者读取 `GET /api/v1/namespaces/{name}`，再通过 PUT 提交 `version` 与新的成员映射。`members` 是完整替换，先保留原成员再加入同事：

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

| Namespace 角色 | 主要用途 |
| --- | --- |
| `viewer` | 发现和读取授权资源 |
| `member` | 使用资源、发起与跟进工作 |
| `developer` | 配置 Agent 等资源，同时可使用资源 |
| `operator` | 执行运维操作 |
| `admin` | 管理成员、资源配置与操作权限 |
| `auditor` | 显式的工作审计能力，不因 admin 身份自动获得 |

一个成员可拥有多个角色。资源策略可进一步限制可发现、可使用和可编辑的内容。授予或移除 auditor 只能由空间所有者或平台管理员完成；归档/恢复共享空间也要求这两种身份之一。

更新空间还可提交 `displayName` 或 `archived`。发生 409 版本冲突时重新读取，不能用旧成员列表覆盖别人的更改。

## 配置具体资源的权限

调用 `GET /api/v1/namespaces/{name}/resources` 查看资源与依赖。对单个 Agent，读取以下接口会返回当前用户的 `decisions`、配置版本及可能的 `dependencyError`；有管理权限时还返回 `policy`：

```bash
permission=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/namespaces/$NAMESPACE/resources/agent/$AGENT_ID/access" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
printf '%s\n' "$permission" | jq .
```

资源管理员可用 PUT 更新 `{version,policy}`。例如将一个 Agent 限制为指定同事可发现、可使用：

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

该请求替换资源策略，应保留仍需使用的其他用户、用户组与依赖授权。`mode:"inherit"` 继承空间角色，`restricted` 使用资源授权。可授予的动作是 `discover`、`use`、`inspect`、`edit`、`publish`、`manage`。受权用户必须已是空间成员。

| policy 字段 | 用途 |
| --- | --- |
| `users` | 账号 ID 到动作列表 |
| `groups` | 本空间用户组 ID 到动作列表 |
| `consumers` | 允许使用该依赖的资源，格式为 `kind:id`，必须存在真实依赖关系 |
| `exportTo` | Workflow 模板可导入的目标空间，不授予跨空间执行权限 |

例如 Team 依赖成员 Agent，Managed Agent 还可能依赖 Memory、Vault 和 Environment。检查依赖授权后再让普通成员试用，不能只检查 Team 本身的 `use`。

用户组通过 `GET/PUT /api/v1/namespaces/{name}/groups` 管理，PUT 使用 `{version,groups}`；每组包含 `name`、账号 ID 列表 `members`、`roles`。用户组管理人的授权，Team 编排 Agent，两者不是同一个概念。

## 工作自身的共享范围

Issue 创建时的 `access` 支持 `private`、`namespace` 或 `shared`；shared 的 `members` 将账号 ID 映射为 `reader` 或 `contributor`。只有根 Issue 创建者可通过 `PUT /api/v1/issues/{issueId}/access` 修改共享，提交 `{version,access}`，版本来自最新 Issue。

工作共享不会授权使用新的 Agent，也不会绕过 Namespace 成员检查。平台管理员不是所有私人工作的默认读者；审计应通过相应授权进行。

## 排查访问失败

先确认身份和空间，再读取资源的 `decisions` 与依赖错误，最后检查 Issue 共享。资源不可见时可能返回 404，已可见但操作不被允许时可能返回 403；用正确账号和返回的原因排查，不要替换成内部服务令牌。

普通成员可通过 `POST /api/v1/namespaces/{name}/requests` 提交 `version`、`resource`（`kind:id`）、`action`、`reason` 申请授权。管理员向 `/requests/{requestId}/review` 提交 `{version,approve}` 决策，相关记录可通过 `/requests` 查询。

用两个日常账号验证[CRM 方案交付案例](/v2/zh/service/cases/in-product-delivery)：一个配置资源，另一个只负责调用并查看自己的工作。应用凭据按目标资源授权和 scope 检查访问，不能替代对用户 Namespace 权限的验证。控制台对应入口见[控制台概览](/v2/zh/service/console/index)。

<span id="curl-management"></span>

## 用户组、工作共享与授权申请示例

沿用[API 身份准备](/v2/zh/service/kubernetes#production-api-access)的用户 token 和空间变量。

管理用户组需要空间管理权限。PUT 替换完整 groups 映射，因此从当前返回值新增一组，并保留其余组。COLLEAGUE_ID 是已有空间成员的账号 ID。

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

<Accordion title="由根 Issue 创建者共享工作">

使用根 Issue 创建者的 TOKEN，并确认同事已经是当前空间成员。下面保留原有共享成员，再新增一位只读成员；版本来自最新 Issue。

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

<Accordion title="申请与审批资源访问">

申请人先用自己的 TOKEN 查询请求版本，再申请操作。管理员切换到自己的登录身份，审阅目标请求后决定 approve 的值，不要替其他用户复用登录 token。

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

管理员审阅后重新读取最新 version。只有决定同意时才提交下面的 approve:true；拒绝改为 false。

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

<Accordion title="变更账号密码">

本人改密时，把 currentPassword、newPassword 写入受保护的 password-change.json；管理员重置密码时，把 newPassword 写入 password-reset.json。两种身份与操作不同，改密后按需要重新登录。

<Tabs>
<Tab title="本人改密">

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
<Tab title="管理员重置">

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
