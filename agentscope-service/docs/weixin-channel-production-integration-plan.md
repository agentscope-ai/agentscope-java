# Personal Weixin production integration plan

Status: neutral extension interfaces, Scheduler login/credential adapters, durable inbox and fenced leases, and the headless control-plane login slice are implemented. The console flow and real-account canary for the current runtime changes remain pending.

See [headless login operations](weixin-headless-login.md) for the implemented interface and its current validation limits.

This plan turns the validated Tencent iLink direct-text path into a production AgentScope login and runtime loop while keeping `agentscope-extensions-channel-weixin` equivalent in scope to the other provider extensions. The extension supplies reusable iLink and Channel capabilities; product behavior remains in AgentScope service modules.

## 1. Goals

- Connect a Personal Weixin account through QR authorization without exposing its runtime credential to the browser.
- Create or update an AgentScope Channel resource and start it through the existing Scheduler reconciliation path.
- Resume accepted messages after restart with at-least-once processing. A crash around external side effects can repeat Agent execution or replies.
- Support multiple Scheduler replicas while allowing only one active consumer for a Weixin account.
- Surface credential rejection and complete reauthorization without manually editing Channel properties.
- Keep the extension independently usable by a non-Builder Java host.

## 2. Non-goals for the first production slice

- Group conversations, media, files, cards, and arbitrary event messages.
- Remote revocation unless iLink exposes and validates a revocation operation.
- A generic authorization framework shared by all Channel providers. Only Weixin currently requires this QR flow.
- Moving Vault ownership or cryptography into the Scheduler.
- Storing the iLink runtime credential in Channel properties.

## 3. Dependency direction

```text
Browser
  |
  v
Go control plane
  - authentication and authorization
  - Channel resource and Weixin connection
  - provider login flow persistence
  - Vault encryption and credential revision
  - product status and audit
  |
  | internally authenticated HTTP
  v
Java Scheduler product Adapters
  - managed Weixin Channel factory
  - control-plane credential Adapter
  - Postgres runtime-state Adapter
  - runtime observation Adapter
  - stateless login-operation Adapter
  |
  v
agentscope-extensions-channel-weixin
  - iLink login and messaging protocol
  - standard Channel lifecycle
  - long polling and outbound delivery
  - provider-neutral host interfaces
```

Forbidden dependency directions:

- The extension must not depend on `agentscope-service`.
- The extension must not import Spring, JPA, Gin, product Vault types, or product request/response types.
- The Scheduler must not query or decrypt control-plane Vault tables directly.
- The Go control plane must not implement a second copy of the iLink protocol.

## 4. Extension target interface

The names below are design-level names. Exact Java naming can change during implementation, but the information exposed by the interface must remain neutral.

### 4.1 Login protocol module

```java
LoginChallenge start(LoginOptions options);
LoginStep poll(LoginSession session);
LoginStep verify(LoginSession session, String verificationCode);
```

`LoginChallenge` contains the one-time QR image plus a `LoginSession`. `LoginSession` contains only portable iLink state required for the next operation, such as the QR identifier, current polling endpoint, and provider expiry; the image is not carried on subsequent polls. Neither type contains owner, Vault, Channel-resource, or Agent identifiers.

`LoginStep` contains:

- provider status;
- an updated `LoginSession` when polling must continue;
- account and user identifiers when supplied by iLink;
- `WeixinCredentials` only when authorization is confirmed;
- a stable provider error category with a sanitized diagnostic message.

The login implementation validates redirect and base URLs against an iLink allowlist. It never logs a QR identifier, verification code, runtime credential, or context token.

### 4.2 Runtime Channel module

The Channel continues to implement the standard `Channel` interface:

```text
init -> start -> dispatch/deliver -> stop
```

Construction receives a neutral runtime configuration plus host interfaces. It does not read global static stores.

```text
WeixinChannelConfig
  accountId
  ilinkUserId
  baseUrl
  protocol/client version
  long-poll timeout
  request timeout
  retry limits
  lease duration

WeixinCredentialProvider
  current -> WeixinCredentials

WeixinStateStore
  cursor
  context token
  lease
  durable message claim/complete

WeixinRuntimeListener
  running
  stopped
  credential rejected
  transient failure/recovery
```

The extension reports technical observations. It does not create `REAUTH_REQUIRED`, update a Channel resource, or contact the console.

### 4.3 Standalone compatibility

A static credential Adapter and an in-memory state Adapter may be provided for examples and tests. They must be clearly documented as non-production. A compatibility `fromProperties` path may remain during migration, but the managed Scheduler path must use construction injection before product-only fields are removed.

## 5. Scheduler product Adapters

### 5.1 Managed Channel factory

