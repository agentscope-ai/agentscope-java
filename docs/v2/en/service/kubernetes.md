---
title: "Production installation"
zh_link: /v2/zh/service/kubernetes
---

<Note>
This guide follows the current source architecture. Build and publish its five images together,
or use a new distribution explicitly containing this implementation. Previously published
`2.1.0-BETA1` images and Charts keep their earlier architecture and cannot install this runtime.
</Note>

## Current source: Managed MQ / Redis deployment

Service deploys Gateway, Go Control Plane, Session Portal, Brain Dataplane and platform Hands.
Control Plane includes Team / Workflow coordination, Scheduler, Cron, channels and outbound
delivery. Applications use Session, Turn and Events; Hands performs physical tool execution.
Operate PostgreSQL, Redis and RocketMQ separately. Pin the image repository and version to one
verified source build; see the [release runbook](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-service/release/README.md).

## 1. Prepare dependencies

Prepare Kubernetes, Helm, psql and a registry accessible by every node. PostgreSQL must contain
`cp`, `rt`, `portal`, `dp` and `ma`. Use separate owner and ordinary service accounts. Redis
protects active ownership and accelerates notifications; PostgreSQL preserves checkpoints,
execution facts and oversized objects in `ma.private_object`. No private managed-blobs PVC is used.

With `userFilesEnabled=false`, public file/Shell/Artifact features are unavailable and internal
workspace data uses Session-isolated PostgreSQL KV storage. No file PVC or Sandbox is needed for
text execution. To enable user files, prepare shared ReadWriteMany Workspace and Artifact storage.

<span id="prepare-rocketmq"></span>

### Prepare RocketMQ

Use a reachable standalone Cluster Proxy supporting LitePushConsumer. Self-hosted Broker 5.5.1
requires `enableLmq=true`, `enableMultiDispatch=true`; set Proxy `enableProxyAutoRenew=true` and
`renewMaxTimeMillis=10800000`. The unmodified 5.5.1 Broker has a known initial-offset delivery
issue on fresh LiteTopics. Verify first-message delivery, offline backlog and restart recovery
on the exact Broker/provider before production use. The repository's explicitly patched local
fixture is development acceptance infrastructure, not a supported production Broker distribution.

Initialize five business parent Topics and groups, with FIFO, Shared/wildcard subscriptions and
minimum-offset consumption. Root and child Sessions share the Turn parent; no depth family is needed.

| Parent Topic | Consumer group | Producer → consumer |
| --- | --- | --- |
| `ma_turns_v3` | `ma_brain_v3` | Portal → Brain |
| `ma_tool_execute_v1` | `ma_hands_execute_v1` | Brain → Hands |
| `ma_tool_control_v1` | `ma_hands_control_v1` | Brain → Hands |
| `ma_tool_events_v3` | `ma_brain_tool_events_v3` | Hands / Brain → Brain |
| `ma_turn_resumes_v3` | `ma_brain_resumes_v3` | Brain → Brain |

From the source `agentscope-service` directory, on your RocketMQ administration host/container:

```bash
ROCKETMQ_HOME=/opt/rocketmq MANAGED_MQ_NAMESRV=namesrv:9876 \
  MANAGED_MQ_CLUSTER=YOUR_CLUSTER sh deploy/managed-mq-init.sh
```

Export the matching `BUILDER_MANAGED_MQ_*` overrides when using custom Topic/group names.
The script also creates the self-hosted Proxy heartbeat Topic. Alibaba Cloud installations use
`deploy/managed-cloud-mq-init.py --instance INSTANCE --region REGION` with an authenticated
`aliyun` CLI instead; configure matching endpoints and optional MQ credentials/TLS in workload
Secrets. See [the source deployment inputs](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service/deploy).

## 2. Create a Secret

From the same current-source checkout, enter `agentscope-service`. Copy
`deploy/kubernetes.env.example`, `deploy/kubernetes-migration.env.example` and
`deploy/postgres-grants.env.example` to private files outside Git. Replace every placeholder.
The grants file uses owner `PG*` settings and four raw business passwords matching the workload
connections. URI passwords must be URL encoded; JDBC and psql passwords remain raw.
The migration Secret contains owner credentials and must never be attached to ordinary workloads.

