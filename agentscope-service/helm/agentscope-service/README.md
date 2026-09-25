# AgentScope Service Helm Chart

Installs the Gateway, Control Plane with Dashboard, Dataplane and Scheduler as a single Service deployment. PostgreSQL and storage provisioning are managed separately.

Before installation:

1. Create a PostgreSQL database with `cp`, `rt` and `dp` schemas owned by the application database user.
2. Create a Secret from the release package's `kubernetes.env.example`, replacing all placeholders with private values. Retain these keys with your backups.
3. Provide storage supporting the shared Workspace volume's `ReadWriteMany` access mode and the Artifact volume's `ReadWriteOnce` mode.

```bash
helm upgrade --install service ./agentscope-service-VERSION.tgz \
  --namespace agentscope --create-namespace \
  --set imageRepository=REGISTRY/NAMESPACE \
  --set existingSecret=service-credentials
```

The Chart uses its `appVersion` for image tags unless `imageTag` is supplied. Configure `publicURL` for the externally reachable gateway. Ingress is optional; service defaults to ClusterIP. Local execution is disabled by default and should only be enabled on a trusted installation.

Each component runs one replica with Recreate updates. The Chart runs standalone HTTP mode, without Kubernetes-native controllers or ASDP gRPC. It does not provide high availability or zero-downtime migrations. Back up the database, both volumes and original keys before upgrades. PVCs created by the Chart are retained on uninstall.

See the release runbook and the bilingual Service documentation under `docs/v2/{en,zh}/service/` in the source distribution for complete setup, configuration, recovery and verification steps.