`ManagedWeixinChannelFactory` receives the control-plane Channel entry, resolves product identifiers, and constructs the extension types. Product fields stop at this Adapter.

Input from control plane:

```json
{
  "type": "weixin",
  "ownerId": "owner_xxx",
  "properties": {
    "accountId": "provider-account",
    "ilinkUserId": "provider-user",
    "credentialRef": "cred_xxx",
    "credentialRevision": 3,
    "baseUrl": "https://ilinkai.weixin.qq.com"
  }
}
```

Only the resulting neutral connection configuration and injected host interfaces reach the extension.

### 5.2 Credential Adapter

The production `WeixinCredentialProvider` Adapter calls:

```http
POST /api/internal/channels/{channelId}/credential
{ "revision": 3 }
```

The control plane resolves the credential configured for that exact Channel resource and revision. The caller cannot supply an arbitrary owner and credential reference. The response is private-network-only, has `Cache-Control: no-store`, and returns the credential plus revision. The Scheduler retains plaintext only in memory.

Credential revision is present in the desired Channel configuration so a completed reauthorization changes the Scheduler fingerprint and restarts the runtime Channel.

### 5.3 Runtime-state Adapter

The production `WeixinStateStore` implementation belongs to the Scheduler and its database schema. It persists:

- update cursor;
- context token per peer;
- account-scoped lease with expiry and fencing generation;
- durable inbound message claim and processing result;
- runtime timestamps and sanitized failure state.

The control-plane Vault schema is not used for these records.

### 5.4 Runtime observation Adapter

The product Adapter translates extension observations into control-plane runtime reports. In particular, iLink `-14` becomes a product `REAUTH_REQUIRED` state, stops the active poller, and requires a new login flow.

### 5.5 Stateless login-operation Adapter

The Scheduler exposes internally authenticated operations backed by the extension:

```http
POST /api/internal/channel-providers/weixin/login/start
POST /api/internal/channel-providers/weixin/login/poll
POST /api/internal/channel-providers/weixin/login/verify
```

Each request is self-contained. A Scheduler replica does not retain flow state between calls. Sensitive responses travel only over the internal authenticated path and are immediately encrypted by the control plane.

## 6. Control-plane product modules

The Go control plane owns:

- authenticated public HTTP operations;
- Channel resource and Weixin connection lifecycle;
- login flow ownership, generation, expiry, and cancellation;
- encryption of portable provider session state;
- Vault credential creation and rotation;
- credential revision;
- authorization and audit;
- public status projection and error localization.

### 6.1 Public HTTP operations

```http
POST   /api/channels/{channelId}/weixin/link-flows
POST   /api/channels/{channelId}/weixin/link-flows/{flowId}/poll
POST   /api/channels/{channelId}/weixin/link-flows/{flowId}/verify
POST   /api/channels/{channelId}/weixin/link-flows/{flowId}/complete
POST   /api/channels/{channelId}/weixin/link-flows/{flowId}/cancel
POST   /api/channels/{channelId}/weixin/relink
POST   /api/channels/{channelId}/weixin/disconnect
GET    /api/channels/{channelId}/weixin/status
```

The public interface never returns a runtime credential, credential ciphertext, provider QR identifier, or internal credential reference. It may return the time-limited QR image content required for the user to scan. A connected status may expose the stable provider `accountId` (`ilink_bot_id`) for operator identification; it is not a credential.

### 6.2 Product state machine

```text
PENDING_LINK
  -> WAITING_SCAN
  -> SCANNED
  -> NEED_VERIFY_CODE (optional)
  -> AUTHORIZED
  -> COMPLETED
  -> STARTING
  -> RUNNING

Terminal or recovery states:
  EXPIRED
  CANCELLED
  FAILED
  REAUTH_REQUIRED
  DISCONNECTED
```

Redirect handling and retryable provider failures remain internal implementation details. `AUTHORIZED` means the control plane holds a temporary encrypted credential; `COMPLETED` means it has atomically installed that credential into the Vault and Channel connection.

### 6.3 Control-plane tables

`weixin_connections`:

```text
channel_id             primary key, references channels
owner_id
vault_id
credential_id
credential_revision
account_id
ilink_user_id
base_url
generation
status
last_error_code
connected_at
updated_at
```

`weixin_link_flows`:

```text
flow_id                primary key
channel_id
owner_id
initiator_id
generation
session_ciphertext
credential_ciphertext
result_ciphertext
status
error_code
expires_at
created_at
updated_at
```

Constraints:

- one current connection per Channel resource;
- one active connection per provider account unless a later product decision explicitly permits sharing;
- flow owner, initiator, Channel, and generation must match on every operation;
- temporary ciphertext is cleared on completion, cancellation, failure, or expiry;
- confirmation is idempotent;
- reauthorization rotates the existing credential when possible and increments revision.

