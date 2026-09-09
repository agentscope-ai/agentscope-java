# Deploy AgentScope Service

Use the **version and registry namespace from the published release notes**. This repository does not imply that candidate images have already been published.

```bash
./init-env.sh VERSION REGISTRY/NAMESPACE
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

Open http://localhost:18080. Sign in as `admin` with `AISTIO_BOOTSTRAP_PASSWORD` from your local `.env`, then change the password in your profile. No demo users are created. The bootstrap values apply only when the users table is empty; restarting never resets accounts. `init-env.sh` preserves an existing `.env`.

The stack persists PostgreSQL, shared workspaces, and artifacts in three named volumes. Stop with `docker compose down`; do not add `-v` unless you intend to delete application data. Keep `.env`, especially the vault key, with your backups. `POSTGRES_DB` defaults to `agentscope`; change it when pointing this stack at a restored database on the same PostgreSQL instance. Generated database passwords are URL-safe hex; manually supplied passwords must also be URL-safe because Compose interpolates them into DSNs.

Configure a model and an Environment before your first Session. For a trusted local evaluation, set `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true` and recreate the control container. Local tools execute inside the dataplane container. For other installations, configure a sandbox or a self-hosted worker. The service does not include model credentials or third-party coding tools.

Only the gateway is published, on loopback by default. Remote access requires your HTTPS reverse proxy and a matching `BUILDER_OAUTH_PUBLIC_URL`; preserve long-lived SSE responses. Internal APIs and PostgreSQL should remain on the private network.

## Kubernetes

Operate PostgreSQL separately. Create `cp`, `rt`, and `dp` schemas with `postgres-init.sql` as the application database owner. Copy `kubernetes.env.example` to a private file, replace the values, and create the Secret:

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service --from-env-file=/private/path/service.env
helm upgrade --install service ./agentscope-service-VERSION.tgz \
  --namespace agentscope --set imageRepository=REGISTRY/NAMESPACE \
  --set existingSecret=agentscope-service --wait --timeout 10m
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

The workspace claim must support shared mounts across the control, data and scheduler pods (RWX storage, or an existing shared claim). The Chart keeps claims on uninstall. It uses one replica per component and Recreate updates; plan a maintenance window. It does not claim zero-downtime database upgrades or HA. The legacy `aistio` Chart remains available for Kubernetes-native control-plane/CRD integration; the complete Service Chart runs standalone HTTP mode and does not expose ASDP gRPC.

See the Service section of the project documentation for first-session tutorials, SDK attachment, configuration, backup/restore and release operations.
