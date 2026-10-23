# Environment Variables Reference

All environment variables consumed by `agentscope-codingagent`.

## Required

| Variable                    | Description                                                          | Example                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `ANTHROPIC_API_KEY`         | API key for Claude models (primary LLM)                              | `sk-ant-api03-...`                     |
| `GITHUB_WEBHOOK_SECRET`     | HMAC secret used to verify `X-Hub-Signature-256` on webhook events   | `your-random-secret`                   |

## GitHub Authentication (one of the following)

| Variable                    | Description                                                          | Example                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `GITHUB_TOKEN`              | Personal Access Token or GitHub App installation token (simple path) | `ghp_...`                              |
| `GITHUB_APP_ID`             | GitHub App ID (for App-based auth — generates JWT + installation token) | `12345`                             |
| `GITHUB_APP_PRIVATE_KEY`    | Path to the PEM file for the GitHub App private key                  | `/secrets/github-app.pem`              |
| `GITHUB_APP_INSTALLATION_ID`| Installation ID (required when using `GITHUB_APP_ID`)               | `67890`                                |

## Model Configuration

| Variable                    | Description                                                          | Default / Example                      |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `MODEL_ID`                  | Primary model identifier                                             | `claude-sonnet-4-5`                    |
| `FALLBACK_MODEL_ID`         | Fallback model used when primary is rate-limited or overloaded       | `claude-haiku-3-5`                     |
| `OPENAI_API_KEY`            | OpenAI API key (if using OpenAI models)                              | `sk-proj-...`                          |

## Sandbox

| Variable                    | Description                                                          | Default / Example                      |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `CODING_SANDBOX_TYPE`       | Sandbox type: `local` (no isolation) or `docker`                     | `local`                                |
| `CODING_SANDBOX_IMAGE`      | Docker image for the coding sandbox                                  | `agentscope/coding-sandbox:latest`     |
| `DOCKER_HOST`               | Docker daemon socket (override for remote Docker or Podman)          | `unix:///var/run/docker.sock`          |

## Tools

| Variable                    | Description                                                          | Example                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `TAVILY_API_KEY`            | Tavily Search API key — enables the `web_search` tool                | `tvly-...`                             |

## Storage

| Variable                    | Description                                                          | Default                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `SQLITE_DB_PATH`            | Path to the SQLite database file used by `SqliteBaseStore`           | `./coding-agent.db`                    |

## Token Encryption (for storing GitHub tokens at rest)

| Variable                    | Description                                                          | Example                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `TOKEN_ENC_KEYSET`          | JSON-serialized Google Tink AEAD keyset (base64 or raw JSON)         | `{"primaryKeyId":...}`                 |
| `TOKEN_ENC_KEYSET_PATH`     | Path to a file containing the Tink keyset JSON (alternative to above)| `/secrets/tink-keyset.json`            |

## Observability

| Variable                         | Description                                                     | Default                                |
|----------------------------------|-----------------------------------------------------------------|----------------------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT`   | OTLP endpoint for distributed tracing (leave empty to disable)  | `` (disabled)                          |
| `TRACING_SAMPLE_RATE`           | Micrometer tracing sample probability (0.0–1.0)                  | `0.1`                                  |
| `PORT`                           | HTTP server port                                               | `8080`                                 |

## Budget / Limits

| Variable                    | Description                                                          | Default                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `MAX_MODEL_CALLS_PER_THREAD`| Per-thread LLM call budget (enforced by `ThreadBudgetHook`)          | `50`                                   |
| `MAX_MODEL_CALLS_GLOBAL`    | Global LLM call budget across all threads (`ModelCallLimitHook`)     | `5000`                                 |

## Webhook Service

| Variable                    | Description                                                          | Default                                |
|-----------------------------|----------------------------------------------------------------------|----------------------------------------|
| `GITHUB_BOT_LOGIN`          | GitHub login of the bot account; prevents the agent from replying to its own comments | `agentscope-bot` |