```bash
set -a
. /private/postgres-grants.env
set +a
psql -X -v ON_ERROR_STOP=1 -f deploy/postgres-init.sql
kubectl create namespace agentscope
kubectl -n agentscope create secret generic service-migration --from-env-file=/private/migration.env
kubectl -n agentscope create secret generic service-credentials --from-env-file=/private/service.env
```

Use `kubectl apply` with your Secret manager for existing installations; keep signing/encryption
keys and business passwords stable. Redis/MQ addresses must resolve from service Pods.

## 3. Configure values

Copy `deploy/production-values.yaml.example` to `/private/production-values.yaml` and set the
public URL, imagePullSecrets and Ingress/TLS as needed. The example enables authentication,
disables Local tools and user files, and uses two replicas for each role. To enable files:

```yaml
userFilesEnabled: true
persistence:
  workspaces:
    storageClass: YOUR_RWX_STORAGE_CLASS
    accessModes: [ReadWriteMany]
  artifacts:
    storageClass: YOUR_RWX_STORAGE_CLASS
    accessModes: [ReadWriteMany]
```

Per-role `existingSecret` values can separate credentials. Optional `managed.mq.existingSecret`
and `data.existingSecret` override business connections; `schema.dataExistingSecret` is migration
only. None is an implicit cloud dependency. Include all PostgreSQL pools, Dataplane native-log
connections and migration jobs in the connection budget.

## 4. Install a pinned version

Set the repository and concrete version of your five built/published images. Use the current
source Chart, or the matching Chart archive from a newly built Kubernetes bundle. Do not select
the old BETA1 Chart from the public repository for this architecture.

```bash
export IMAGE_REPOSITORY=registry.example.com/agentscope
export SERVICE_VERSION=2.1.0-candidate.1
export CHART=helm/agentscope-service
helm upgrade --install service-schema "$CHART" --namespace agentscope \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --set schema.only=true --wait --timeout 10m
sh deploy/postgres-roles.sh
helm upgrade --install service "$CHART" --namespace agentscope \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --wait --timeout 10m
kubectl -n agentscope get pods
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

Four owner-only Jobs migrate Control (`cp` + `rt`), Portal (`portal`), Dataplane (`dp`) and
Managed (`ma`). Wait for all four, apply grants, then start workloads. Ordinary services only
validate versions/checksums; they do not run DDL. Helm repeats idempotent migration hooks but
does not automatically reapply business grants. New tables require this same two-stage upgrade.

## 5. Verify user workflows

Check Gateway health, bootstrap sign-in and Agent creation. Configure an [Environment](/v2/en/service/environments) and export its ID as `ENVIRONMENT_ID`; Session admission requires this binding even with user files disabled. From `agentscope-service`, or using
the Kubernetes bundle's `smoke.py`, verify native Session creation and Events:

```bash
python3 release/smoke.py --base http://127.0.0.1:18080 \
  --env-file /private/service.env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
