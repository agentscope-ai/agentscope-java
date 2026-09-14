---
title: "External registration and connection settings"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

External applications keep their own processes and frameworks. These settings connect catalog identity, the HTTP contract and optional ASDP transport. See the [integration guide](/v2/en/service/external-agent) for Java and Python snippets.

## SDK settings

| Java `AistioConfig.Builder` | Python `instrument()` | Meaning |
| --- | --- | --- |
| `builder(agentKey)` | `agent_key` | Logical Agent identity shared by application replicas |
| `tenant` / `namespace` | `tenant` / `namespace` | Registration scope; both default to `default` |
| `instanceKey` | `instance_key` | Unique per replica, stable across its restarts |
| `controlPlaneHttp` | `control_plane_http` | HTTP registration/API address including scheme |
| `controlPlane` | `control_plane` | ASDP address as `host:port` |
| `publicBaseUrl` | `contract_http_base_url` | Application contract URL reachable from the control plane |
| `contractHttpPort` | `contract_http_port` | Contract listening port; Java defaults to 18090, Python to 8080 |
| `internalToken` | `internal_token` | Trusted workload/bootstrap credential for initial registration |
| `registrationCredential` | `registration_credential` | Subsequent registration identity credential |
| `registeredIdentity(agentId, bindingId, generation)` | `agent_id` / `binding_id` / `generation` | Existing registered identity; retain these as a consistent set |
| `eventJournalDir` | `event_journal_dir` | Persistent event journal directory |
| `startHttp` | `start_http` | Start contract HTTP service; defaults to true |
| `startGrpc` | `start_grpc` | Start ASDP; Java defaults to false, Python to true |
| `enableEvents` | `enable_events` | Event reporting; Java follows `startGrpc` when unset, Python defaults to true |
| `sessionAffinity` | `session_affinity` | Session affinity information for application routing |

Java's `startHttpRegister(true)` starts HTTP registration independently. When unset, registration follows whether `controlPlaneHttp` is configured. Python currently registers during ASDP initialization; setting `start_grpc=False` does not provide independent HTTP registration.

## Network and deployment modes

| Direction | Requirement |
| --- | --- |
| Application → Service HTTP | Reachable registration address and valid identity credential |
| Control plane → application contract | Routable DNS and port; container localhost normally refers only to that container |
| Application → ASDP listener | An ASDP-enabled deployment and its actual gRPC address |

Standard Service Compose/Helm runs standalone HTTP without an ASDP listener. Python automatic integration and ASDP transport require an ASDP-enabled Kubernetes-native Aistio deployment. An SDK flag does not add a gRPC listener to Gateway.

## Identity and credentials

Persist the registered identity and registration credential. Reuse identity on ordinary restarts and give new replicas different instance keys. Generation participates in control-plane identity and dispatch validation; do not increment it manually to bypass stale-execution checks.

An administrator supplies bootstrap credentials to trusted workloads. Browser login tokens, Endpoint API keys and Runtime Host enrollment tokens have different purposes. Model and business-tool credentials remain application-owned; catalog registration does not mount Managed Vaults automatically.

## Verify configuration

Check framework identity, instance and contract address in the catalog. Run a conversation in the application and verify supported context/history queries. Then test commands, cancellation and task dispatch according to adapter capabilities. Observation-only registration does not imply Issue execution support.
