# Helm deployment

The complete Service Chart installs the Gateway, Control Plane, Dataplane and Scheduler. You operate PostgreSQL and the storage provisioner separately.

## Prerequisites

Prepare a Kubernetes cluster, Helm 3.17+ or a compatible version, and reachable PostgreSQL. Run the release package's `postgres-init.sql` as the application database owner to create the `cp`, `rt` and `dp` schemas.

Workspaces request ReadWriteMany storage by default. Supply an RWX StorageClass or an existing shared PVC. Artifacts request ReadWriteOnce. RWO workspace storage can work for a single-node test; this does not establish shared storage support across nodes.

## 1. Create the configuration Secret

Copy `kubernetes.env.example` from the release package to a private file. Fill database connections, random secrets and initial administrator credentials. URL-encode passwords inside Go DSNs; supply the raw JDBC password separately. Configure database TLS for your network and certificates.

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service   --from-env-file=/private/path/service.env
```

Keep the completed file and rendered Secret out of Git.

## 2. Install

Use the Chart archive downloaded from the release:

```bash
helm upgrade --install service ./agentscope-service-VERSION.tgz   --namespace agentscope   --set imageRepository=REGISTRY/NAMESPACE   --set existingSecret=agentscope-service   --wait --timeout 10m
```

After OCI publication, use `oci://REGISTRY/NAMESPACE/charts/agentscope-service` with `--version VERSION` instead of a file. Private registries require both Helm authentication and Kubernetes `imagePullSecrets`.

Configure storage with `persistence.workspaces.storageClass`, `persistence.workspaces.existingClaim` and the corresponding artifact settings. For Ingress, configure host, class, TLS and controller-specific SSE settings, and set `publicURL`.

## 3. Verify

```bash
kubectl -n agentscope get pods,pvc,svc
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

Sign in and follow [Your first Session](first-session.md). Verify Bound PVCs, Ready components, and readable history and files after restarting.

## Operational boundaries

The Chart uses one replica per component and Recreate updates. Schedule a maintenance window. It does not promise zero-downtime database migration or qualified multi-replica HA. Restart affected Deployments after Secret updates.

Uninstall retains PVCs. Reinstall with explicit existingClaim settings to reuse them. This Chart runs standalone HTTP mode; the legacy `aistio` Chart serves Kubernetes-native control-plane use cases. Do not blindly install both as one product stack.
