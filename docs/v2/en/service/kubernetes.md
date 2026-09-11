---
title: "Production installation with Kubernetes and Helm"
---

[简体中文](/v2/zh/service/kubernetes)

The published Service Chart installs Gateway, Control, Dataplane and Scheduler. You manage PostgreSQL, storage, domain and TLS. Components default to one replica with Recreate updates; plan maintenance windows.

## 1. Prepare dependencies

Prepare Kubernetes, Helm and reachable PostgreSQL. Workspaces need an RWX StorageClass or an existing shared PVC because several components mount them. Artifacts default to RWO. Single-node RWO behavior does not establish shared access across nodes.

Download the Chart and deployment configuration package from the Release and verify SHA256SUMS. Execute `postgres-init.sql` in the target database as its application owner to create `cp`, `rt` and `dp`. Plan backups for the database, files and keys.

## 2. Create a Secret

Copy `kubernetes.env.example` to a private file and replace every placeholder: database connections, random JWT/internal/Vault secrets, bootstrap password and required model credentials. URL-encode URI passwords and provide the raw JDBC password separately. Configure TLS according to database certificates.

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service --from-env-file=/private/path/service.env
```

Keep plaintext configuration and rendered Secrets out of the repository.

## 3. Configure values

Use this `production-values.yaml` starting point. Replace domain, storage classes, Ingress class and TLS Secret. Provision the TLS Secret beforehand or through your certificate controller.

```yaml
existingSecret: agentscope-service
allowLocalEnvironment: false
publicURL: https://agentscope.example.com
persistence:
  workspaces:
    storageClass: shared-rwx
    size: 20Gi
  artifacts:
    storageClass: standard
    size: 20Gi
ingress:
  enabled: true
  className: nginx
  host: agentscope.example.com
  tls:
    - hosts: [agentscope.example.com]
      secretName: agentscope-service-tls
```

Use `existingClaim` for retained PVCs. Configure `imagePullSecrets` for private images and controller-specific annotations for SSE timeouts and buffering. Tune requests and limits under `control`, `dataplane`, `scheduler` and `gateway` using measured workload requirements.

## 4. Install a pinned version

Use the Release's OCI Chart location and image namespace:

```bash
helm upgrade --install service oci://REGISTRY/NAMESPACE/charts/agentscope-service \
  --version VERSION \
  --namespace agentscope \
  --set imageRepository=REGISTRY/NAMESPACE \
  -f production-values.yaml \
  --wait --timeout 10m
```

Alternatively replace the OCI location and `--version VERSION` with the downloaded `./agentscope-service-VERSION.tgz`. Authenticate to private OCI registries with Helm first. Keep Chart and component image versions aligned.

## 5. Verify user workflows

```bash
kubectl -n agentscope get pods,pvc,svc,ingress
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

Confirm Bound PVCs and Ready Pods. Sign in through the public domain with the bootstrap administrator and change its password. Verify the model, Environment, first Chat, Issue delivery and streaming. Port-forwarding helps diagnosis but does not validate public callbacks.

## Maintain the installation

Restart affected Deployments after Secret updates. Follow [operations](/v2/en/service/operations) before upgrading and retain prior Charts, values and image versions. PVCs are retained on uninstall; explicitly select them with existingClaim on reinstall.

This Chart runs complete Service standalone HTTP. Kubernetes-native Aistio/ASDP is a separate deployment mode, requiring deliberate SDK connectivity planning rather than blindly combining Charts. The single-replica installation does not guarantee zero-downtime migrations or multi-replica HA.
