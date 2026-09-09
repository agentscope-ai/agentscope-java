# Docker quickstart

Start the complete Service, sign in, then run your first Agent Session.

## Prerequisites

Install Docker Engine or Docker Desktop, Compose v2 and OpenSSL. Published-image deployment does not require Maven, Go or Node.js. Model execution requires your own model credentials.

```bash
docker info
docker compose version
```

Download `agentscope-service-VERSION-compose.tar.gz` and `SHA256SUMS` from the selected release. Verify the archive against the published checksum. Replace `VERSION` and `REGISTRY/NAMESPACE` below with the values from that release.

## 1. Initialize and start

```bash
tar -xzf agentscope-service-VERSION-compose.tar.gz
cd agentscope-service
./init-env.sh VERSION REGISTRY/NAMESPACE
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

The initializer creates database, JWT, internal-token, Vault and administrator secrets in a mode-`600` `.env` file. Running it again preserves existing configuration. Inspect this file locally and keep it out of Git.

## 2. Sign in and check readiness

```bash
docker compose ps
curl -fsS http://localhost:18080/actuator/health
```

Open `http://localhost:18080`. Use `admin` and the `AISTIO_BOOTSTRAP_PASSWORD` in `.env`, then change the password in Profile. The release deployment does not create demo users. Bootstrap credentials apply only to an empty database; restarting does not reset accounts.

## 3. Configure execution

For a trusted local evaluation, set `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true` in `.env`, add model credentials such as `DASHSCOPE_API_KEY`, and repeat the startup command. Local tools execute inside the Dataplane container.

For other installations, keep Local disabled and configure a Sandbox or Self-hosted Environment in the console. Continue with [Your first Session](first-session.md).

## Stop and resume

```bash
docker compose down
docker compose up -d --wait --wait-timeout 600
```

Database, workspace and artifact volumes remain available. `down -v` deletes volumes and is not a normal stop command. See [Docker deployment](docker.md) and [Operations](operations.md) for remote access and upgrades.
