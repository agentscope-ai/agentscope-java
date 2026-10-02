---
title: Troubleshooting
zh_link: /v2/zh/service/troubleshooting
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Start with the affected work record. Record the version, time and Session, Task, Attempt or Run IDs, then inspect the relevant component. Remove tokens, passwords and sensitive business data before sharing logs.

| Symptom | Check first |
| --- | --- |
| Image pull fails | Release manifest namespace, tag, authentication and architecture |
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

Gateway health does not verify model or tool execution. Inspect Dataplane for Managed Session failures, Scheduler for channel/Worker scheduling, and Control for product Automations, resources, accounts and orchestration. Hosted provider failures also need the machine's daemon logs.

## Restarting did not reset the administrator password

This is expected. `AISTIO_BOOTSTRAP_PASSWORD` applies only to an empty account table. Change existing passwords through Profile or administrator account management rather than regenerating `.env`.

## Vault decryption fails

Check that recovery preserved the original `BUILDER_VAULT_MASTER_KEY` and that all components use the same value. Restore matching configuration and data before trying to replace keys.

## Work arrived but no final result

Inspect the Run, Node and latest Attempt from Issue Executions, not just the last message. Check dependencies, approvals or signals for waiting, missing input for blocked, and errors/partial artifacts for failed. Inbox Request changes does not start execution. Verify task reporting for External adapters and provider exit/reporting for Hosted work.

## Repeated Webhook or Endpoint requests

Query the existing Delivery/Invocation first. Reuse the same key and content for the same logical request; assign a new key only to new work. Check trigger event filters, authentication and schemas. After SSE disconnects, query the returned statusUrl before resubmitting.

## Agent API and SSE

Keep the session ID, turn ID, latest event ID, HTTP status and sanitized error. Distinguish the page connection, task execution and context recovery:

| Symptom | Action |
| --- | --- |
| Refresh shows only a suffix or loses tools produced while away | Render snapshot.items/tools first, then stream after as_of; a cursor alone cannot rebuild UI state |
| Stream closes and task status is unclear | Read turns/{turn} or snapshot; disconnect neither cancels nor calls for a new turn |
| Task stays running after run.ended / item.completed | Wait for the target turn outcome; an attempt, message or tool is not the whole task |
| 400 / 409 cursor error | Verify session scope and reload snapshot; never parse or increment cursors yourself |
| Resource pagination returns 410 | Restart from its first page; resource-page cursors are not SSE cursors |
| Answer submitted but the tool does not continue | Read required_actions and GET turns/{turn}/actions; accepted is receipt, rejected requires checking reason and pending |
| Steer returns 409 | The task may have ended or closed input; reread status, and use a new turn for a separate question |
| Checkpoint restore returns 409 | Resolve open tasks, actions, pending inputs and unknown tool outcomes; restoring does not undo external operations |
| Cost is incomplete or budget blocks execution | Inspect unpriced calls, usage and pricing in usage/budget; adjust limits and explicitly resume as task state permits |
| No webhook received | Check allowed hosts, registration time, event filters and deliveries; fix the receiver before retrying a paused webhook |
| Heartbeats but no text | Check task, tool and model state; deltas may be unavailable. If content arrives in bursts, inspect proxy buffering |

See [Agent API operations](/v2/en/service/session-event-log) and [SSE handling](/v2/en/service/sse-events). Published Endpoints retain their statusUrl/eventsUrl and [separate event contract](/v2/en/service/sse-events#endpoint-protocol-scope).
