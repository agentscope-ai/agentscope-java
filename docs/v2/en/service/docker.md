---
title: "Docker: single-machine deployment and remote access"
---

[简体中文](/v2/zh/service/docker)

After [local setup](/v2/en/service/quickstart), configure remote access, persistent storage and maintenance here. Production use requires HTTPS, appropriate execution isolation and verified recovery.

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

Prepare a domain and TLS certificate, then proxy HTTPS to Gateway. Set `BUILDER_OAUTH_PUBLIC_URL=https://agentscope.example.com` in `.env`. Adjust `BIND_ADDRESS` and `GATEWAY_PORT` if needed, then recreate containers.

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

Complete Compose runs standalone HTTP. ASDP-dependent SDKs need the [corresponding External integration deployment](/v2/en/service/external-agent). See [Helm](/v2/en/service/kubernetes) for Kubernetes storage and scheduling, and rehearse [recovery](/v2/en/service/operations) before upgrading.