## 7. Reliable message processing

The runtime must not advance the update cursor before messages are durably accepted. The production sequence is:

```text
poll iLink
  -> transactionally insert unseen messages into durable inbox
  -> advance cursor in the same transaction
  -> claim inbox item with fencing generation
  -> dispatch to AgentScope
  -> persist outcome
```

An in-memory message-ID cache may reduce duplicate work locally, but it is not the correctness mechanism. Database uniqueness and durable inbox state own idempotency across restarts.

Only one Scheduler replica may consume an account. Lease renewal uses compare-and-set ownership and a fencing generation so a stale holder cannot continue committing messages after losing the lease.

## 8. Security requirements

- Runtime credentials, verification codes, context tokens, and raw QR identifiers are redacted from logs and diagnostics.
- The browser never receives the runtime credential.
- Provider session and temporary credential state are encrypted at rest with the control-plane Vault key.
- Only the control plane holds the Vault master key.
- Internal calls require the existing internal token and private network policy; production should add mTLS where available.
- iLink redirect and base URLs use HTTPS and an explicit hostname allowlist.
- Login start, poll, and verify operations are rate-limited.
- Public responses and internal credential responses use `Cache-Control: no-store`.
- Disconnect distinguishes local credential removal from remote provider revocation and never claims remote revocation without evidence.

## 9. Implementation slices

### Slice 1: neutralize the extension interface

- Introduce portable login-session and observation types.
- Make login polling stateless.
- Introduce constructor-injected credential, state, and listener interfaces.
- Preserve a temporary compatibility path for existing tests.

Acceptance: extension tests compile without `agentscope-service`; two independent login sessions can be interleaved without shared mutable state.

### Slice 2: Scheduler Adapters

- Add managed Channel factory.
- Add control-plane credential Adapter.
- Add stateless internal login operations.
- Remove Scheduler use of the Java JPA Vault implementation for Weixin.

Acceptance: fake control plane can configure and start a Weixin Channel without product concepts reaching extension types.

### Slice 3: headless control-plane login

- Add connection and flow tables.
- Add public flow operations and internal Scheduler client.
- Reuse the existing OAuth flow patterns for ownership, generation, expiry, no-store responses, temporary encryption, and final transaction.
- Install credentials into the Go-owned Vault.

Acceptance: a command-line client can complete QR login and produce an enabled Channel without exposing plaintext credentials.

### Slice 4: production runtime state

- Add Postgres cursor, context, durable inbox, and fenced lease Adapter.
- Correct cursor commit ordering.
- Report detailed runtime observations.

Acceptance: restart and replica-failover tests show no lost accepted message and no concurrently active account consumer.

### Slice 5: console flow

- Replace manual Weixin provider fields with a connect wizard.
- Display scan, verification, connected, runtime, and reauthorization states.
- Keep credential and provider-generated identifiers out of ordinary forms.

Acceptance: a user can connect, test, reconnect, disable, and disconnect without editing raw properties.

### Slice 6: real-account canary

- Run inbound, reply, second-message acknowledgement, restart-resume, credential rejection, and reauthorization scenarios with a dedicated canary account.
- Add dashboards and alerts for poll health, inbound lag, credential rejection, lease contention, and dispatch failure.

Acceptance: all production definition-of-done checks pass before general availability.

## 10. Definition of done

- The extension contains no AgentScope product ownership, Vault, database, or console code.
- A standalone Java test host can use the extension with static credentials and in-memory state.
- AgentScope uses external Adapters for credentials, persistence, runtime observations, and login orchestration.
- No plaintext runtime credential appears in browser traffic, Channel properties, logs, or persistent non-Vault fields.
- Login confirmation atomically installs or rotates the credential and enables the Channel.
- Credential revision restarts the runtime Channel after reauthorization.
- Restart resumes from the durable cursor.
- Durable inbox and idempotency prevent lost accepted messages and duplicate product actions.
- Multi-replica execution has one fenced consumer per Weixin account.
- iLink credential rejection reaches the console as `REAUTH_REQUIRED` and reauthorization restores `RUNNING`.
- The real-account canary passes the full login, bidirectional messaging, restart, and reauthorization sequence.

## 11. Documentation placement

- Extension README: standalone usage, host interfaces, supported iLink protocol scope, and extension-level tests.
- AgentScope service documentation: Vault, login flows, Scheduler Adapters, operations, audit, and console behavior.
- ADR: the stable dependency-direction and ownership decision.
- `CONTEXT.md`: domain vocabulary only, with no implementation details.
