# Channel Integration Context

This glossary defines the messaging-channel concepts used by AgentScope integrations, including the personal Weixin channel in the multi-tenant Agent platform.

## Channel concepts

**Channel**:
A runtime adapter that receives messages from one provider, routes them through AgentScope, and sends replies back to that provider.
_Avoid_: Channel resource, connector

**Channel extension**:
A reusable provider-specific module that implements the standard Channel interface and provider protocol without owning product accounts, persistence policy, or user workflows.
_Avoid_: Channel resource, managed connection

**Channel resource**:
The product-owned configuration and lifecycle record for one configured Channel instance, including routing and enabled state.
_Avoid_: Channel extension, external account

**Personal Weixin**:
An individual Weixin account that a platform user authorizes for Agent conversations through Tencent's official ClawBot service.
_Avoid_: WeCom, enterprise WeChat, unofficial WeChat protocol

**iLink**:
The official Weixin ClawBot service through which an authorized Personal Weixin account exchanges bot messages.
_Avoid_: webhook protocol, iPad protocol

**Platform user**:
The authenticated person in the Agent platform, identified within a company by the pair `compId + userId`.
_Avoid_: Weixin user, sender

**Weixin account**:
The external Personal Weixin identity authorized by one Platform user; it is distinct from that user's platform identity.
_Avoid_: platform account, channel

**Weixin connection**:
The product-owned relationship between a Channel resource, its authorizing Platform user, and one external Weixin account.
_Avoid_: Channel extension, login session, account file

**Channel binding**:
A routing rule that selects the logical AgentScope target for messages received through a Channel resource.
_Avoid_: Weixin connection, login session

**Provider login session**:
Short-lived iLink state that progresses one QR authorization attempt and expires independently of a Weixin connection.
_Avoid_: Channel session, Agent session, Weixin connection

**Runtime credential**:
The secret issued by iLink after authorization and used by a running Channel to call iLink on behalf of a Weixin account.
_Avoid_: Context token, credential reference, QR code

**Peer**:
The conversation counterpart used to build an AgentScope session key; it may be an individual user or a group conversation.
_Avoid_: sender

**Context token**:
The conversation context supplied by iLink for associating a reply with the inbound Weixin conversation.
_Avoid_: session token, access token

**Update cursor**:
The continuation position used by a Weixin account when receiving the next batch of iLink messages.
_Avoid_: sync token, message ID

## Runtime delivery concepts

**Reply delivery**:
Sending an Agent reply back to the peer that produced the inbound message. It is owned by the host
platform, which persists the reply and retries it independently of the inbound message that is
already accepted.
_Avoid_: outbox, send, dispatch

**Provider receipt**:
The identifier iLink returns when it accepts a reply for delivery; the durable evidence that the
provider, not merely the local process, took the message.
_Avoid_: local ack, delivery ID, message ID

**Dispatch failure**:
An accepted inbound message that could not be processed end to end — the Agent run failed, or the
message could not be handed to the host for reply delivery.
_Avoid_: delivery failure

**Delivery failure**:
A reply the provider repeatedly refused. The Agent has already run, so a delivery failure never
replays the inbound message.
_Avoid_: dispatch failure, send error

**Abandoned message**:
An accepted inbound message whose dispatch kept failing; it is tombstoned instead of retried
forever, and is cleaned up on the same retention as a completed one.
_Avoid_: failed message, dropped message
