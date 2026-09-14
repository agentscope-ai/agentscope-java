---
title: Configuration reference
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
| `AISTIO_PRODUCT_DSN` | Product database | Uses schema `cp` |
| `AISTIO_STORAGE_DSN` | Control-plane runtime database | Set `search_path=rt` |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` | Java JDBC connection | Full credential names: `BUILDER_DB_USER`, `BUILDER_DB_PASSWORD`; schema `dp` |
| `BUILDER_JWT_SECRET` | User token signing | At least 32 characters; consistent across components |
| `BUILDER_INTERNAL_TOKEN` | Internal service authentication | At least 32 characters; not a user credential |
| `BUILDER_VAULT_MASTER_KEY` | Credential encryption | Shared across components; back up with data |
| `AISTIO_BOOTSTRAP_ADMIN` / `PASSWORD` | Initial administrator | Full password name: `AISTIO_BOOTSTRAP_PASSWORD`; 12–72 bytes |
| `AISTIO_SEED_USERS` | Go demo-account seeding | Release configuration sets `false` |
| `BUILDER_SEED_USERS` | Java demo-account seeding | Release configuration sets `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | Permit Local Environments | Defaults to `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | Public origin | Must match OAuth callback configuration |
| `DASHSCOPE_API_KEY` | Default DashScope model credentials | Required only for that model path |
| `BUILDER_E2B_API_KEY` | E2B environment credentials | Required only for the corresponding Sandbox path |

## Paths and internal addresses

Release deployments share `/data/workspaces`. The control plane uses `AISTIO_WORKSPACE_ROOT`; Java uses `BUILDER_WORKSPACE_ROOT`. `AISTIO_ARTIFACT_ROOT` selects the artifact directory.

`BUILDER_CONTROL_URL`, `BUILDER_DATA_URL` and `BUILDER_SCHEDULER_URL` are internally reachable addresses. Do not replace them with the browser's localhost address.

## Schema management

Dataplane and Scheduler default to Hibernate `update`; Go runs migrations on startup. `BUILDER_JPA_DDL_AUTO=validate` checks existing tables without initializing a new database; use it only when you manage the schema separately. Back up and rehearse upgrades with the [operations guide](/v2/en/service/operations).

## Verify a configuration change

Distinguish deployment settings from Agent configuration before choosing a check:

| Change | Apply and verify |
| --- | --- |
| Compose `.env` | Recreate affected containers; `docker compose restart` does not apply new environment variables to existing containers |
| Helm values / Secret | Follow the production installation procedure and confirm affected Pods use the new configuration; environment variables do not refresh in running processes |
| Agent Instructions / Definition | Save, publish, and bind the intended revision as required by the editor; start new work and inspect its actual definition |
| Session defaults | Start a new Session to check inheritance; inspect explicit selections in existing Sessions separately |
| Memory document content | Ask the Agent to read it again; previous replies do not update automatically |

For example, after changing default model credentials, recreate services using the Compose procedure in [local installation](/v2/en/service/quickstart), check health, and send a simple request in a new Managed Chat. Once model access works, run the knowledge checks in the [presales team case](/v2/en/service/cases/presales-team) to isolate resource-binding problems.

Record setting names, application version, recreation time, and the new Session ID without secret values. Changing bootstrap settings does not overwrite an existing administrator password; see [accounts](/v2/en/service/access).
