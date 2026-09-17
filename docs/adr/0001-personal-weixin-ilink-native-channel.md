---
status: accepted
---

# Keep the Personal Weixin iLink integration as a reusable Java Channel extension

Personal Weixin will use a native Java `weixin` Channel extension backed by Tencent iLink. The extension owns provider protocol behavior and the standard AgentScope Channel runtime behavior, while AgentScope product concerns are supplied by host Adapters in the Scheduler and control plane. This preserves the same dependency direction as the DingTalk, Feishu, WeCom, GitHub, and GitLab extensions and prevents a reusable transport extension from becoming coupled to one product deployment.

## Interface and ownership

`agentscope-extensions-channel-weixin` may expose QR-login primitives, protocol request/response types, long polling, message mapping, outbound delivery, cursor/context state interfaces, lease interfaces, runtime observations, and in-memory or fake Adapters for standalone use and tests.

It must not depend on or expose AgentScope product concepts such as `ownerId`, Vaults, credential references, database tables, Scheduler configuration refresh, public HTTP routes, console state, audit policy, Agent selection, or Channel-resource lifecycle. Its public types use iLink and standard Channel vocabulary only.

The Scheduler owns the product Adapters that construct a Weixin Channel, resolve a configured credential from the control plane, persist runtime state, report observations, and expose stateless internal login operations backed by the extension. The Go control plane owns authenticated login flows, authorization, Vault encryption, credential revision, Channel resources, user-visible state, and audit records.

## Consequences

- Login session state is an explicit portable value rather than hidden mutable state in a singleton client, allowing any Scheduler replica to perform the next iLink operation.
- The extension receives a neutral credential provider and runtime-state interface through construction; it does not resolve an AgentScope owner or Vault reference.
- Production database and control-plane Adapters live outside the extension. In-memory Adapters remain suitable only for standalone examples and tests.
- iLink `-14` is reported as a provider credential-rejection observation. The product decides whether that becomes `REAUTH_REQUIRED`, a notification, or another workflow.
- The product does not duplicate the iLink protocol in Go. It invokes a stateless, internally authenticated Java interface backed by the extension.
- Direct database access from Scheduler to the control-plane Vault is rejected because it couples the Scheduler to Vault schema and cryptography ownership.

The first supported runtime scope remains direct text conversations. Group, media, and event messages require explicit routing and security semantics before being added.
