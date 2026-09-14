---
title: "Message events: Channel integration and routing"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

**DESIGN → Channels** connects messaging platforms to AgentScope Service. A Channel owns the connection and routing; an Agent executes work. Use an [Endpoint](/v2/en/service/endpoints) for a stable HTTP interface to your own application.

## Interface tour

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/channels.png" alt="Channel catalog with platform type and running status" />
</Frame>

Each card shows the platform type, conversation isolation and runtime status. Open a channel to configure credentials and routing. **running** means the adapter has started; send a message from the actual platform to verify routing and result delivery.

## Connect a platform

Prepare the platform application, permissions and credentials. Create a Channel, choose a platform from those offered by your installation and fill in its specific fields. Complete platform-side callback, verification or connection settings using the details shown in the form.

External callbacks need a publicly reachable HTTPS address configured by your administrator. A container address or localhost is not reachable from the messaging platform.

## Route messages

Add a target Agent in the Channel's binding rules and set the matching scope. Review default and specific rules so that conversations reach the intended Agent. You can also view associations under an Agent's Connections → Channels.

Save and send a read-only request from a test account. Confirm receipt and a reply from the correct Agent, then verify follow-up messages and supported file handling in the same external conversation.

## Track durable work

The Channel detail includes reception and work-return views for work associations and outbound results. Follow linked Issues for durable work, and use Issue Source to trace its entry point. Agent completion alone does not prove that a response reached the external platform.

## Troubleshoot

For missing inbound messages, inspect subscriptions, callback reachability and credentials. For received messages without execution, inspect bindings, Agent readiness and permissions. For completed work without replies, inspect sending permissions and return errors. Recheck both directions after credential rotation.

Next: [Agents](/v2/en/service/agents) · [Issues](/v2/en/service/issues).
