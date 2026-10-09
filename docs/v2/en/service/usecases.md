---
title: "Application scenarios"
description: "Use the Session API for interactive products, background work and verified delivery."
zh_link: /v2/zh/service/usecases
---

Agent as a Service integration starts with a business action: generating a proposal, investigating an incident, verifying material or researching an account. Decide who initiates work, what the Agent may access and how the result will be checked before choosing an Agent, Team or Workflow. The target can change while the application keeps using the Session API.

Begin with [deployment](/v2/en/service/quickstart) and [your first Managed Agent](/v2/en/service/create-managed-agent), then prepare credentials using the [integration guide](/v2/en/service/service-api). The following fictional examples describe integration designs rather than completed production deployments.

## Generate a customer proposal inside a CRM

A user working on opportunity OPP-104 wants a proposal draft they can review after leaving the page. The application associates the opportunity version with a Session, making generation part of the existing CRM workflow. Restore the original Session when reopening the opportunity. Submit a new Turn for a changed requirement version and retain previous results so an older completion cannot overwrite a newer proposal. A successful write creates a workspace file; making it downloadable still requires uploading its bytes or registering an Artifact.

Follow the [Generate a customer proposal inside a CRM](/v2/en/service/cases/in-product-delivery) walkthrough.

## Turn an incident into a reviewable repair

Incident INC-204 reports incorrect status filtering and pagination. After deduplicating alerts, the engineering platform starts a repair against a specific repository and commit, asking the Agent to investigate, modify code and provide test evidence. Record the incident, base commit, repair attempt, Session and Turn together. Retry the same repair with its original idempotency key; submit new review feedback as another Turn. Session callbacks can bring completion back to the engineering platform without keeping a browser connection open.

Follow the [Turn an incident into a reviewable repair](/v2/en/service/cases/incident-to-pr) walkthrough.

## Integrate document verification into a business process

A document pipeline needs to compare extracted fields from invoice DOC-101 with its source. The Agent identifies discrepancies and evidence; the application decides whether processing continues or requires human review. Associate the document version, extraction batch and rule version with the Session and Turn. Keep the document pending while verification runs. Validate the result structure before displaying findings alongside source references. A detected business inconsistency is a valid finding, distinct from an execution failure such as an unreadable file.

Follow the [Integrate document verification into a business process](/v2/en/service/cases/document-verification) walkthrough.

## Offer a conversational business assistant inside a product

A user asks why order O-1001 is delayed and whether next-day delivery can be guaranteed. The assistant should read the facts, explain known status and create a support ticket only after confirmation, using the same business identity as the product. When the Agent requires confirmation, render the actual pending operation from required_actions. Return its request ID and decision through that Turn’s actions resource, using the designated user’s identity when necessary. Restore the Session on refresh instead of resubmitting the ticket creation request.

Follow the [Offer a conversational business assistant inside a product](/v2/en/service/cases/business-assistant) walkthrough.

## Run scheduled research and batch work

A daily job summarizes changes in sources for account A-101, keeping verifiable facts separate from sales hypotheses. A scheduler initiates the work without relying on an online browser session. Track submitted, active and retryable objects, then reconcile results through callbacks or polling. Fetch the final Turn state before writing back, and deduplicate callbacks. Platform Automation can organize internal work, while an external scheduler remains responsible for its own batch and business compensation rules.

Follow the [Run scheduled research and batch work](/v2/en/service/cases/scheduled-research) walkthrough.

## Expose a specialist Agent to another Agent

A procurement assistant delegates evidence verification for supplier V-101 to a specialist Agent, then uses the findings in its own work. Several applications can reuse the specialist while the calling application supplies the tool wrapper. Persist the mapping from the parent tool call to the Session and Turn. Retry the same tool call with its original key and saved task record. When the parent is cancelled, the adapter cancels the downstream work and confirms its outcome. Cross-application cancellation and cumulative budgets require explicit adapter behavior.

Follow the [Expose a specialist Agent to another Agent](/v2/en/service/cases/agent-as-tool) walkthrough.

## Choose an initial scenario

Start with work whose data is available, tools are connected and delivery can be checked. Verify submission, execution, retrieval and review with one Managed Agent, then add collaboration, defined processes or scheduling when needed. Retain input versions, Sessions, Turns, sources and actual outputs while checking quality, latency, cost and recovery.

Public material such as [Notion’s Agent workflows](https://claude.com/customers/notion-qa) and [Claude Managed Agents documentation](https://platform.claude.com/docs/en/managed-agents/overview) can inform product design. These references do not imply that those companies use AgentScope Service or that the business integrations above are prebuilt.
