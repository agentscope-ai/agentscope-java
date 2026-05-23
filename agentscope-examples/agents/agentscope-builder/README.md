# AgentScope Builder

AgentScope Builder is a multi-tenant web application for building, configuring, and running LLM agents. It provides a REST API and chat UI for managing agents, workspaces, skills, and subagents.

## Quick Start

```bash
# Set your model API key
export DASHSCOPE_API_KEY=sk-xxx

# Build and run
mvn -pl agentscope-examples/agents/agentscope-builder -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-builder/target/agentscope-builder-*.jar
```

The server starts on `http://localhost:8080`. On first launch, a default agent config is auto-generated at `.agentscope/agentscope.json`.

## Configuration

All configuration uses the `builder.*` property prefix. Properties can be set in `application.yml`, as JVM system properties (`-Dbuilder.xxx`), or as environment variables (`BUILDER_XXX`).

### Model

```yaml
builder:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}
    model-name: qwen-max
    stream: true
```

Alternatively, provide your own `Model` Spring bean to use any supported model (OpenAI, Anthropic, Gemini, Ollama, etc.).

### Workspace

```yaml
builder:
  workspace: ${BUILDER_WORKSPACE:}   # Working directory; defaults to JVM cwd
```

The agent config file `.agentscope/agentscope.json` is read from this directory. Each agent's workspace defaults to `.agentscope/workspace` unless overridden per-agent.

### JWT

```yaml
builder:
  jwt:
    secret: ${BUILDER_JWT_SECRET:builder-default-dev-secret-change-in-production-32chars}
```

**Must be overridden in production** with a secret of at least 32 characters.

---

## Filesystem Modes

Builder supports three filesystem modes that control how per-(user, agent) workspaces are backed. Set via `builder.workspace-store.fs-spec`.

### Local Mode (default)

```yaml
builder:
  workspace-store:
    fs-spec: local
    local:
      max-file-size-mb: 10
```

Agents run directly on the host with `LocalFilesystemWithShell`. Each user's workspace is isolated via namespace-scoped directories under the agent workspace root (`users/{userId}/agents/{agentId}/`). Shell commands execute on the host OS.

**When to use:** Single-node deployments, local development, trusted environments.

### Sandbox Mode

```yaml
builder:
  workspace-store:
    fs-spec: sandbox
  sandbox:
    enabled: true
    image: agentscope/python-sandbox:py311-slim
    network: none
    workspace-root: /workspace
    isolation: USER
    projection-roots: AGENTS.md,skills,subagents,knowledge
    cpu-count: 1
    memory-bytes: 1073741824   # 1 GiB
```

Agents run inside Docker containers, providing OS-level isolation per user. Workspace files (skills, subagents, AGENTS.md, knowledge) are projected from the host into the container. The web API continues managing projected files on the host filesystem.

**Prerequisites:**
- Docker daemon accessible to the application
- The sandbox image built and available locally (or pullable)

**Configuration reference:**

| Property | Env Var | Default | Description |
|---|---|---|---|
| `builder.sandbox.enabled` | `BUILDER_SANDBOX_ENABLED` | `false` | Enable sandbox mode |
| `builder.sandbox.image` | `BUILDER_SANDBOX_IMAGE` | `agentscope/python-sandbox:py311-slim` | Docker image |
| `builder.sandbox.network` | `BUILDER_SANDBOX_NETWORK` | `none` | Docker network mode |
| `builder.sandbox.workspace-root` | `BUILDER_SANDBOX_WORKSPACE_ROOT` | `/workspace` | Mount path inside container |
| `builder.sandbox.isolation` | `BUILDER_SANDBOX_ISOLATION` | `USER` | Isolation scope: `SESSION`, `USER`, `AGENT`, `GLOBAL` |
| `builder.sandbox.projection-roots` | `BUILDER_SANDBOX_PROJECTION_ROOTS` | `AGENTS.md,skills,subagents,knowledge` | Host files projected into container |
| `builder.sandbox.cpu-count` | `BUILDER_SANDBOX_CPU_COUNT` | `0` (no limit) | CPU limit per container |
| `builder.sandbox.memory-bytes` | `BUILDER_SANDBOX_MEMORY_BYTES` | `0` (no limit) | Memory limit per container (bytes) |

**Isolation scopes:**
- `SESSION` — one container per chat session
- `USER` — one container per user, shared across sessions
- `AGENT` — one container per agent, shared across users
- `GLOBAL` — single container shared globally

**Distributed sandbox:** By default, sandbox runs in single-node mode. For multi-replica deployments, provide a distributed `Session` bean (e.g. Redis-backed) so sandbox state is shared across instances.

**When to use:** Multi-tenant deployments where agents execute untrusted code, or when OS-level isolation between users is required.

