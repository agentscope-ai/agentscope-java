---
title: "Local installation and quickstart"
---

[简体中文](/v2/zh/service/quickstart)

Start the complete Service from its published Compose package without building source. It includes Gateway, Control, Dataplane, Scheduler and PostgreSQL for local evaluation or a single-machine installation.

## Prepare

Install Docker Engine or Docker Desktop, Compose v2 and OpenSSL. Check `docker info` and `docker compose version`. Provide your own model credentials. Reserve persistent disk space for database and work files; CPU and memory depend on concurrency and tool load.

Download `agentscope-service-VERSION-compose.tar.gz` and `SHA256SUMS` from the selected [Release](https://github.com/agentscope-ai/agentscope-java/releases). Compare the archive's SHA-256 with its manifest entry using `sha256sum` on Linux or `shasum -a 256` on macOS. Use the Release's VERSION and REGISTRY/NAMESPACE below; the registry path has no `https://` prefix.

## 1. Start

```bash
tar -xzf agentscope-service-VERSION-compose.tar.gz
cd agentscope-service
./init-env.sh VERSION REGISTRY/NAMESPACE
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

Initialization creates a mode-`600` `.env` with database, JWT, internal-token, Vault and initial administrator secrets. Running the script again preserves the file rather than changing versions or resetting passwords.

## 2. Sign in

```bash
docker compose ps
curl -fsS http://localhost:18080/actuator/health
```

After the entire stack is healthy, open `http://localhost:18080`. Sign in with `admin` and `AISTIO_BOOTSTRAP_PASSWORD` from `.env`, then change the password in Profile. Bootstrap creates an administrator only in an empty user database; restarts do not reset accounts.

## 3. Configure execution

For a trusted local evaluation, edit `.env`:

```dotenv
BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
DASHSCOPE_API_KEY=YOUR_MODEL_CREDENTIAL
```

Supply the real credential and repeat `docker compose up -d --wait --wait-timeout 600`. Local tools execute inside Dataplane, without automatically mounting host files. Follow [your first conversation and deliverable](/v2/en/service/first-session) to create a Managed Agent.

Alternatively, connect an existing Coding Agent through [Hosted execution](/v2/en/service/hosted-agent). Keep Local disabled and configure an appropriate Environment when tool isolation is needed.

## Stop, resume and diagnose

`docker compose down` stops services while preserving volumes. Repeat the startup command to resume. Do not add `-v` for ordinary shutdown; it deletes data volumes.

For startup failure, inspect `docker compose ps -a` and `docker compose logs --tail=100` for image, database and component errors. Resolve a port conflict by changing `GATEWAY_PORT` and the corresponding `BUILDER_OAUTH_PUBLIC_URL` in `.env`, then recreate containers.

Next: [Docker networking and storage](/v2/en/service/docker) · [Production Helm installation](/v2/en/service/kubernetes).
