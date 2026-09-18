---
title: "Local installation"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

This guide is for local developers. Use Docker Compose to start the complete AgentScope Service without building source. The published package includes Gateway, Control, Dataplane, Scheduler and PostgreSQL for evaluation, feature development and integration work. For Kubernetes/Helm deployments, see [production installation](/v2/en/service/kubernetes).

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

Supply the real credential and repeat `docker compose up -d --wait --wait-timeout 600`. Local tools execute inside Dataplane, without automatically mounting host files. Follow [quickstart](/v2/en/service/first-session) to create a Managed Agent.

Alternatively, connect an existing Coding Agent through [Hosted execution](/v2/en/service/hosted-agent). Keep Local disabled and configure an appropriate Environment when tool isolation is needed.

## Network surfaces

| Component | Container port | Exposure |
| --- | --- | --- |
| Gateway | 8080 | Host `127.0.0.1:18080` by default |
| Control | 8081 | Internal network |
| Dataplane | 8082 | Internal network |
| Scheduler | 8083 | Internal network |
| PostgreSQL | 5432 | Internal network |

A same-host reverse proxy can use `127.0.0.1:18080`. In another container, localhost refers to that proxy container; configure a shared network or reachable host address. Expose Gateway to users and keep internal components and PostgreSQL private.

## Enable remote access

Use this section when accessing your local service from another device or testing public OAuth/Channel callbacks. Prepare a domain and TLS certificate, then proxy HTTPS to Gateway. Set `BUILDER_OAUTH_PUBLIC_URL=https://agentscope.example.com` in `.env`. Adjust `BIND_ADDRESS` and `GATEWAY_PORT` if needed, then recreate containers.

The proxy must forward SSE promptly, avoid event-stream caching and allow sufficiently long read timeouts. Verify login, long replies, reconnection and OAuth/Channel callbacks, not just the home page.

## Persist data

Named volumes store PostgreSQL, shared Workspaces and Artifacts. Locate project volumes with `docker volume ls` and back them up according to your storage policy. Preserve the Vault master key from `.env` with encrypted data.

For host directories, configure explicit mounts and access for container user `65532:65532`. An Agent instruction containing a local path does not make it readable inside the container. File access must match the selected Environment.

## Change configuration or version

After editing `.env`:

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

Pull new images before an upgrade. `init-env.sh` preserves existing configuration, so edit `SERVICE_VERSION` to change versions. Coordinate secret changes across consumers; Vault master keys cannot be casually replaced.

Complete Compose runs standalone HTTP. ASDP-dependent SDKs need the [corresponding External integration deployment](/v2/en/service/external-agent). See [production installation](/v2/en/service/kubernetes) for production deployments and rehearse [recovery](/v2/en/service/operations) before upgrading.

## Stop, resume and diagnose

`docker compose down` stops services while preserving volumes. Repeat the startup command to resume. Do not add `-v` for ordinary shutdown; it deletes data volumes.

For startup failure, inspect `docker compose ps -a` and `docker compose logs --tail=100` for image, database and component errors. Resolve a port conflict by changing `GATEWAY_PORT` and the corresponding `BUILDER_OAUTH_PUBLIC_URL` in `.env`, then recreate containers.

Next: [Quickstart](/v2/en/service/first-session) · [Production installation](/v2/en/service/kubernetes).