### Remote Mode

```yaml
builder:
  workspace-store:
    fs-spec: remote
```

Both agent runtime and workspace management use a distributed `BaseStore` backend. Agent filesystem operations go through `RemoteFilesystem` / `CompositeFilesystem`, and the web API workspace store also uses `RemoteFilesystem`.

**Prerequisites:**
- A `BaseStore` Spring bean must be provided (e.g. Redis, OSS, or a custom implementation)
- A distributed `Session` bean is required (e.g. `RedisSession`)

**When to use:** Horizontally scaled deployments where workspace data must be shared across multiple application replicas.

---

## Persistence (users & agent metadata)

Builder persists two kinds of records via Spring Data JPA: **user accounts** (id, username, password hash, roles) and **agent definitions** (id, owner, workspace path, system prompt, tool / skill allow-lists, share grants, ...). The default backend is an embedded H2 database; production deployments switch to MySQL / PostgreSQL via the bundled `jdbc` Spring profile.

> The database file is **intentionally not stored under `builder.workspace`** so workspace volumes and Docker / sandbox mounts never accidentally pull in catalog tables. The two locations are unrelated.

### Default: embedded H2

```yaml
spring:
  datasource:
    url: ${BUILDER_DB_URL:jdbc:h2:file:${BUILDER_DB_PATH:${user.home}/.agentscope-builder/db};AUTO_SERVER=TRUE;MODE=MYSQL;DB_CLOSE_DELAY=-1}
    driver-class-name: ${BUILDER_DB_DRIVER:org.h2.Driver}
    username: ${BUILDER_DB_USER:sa}
    password: ${BUILDER_DB_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: ${BUILDER_JPA_DDL_AUTO:update}
```

On first start, Hibernate creates the schema and `JpaUserStore` seeds a default `admin/admin` user. The DB lives at `${user.home}/.agentscope-builder/db.mv.db` unless `BUILDER_DB_PATH` (the bare file prefix, no `.mv.db`) or `BUILDER_DB_URL` overrides it.

`AUTO_SERVER=TRUE` lets external tools (H2 Console, DBeaver) connect to the running DB; drop the flag if that exposure is unwanted.

#### Bundled demo accounts (H2 only)

For the local quick-start path, [`src/main/resources/data-h2.sql`](src/main/resources/data-h2.sql) seeds two additional regular-user accounts so you can poke at the UI without first calling the admin API:

| Username | Password | Role |
|---|---|---|
| `admin` | `admin` | `user, admin` (seeded by `JpaUserStore`) |
| `bob` | `bob` | `user` |
| `alice` | `alice` | `user` |

The seed script is gated to H2 (`spring.sql.init.platform=h2` + `spring.sql.init.mode=embedded`) and is **always disabled** under the `jdbc` Spring profile, so MySQL / PostgreSQL deployments never pick up the demo accounts. Statements use `MERGE INTO ... KEY (user_id)` so re-running the script across restarts is idempotent. Set `BUILDER_SQL_INIT_MODE=never` to disable the H2 seed even in dev.

**When to use:** Single-node deployments, local development, demos.

### Switching to MySQL / PostgreSQL

Activate the bundled `jdbc` Spring profile and override the DataSource:

```bash
export SPRING_PROFILES_ACTIVE=jdbc

# MySQL example (profile defaults already point at MySQL on localhost; override per env)
export BUILDER_DB_URL="jdbc:mysql://db:3306/agentscope_builder?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC"
export BUILDER_DB_USER=agentscope
export BUILDER_DB_PASSWORD=agentscope
export BUILDER_DB_DRIVER=com.mysql.cj.jdbc.Driver

# PostgreSQL example
# export BUILDER_DB_URL="jdbc:postgresql://db:5432/agentscope_builder"
# export BUILDER_DB_DRIVER=org.postgresql.Driver

export BUILDER_JPA_DDL_AUTO=update   # use 'validate' once schema is managed by Flyway/Liquibase

java -jar agentscope-builder-*.jar
```

You can also stay on the default (no-profile) configuration and just set `BUILDER_DB_*` env vars directly; the profile mainly exists so a bare `SPRING_PROFILES_ACTIVE=jdbc` is a sensible MySQL default.

The MySQL (`com.mysql:mysql-connector-j`) and PostgreSQL (`org.postgresql:postgresql`) JDBC drivers are bundled at runtime scope; Hibernate auto-detects the dialect from the JDBC URL.

### Schema overview

(Managed by Hibernate when `ddl-auto` is `update`; pin the schema with Flyway / Liquibase in production and switch to `BUILDER_JPA_DDL_AUTO=validate`.)