# With a working model, repeat using a new state file and --model-turn.
# After restarting the components, reuse that state file with --resume.
```

Run this command from `agentscope-service`; a resource-only pass does not qualify inference.
Also run a tool-using Turn in your intended Environment, check incremental SSE and reconnect,
and test unfinished work after replica restart. See [your first Managed Agent](/v2/en/service/create-managed-agent).

## Maintain the installation

Before an incompatible upgrade, back up PostgreSQL including private objects, enabled file
volumes and original Vault/signing keys. Stop admission and drain Turns, tools and channel
replies. Stop retired Scheduler/orchestration workloads before starting the combined Control Plane.
Run the new schema jobs, reapply grants, then replace compatible roles together.

The current `dp`/`ma` baselines reject incompatible old versions/checksums and unversioned
Managed data. They do not convert previous runtime state or import filesystem objects. Use a
fresh runtime data space or an explicitly verified migration; never delete a ledger to bypass
validation. Image rollback does not reverse schema changes. PVCs remain on Helm uninstall.

## Deployment boundaries and production planning

All five roles use RollingUpdate Deployments and can scale independently. Brain's bounded
Harness and MQ callback pools are configured under `dataplane`; physical tool concurrency is
under `hands`. A suspended Turn releases active execution resources while preserving Session
FIFO order. MCP batch waits can continue directly from durable records; other waits use Resume.
PostgreSQL provides durable coordination across replicas, so no sticky Session routing is required.

Self-hosting deploys the platform; a `self_hosted` tool Worker supplies an execution environment;
a Runtime Host executes Hosted Coding Agents. These roles are separate. Kubernetes-native
ControlPlane/ASDP is another deployment mode and is not an extra required Service component.
See [Environments](/v2/en/service/environments) and [Runtime Host](/v2/en/service/runtime-host).

## Network surfaces

| Component | Port | Exposure |
| --- | --- | --- |
| Gateway | 8080 | Public API / Console entry; Compose defaults to loopback 18080 |
| Control Plane | 8081 | Internal |
| Brain Dataplane | 8082 | Internal |
| Session Portal | 8084 | Internal |
| Hands | 8085 | Internal |
| PostgreSQL / Redis / MQ | Operator-configured | Private dependency network |

## Enable remote access

Route HTTPS to Gateway, configure `publicURL` in Helm or `BUILDER_OAUTH_PUBLIC_URL` in Compose,
and keep internal components private. Disable SSE buffering/caching and configure long read
timeouts. Verify event delivery, reconnect, OAuth/channel callbacks and the actual external URL.

## Install the published Docker Compose bundle

Use a newly built/published Compose archive explicitly containing this architecture and its
matching `SHA256SUMS`. Verify the checksum, extract it, enter its `agentscope-service` directory,
then use the image version and repository from its release manifest:

```bash
./init-env.sh YOUR_VERSION REGISTRY/NAMESPACE
# Fill reachable Redis/MQ endpoints, MQ auth/TLS and model credentials in .env.
# Keep BUILDER_LOCAL_DEV=false.
docker compose config --quiet
docker compose pull
docker compose up -d --wait --wait-timeout 600
python3 smoke.py --base http://127.0.0.1:18080 --env-file .env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
```

Compose starts PostgreSQL and automatically runs all four migrations and business grants before
services. Redis/MQ remain external and their five Topic/group pairs must already exist. Only
Gateway exposes a host port. Local tools require explicit `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`
and run in Hands. Stop with `docker compose down` to retain volumes; use `--remove-orphans` after
draining retired components. See [operations](/v2/en/service/operations#compose-operations).



<span id="self-hosting"></span>
<span id="three-deployment-boundaries"></span>
<span id="choose-a-deployment-path"></span>
<span id="hand-over-a-usable-platform"></span>
<span id="operate-the-platform"></span>

<span id="production-api-access"></span>

## Authentication and authorization

Keep `BUILDER_LOCAL_DEV=false` in production. The published-image Compose configuration disables local mode by default, and the complete Service Helm Chart disables it too. Sign in to Console using `CONTROL_PLANE_BOOTSTRAP_ADMIN` and `CONTROL_PLANE_BOOTSTRAP_PASSWORD` from your `.env` or Secret, then change the password in Profile.

Local tutorials omit authentication headers. Before using their APIs in production, sign in and select an authorized namespace:

```bash
export BASE_URL="https://agentscope.example.com"
```

Enter your platform username and password. On a new deployment, use `admin` and the password from `.env`, or the password you changed in Profile.

```bash
printf 'Username: '
IFS= read -r LOGIN_USER
printf 'Password: '
IFS= read -r -s LOGIN_PASSWORD
printf '\n'

LOGIN_JSON=$(
  jq -n --arg username "$LOGIN_USER" --arg password "$LOGIN_PASSWORD" \
    '{username: $username, password: $password}' \
  | curl -sS --fail-with-body "$BASE_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      --data-binary @-
)
unset LOGIN_PASSWORD
TOKEN=$(jq -er '.token' <<< "$LOGIN_JSON")

