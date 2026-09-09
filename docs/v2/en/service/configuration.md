# Configuration reference

Use `.env` for Docker. On Kubernetes, keep sensitive settings in an existing Secret and configure ingress and storage through Chart values.

| Setting | Purpose | Notes |
| --- | --- | --- |
| `IMAGE_REPOSITORY` / `SERVICE_VERSION` | Compose image namespace and version | Use a specific published release |
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
| `BUILDER_SEED_USERS` | Java legacy-account seeding | Release configuration sets `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | Permit Local Environments | Defaults to `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | Public origin | Must match OAuth callback configuration |
| `DASHSCOPE_API_KEY` | Default DashScope model credentials | Required only for that model path |
| `BUILDER_E2B_API_KEY` | E2B environment credentials | Required only for the corresponding Sandbox path |

## Paths and internal addresses

Release deployments share `/data/workspaces`. The control plane uses `AISTIO_WORKSPACE_ROOT`; Java uses `BUILDER_WORKSPACE_ROOT`. `AISTIO_ARTIFACT_ROOT` selects the artifact directory.

`BUILDER_CONTROL_URL`, `BUILDER_DATA_URL` and `BUILDER_SCHEDULER_URL` are internally reachable addresses. Do not replace them with the browser's localhost address.

## Schema management

Dataplane and Scheduler currently default to Hibernate `update`; Go runs migrations on startup. Without separately managed versioned SQL migrations, changing `BUILDER_JPA_DDL_AUTO` to `validate` will not initialize a new database. Back up and rehearse upgrades with the [operations guide](operations.md).