| Table | Key columns | Notes |
|---|---|---|
| `builder_user` | `user_id` (PK), `username` (unique, case-insensitive), `password_hash`, `roles_csv`, `created_at` | Default `admin/admin` is seeded on first boot if empty. |
| `builder_agent` | `row_id` (PK, identity), `owner_id` (soft FK → `builder_user.user_id`, indexed), `agent_id`, `workspace_path`, agent metadata, ... | `(owner_id, agent_id)` uniquely identifies an agent definition. `workspace_path` stores the user-supplied agent workspace path verbatim so external tools can join SQL rows to on-disk workspaces. |
| `builder_agent_share` | `id` (PK), `agent_row_id` (hard FK → `builder_agent.row_id`, ON DELETE CASCADE via JPA orphan-removal), `grantee_type`, `grantee_id`, `tier`, `created_at`, `created_by` | One row per share grant. Deleting the agent removes all grants. |

> List-shaped agent settings (tools allow / deny, skills allow / deny, group-chat mention patterns) are stored as JSON strings in single columns — schema stays portable across H2 / MySQL / PostgreSQL.

> `owner_id` is a *soft* foreign key on purpose: deleting a user is an application-level flow (`AdminUserController#delete`) that first revokes shares and removes the user's agents. Add a hard `FOREIGN KEY ... ON DELETE CASCADE` via your migration tool when desired.

---

## Agent Configuration

Agents are defined in `.agentscope/agentscope.json`:

```json
{
  "main": "default",
  "agents": {
    "default": {
      "name": "my-agent",
      "sysPrompt": "You are a helpful assistant.",
      "workspace": ".agentscope/workspace",
      "maxIters": 10,
      "model": "anthropic/claude-sonnet-4-6",
      "sandbox": {
        "mode": "all",
        "scope": "user"
      }
    }
  }
}
```

Per-agent sandbox config (`sandbox.mode` / `sandbox.scope`) is metadata stored with the agent definition. The runtime sandbox behavior is currently controlled globally via `builder.sandbox.*` properties.

---

## Environment Variables Reference

| Variable | Property | Default | Description |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `builder.dashscope.api-key` | (none) | DashScope API key |
| `BUILDER_MODEL_NAME` | `builder.dashscope.model-name` | `qwen-max` | Model name |
| `BUILDER_WORKSPACE` | `builder.workspace` | (JVM cwd) | Working directory |
| `BUILDER_JWT_SECRET` | `builder.jwt.secret` | (dev default) | JWT signing secret |
| `BUILDER_WORKSPACE_FS_SPEC` | `builder.workspace-store.fs-spec` | `local` | Filesystem mode |
| `BUILDER_SANDBOX_ENABLED` | `builder.sandbox.enabled` | `false` | Enable sandbox |
| `BUILDER_SANDBOX_IMAGE` | `builder.sandbox.image` | `agentscope/python-sandbox:py311-slim` | Sandbox Docker image |
| `BUILDER_SANDBOX_ISOLATION` | `builder.sandbox.isolation` | `USER` | Sandbox isolation scope |
| `BUILDER_AGENT_NAME` | `builder.agent.name` | `builder-agent` | Default agent name |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | (none) | Set to `jdbc` to switch DataSource defaults from H2 to MySQL |
| `BUILDER_DB_URL` | `spring.datasource.url` | `jdbc:h2:file:${BUILDER_DB_PATH:...}` (no profile) / MySQL localhost (`jdbc` profile) | Full JDBC URL override |
| `BUILDER_DB_PATH` | (substituted into `spring.datasource.url`) | `${user.home}/.agentscope-builder/db` | Bare H2 file path prefix (no `.mv.db`); only used when `BUILDER_DB_URL` is left at its default |
| `BUILDER_DB_USER` | `spring.datasource.username` | `sa` (H2) / `agentscope` (jdbc profile) | Database username |
| `BUILDER_DB_PASSWORD` | `spring.datasource.password` | (empty / `agentscope`) | Database password |
| `BUILDER_DB_DRIVER` | `spring.datasource.driver-class-name` | `org.h2.Driver` / `com.mysql.cj.jdbc.Driver` | JDBC driver class (`org.postgresql.Driver` for PostgreSQL) |
| `BUILDER_JPA_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate schema mode; use `validate` once Flyway / Liquibase manages the schema |
| `BUILDER_SQL_INIT_MODE` | `spring.sql.init.mode` | `always` | `always` runs `data-h2.sql` on every startup (idempotent MERGE); `never` disables the seed. Spring Boot 4 only treats `jdbc:h2:mem:` as `embedded`, so we don't use that mode by default |
| `BUILDER_SQL_INIT_PLATFORM` | `spring.sql.init.platform` | `h2` | Picks `data-${platform}.sql`. Leave on `h2` for local; the `jdbc` profile is unaffected because it forces `mode=never` |
