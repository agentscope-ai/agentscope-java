# Troubleshooting

Start with the affected work record. Record the version, time and Session, Task, Attempt or Run IDs, then inspect the relevant component. Remove tokens, passwords and sensitive business data before sharing logs.

| Symptom | Check first |
| --- | --- |
| Image pull fails | Published release availability, namespace, tag, authentication and architecture |
| Container is unhealthy | `docker compose ps`, database readiness and component logs |
| Helm Pod stays Pending | Bound PVCs and StorageClass access-mode support |
| Login fails | Bootstrap versus existing password; whether the database already contains accounts |
| Local Environment rejected | Explicit Local opt-in, or use Sandbox / Self-hosted |
| Agent does not respond | Model credentials, Environment binding, tool confirmations and Dataplane logs |
| Team remains waiting | Unfinished member tasks, missing input, approvals or deliverable review |
| History disappears after restart | Accidental memory storage, changed database or volumes |
| OAuth callback fails | Public origin, platform callback URL and HTTPS reachability |
| Runtime Host offline | Installed provider, credentials, network and daemon logs |
| SDK gRPC connection fails | Kubernetes-native ASDP availability and correct gRPC port |

## Collect component logs

```bash
docker compose logs --tail=200 control data scheduler gateway
kubectl -n agentscope logs deployment/service-agentscope-control --tail=200
kubectl -n agentscope describe pod POD_NAME
```

Gateway health does not verify model or tool execution. Inspect the Dataplane for Session failures, the Scheduler for channel and scheduled-work failures, and Control for resources, accounts and dispatch.

## Restarting did not reset the administrator password

This is expected. `AISTIO_BOOTSTRAP_PASSWORD` applies only to an empty account table. Change existing passwords through Profile or administrator account management rather than regenerating `.env`.

## Vault decryption fails

Check that recovery preserved the original `BUILDER_VAULT_MASTER_KEY` and that all components use the same value. Restore matching configuration and data before trying to replace keys.