curl -sS --fail-with-body "$BASE_URL/api/v1/me/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '.items[] | {tenant, name}'
```

Choose a namespace from the response and use its `tenant` and `name` for `TENANT` and `NAMESPACE` above. The user token manages resources; see [application integration](/v2/en/service/service-api) for application credentials.

```bash
export TENANT="YOUR_AUTHORIZED_TENANT"
export NAMESPACE="YOUR_AUTHORIZED_NAMESPACE"
```

Include these headers on management requests. Scope fields in the body and query must match the selected scope:

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

See [production access management](/v2/en/service/access) for accounts, namespace roles, resource grants, and private work. Tool confirmations accept an application key explicitly granted `approve`; `interact` alone cannot approve a tool. Designated human approvals still require that user’s identity. Model, tool, and OAuth providers keep their own credentials in local mode too.

<span id="production-application-credentials"></span>

## Application credentials

Keep an actual `AGENT_ID` from [creating an Agent](/v2/en/service/create-managed-agent). The Application owner performs these management operations.

Use your platform Bearer token while developing. For a business backend, create an Application and issue an API key with explicit grants for the Agents, Teams or Workflows it may use. Credentials belong to the Application: replacing a key preserves access to its Sessions, while another application cannot read those Sessions simply because it uses the same Agent. Keep keys in your backend and check the business user's permissions there.


```bash
set -euo pipefail

APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "report-application"
}
JSON
)
APPLICATION_ID=$(jq -er '.application.id' <<< "$APPLICATION_JSON")
```

Issue a credential that can call this Agent:

```bash
KEY_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "name": "backend",
  "scopes": [
    "invoke",
    "read",
    "interact",
    "cancel",
    "webhooks:write"
  ],
  "targets": [
    {
      "type": "agent",
      "id": "$AGENT_ID"
    }
  ]
}
JSON
)
AGENTSCOPE_API_KEY=$(jq -er '.apiKey' <<< "$KEY_JSON")
CREDENTIAL_ID=$(jq -er '.credential.id' <<< "$KEY_JSON")
```

A key is returned in plaintext only when it is issued. To rotate it, create a replacement, update and verify your application, then revoke the previous credential. The scopes are `invoke`, `read`, `interact`, `cancel`, `approve` and `webhooks:write`. Grant `approve` explicitly when the application should confirm tool calls. An application key does not become a designated human approver.

<Accordion title="List and revoke old credentials">

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

Issue and verify a replacement with the creation request above. Then replace `OLD_CREDENTIAL_ID` with the old credential ID from the list. This disables that key.

```bash
OLD_CREDENTIAL_ID="OLD_CREDENTIAL_ID_FROM_LIST"
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials/$OLD_CREDENTIAL_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

For application Session API calls, add `-H "X-API-Key: $AGENTSCOPE_API_KEY"` to the unauthenticated requests in local tutorials. Use the authorized user's Bearer token for human approval. Python callers use `ServiceClient(BASE_URL, AGENTSCOPE_API_KEY)`; management clients use a user token.


### Application limits

Read the current version before updating concurrency and token limits:

```bash
APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
APPLICATION_VERSION=$(jq -er '.application.version' <<< "$APPLICATION_JSON")

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "version": $APPLICATION_VERSION,
  "maxConcurrent": 5,
  "tokenBudget": 1000000
}
JSON
```

<span id="production-cli-install"></span>

## Install the production CLI and Runtime Host

Install both commands from the CLI archive built with the same source as the Service. If that release has a published Go module tag, Go 1.26+ can install the matching version:

```bash
export SERVICE_VERSION=YOUR_CURRENT_RELEASE
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v"$SERVICE_VERSION"
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v"$SERVICE_VERSION"
as connect https://agentscope.example.com
```

Configure PATH using the [Runtime Host guide](/v2/en/service/runtime-host#install-with-go), substitute your actual Service URL, and follow CLI login or short-lived enrollment prompts. Install and authenticate the provider separately.
