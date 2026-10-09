---
title: Configuration reference
zh_link: /v2/zh/service/configuration
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use `.env` for Docker. On Kubernetes, keep sensitive settings in an existing Secret and configure ingress and storage through Chart values.

| Setting | Purpose | Notes |
| --- | --- | --- |
| `IMAGE_REPOSITORY` / `SERVICE_VERSION` | Compose image namespace and version | Use a specific published release |
| `BIND_ADDRESS` / `GATEWAY_PORT` | Compose listener | Defaults to `127.0.0.1` / `18080`; update public URL when changed |
| `POSTGRES_DB` | Compose database name | Defaults to `agentscope`; can select a restored database |
| `POSTGRES_PASSWORD` | Compose database password | Preserve after initialization; use URL-safe values |
| `CONTROL_PLANE_PRODUCT_DSN` | Product database | Uses schema `cp` |
| `CONTROL_PLANE_STORAGE_DSN` | Control-plane runtime database | Set `search_path=rt` |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` | Java JDBC connection | Full credential names: `BUILDER_DB_USER`, `BUILDER_DB_PASSWORD`; schema `dp` |
| `BUILDER_JWT_SECRET` | User token signing | At least 32 characters; consistent across components |
| `BUILDER_INTERNAL_TOKEN` | Internal service authentication | At least 32 characters; not a user credential |
| `BUILDER_VAULT_MASTER_KEY` | Credential encryption | Shared across components; back up with data |
| `CONTROL_PLANE_BOOTSTRAP_ADMIN` / `PASSWORD` | Initial administrator | Full password name: `CONTROL_PLANE_BOOTSTRAP_PASSWORD`; 12–72 bytes |
| `CONTROL_PLANE_SEED_USERS` | Go demo-account seeding | Release configuration sets `false` |
| `BUILDER_SEED_USERS` | Java demo-account seeding | Release configuration sets `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | Permit Local Environments | Defaults to `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | Public origin | Must match OAuth callback configuration |
| `DASHSCOPE_API_KEY` | Default DashScope model credentials | Required only for that model path |
| `BUILDER_E2B_API_KEY` | E2B environment credentials | Required only for the corresponding Sandbox path |

<span id="agent-api-settings" />

## Unified service invocation settings

Session/Turn calls, events, commands, and Webhooks are stored by the control plane. Durable storage supports process recovery. These settings are separate from Managed Dataplane settings below:

| Configuration | Default / source | Purpose |
| --- | --- | --- |
| `service-controlplane --service-event-retention` | `720h`; `0` disables pruning | Prunes old terminal Turn event increments while retaining cumulative snapshots; reload snapshot after cursor expiry (410) |
| `CONTROL_PLANE_ENDPOINT_CREDENTIAL_KEY` | Falls back to platform JWT secret | Encrypts Webhook signing secrets; this compatibility name remains. Keep it consistent across replicas and backups. Application keys are stored as hashes |
| `service-controlplane --enable-asdp` | `true` | Initializes execution-channel handlers also used by HTTP workers; HTTP workers need no gRPC port access |

Manage Application concurrency and token budgets through its API. Session timeout, per-task budget, and input/output contracts are selected at creation. Public Session Webhooks use control-plane HTTPS validation and signed delivery; native dataplane Webhook allowed-hosts settings do not govern this public path.

## Native Managed session API settings

These are **Dataplane Spring properties**. Supply them through a loaded application.yml, startup arguments or process environment configuration. Adding a line only to Compose `.env` does not pass it to a container; map it in the service definition and recreate the data container.

| Property | Default | Purpose |
| --- | --- | --- |
| `builder.agent-api.files.max-bytes` | `16777216` | Upload limit per file (16 MiB); independent of model/context limits |
| `builder.agent-api.webhooks.allowed-hosts` | Empty | Comma-separated exact destination hosts; HTTPS port 443 only; no destinations allowed by default |
| `builder.agent-api.webhooks.poll-ms` | `2000` | Webhook delivery poll interval in milliseconds |
| `builder.agent-api.pricing.models` | `{}` | JSON string mapping model IDs to per-million input/output token prices |
| `builder.agent-api.pricing.currency` | `USD` | Estimated cost currency; must match max_cost budgets |
| `builder.agent-api.trace-enabled` | `false` | Administrator diagnostics; ownership and ROLE_ADMIN / ROLE_SESSION_TRACE are still required |
| `builder.agent-api.inbox-poll-ms` | `1000` | Durable task dispatch polling interval in milliseconds |
| `builder.agent-api.export-poll-ms` | `5000` | Native-to-public event catch-up interval in milliseconds, not a token refresh interval |

For example, in YAML actually loaded by Dataplane:

```yaml
builder:
  agent-api:
    files:
      max-bytes: 16777216
    webhooks:
      allowed-hosts: notify.example.com
    pricing:
      currency: USD
      models: '{"your-model":{"input_per_million":1,"output_per_million":2}}'
```

Use your own notification host and model ID. Example prices illustrate format only; operators maintain actual prices. Missing prices mean incomplete cost data, not zero cost. Message/tool deltas are durable by default; preview is not required.

Replicas share the Data Plane database and a BaseStore supporting conditional writes. Filesystem storage can also be distributed. See [record locations](/v2/en/service/session-event-log). Disable proxy buffering for SSE, flush promptly and allow a sufficient read timeout. The 15-second heartbeat keeps the connection alive without promising model output.

## Paths and internal addresses

Release deployments share `/data/workspaces`. The control plane uses `CONTROL_PLANE_WORKSPACE_ROOT`; Java uses `BUILDER_WORKSPACE_ROOT`. `CONTROL_PLANE_ARTIFACT_ROOT` selects the artifact directory.

`BUILDER_CONTROL_URL`, `BUILDER_DATA_URL` and `BUILDER_SCHEDULER_URL` are internally reachable addresses. Do not replace them with the browser's localhost address.

## Schema management

Dataplane and Scheduler default to Hibernate `update`; Go runs migrations on startup. `BUILDER_JPA_DDL_AUTO=validate` checks existing tables without initializing a new database; use it only when you manage the schema separately. Back up and rehearse upgrades with the [operations guide](/v2/en/service/operations).

## Verify a configuration change

Distinguish deployment settings from Agent configuration before choosing a check:

| Change | Apply and verify |
| --- | --- |
| Compose `.env` | Recreate affected containers; `docker compose restart` does not apply new environment variables to existing containers |
| Helm values / Secret | Follow the production installation procedure and confirm affected Pods use the new configuration; environment variables do not refresh in running processes |
| Agent Instructions / Definition | Update through definition APIs; publish the appropriate Workspace revision and verify a new Session |
| Session defaults | Start a new Session to check inheritance; inspect explicit selections in existing Sessions separately |
| Memory document content | Ask the Agent to read it again; previous replies do not update automatically |

For example, after changing default model credentials, recreate services using the Compose procedure in [deployment setup](/v2/en/service/quickstart), check health, and create a new Managed session through the API and submit a simple request. Once model access works, run the fixed inputs in the [CRM proposal case](/v2/en/service/cases/in-product-delivery). If you then connect Memory, verify its reads separately to isolate resource-binding problems.

Record setting names, application version, recreation time, and the new Session ID without secret values. Changing bootstrap settings does not overwrite an existing administrator password; see [accounts](/v2/en/service/access).
