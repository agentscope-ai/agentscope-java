---
title: "Use cases"
description: Design Agent as a Service applications around business entry points, API calls, deliverables, and acceptance.
zh_link: /v2/zh/service/usecases
---

<Note>
These are preview docs. The integration designs below use the current APIs. Industry examples illustrate usage patterns; they do not imply that those companies use AgentScope Service or that the business integrations are bundled.
</Note>

Agent as a Service integration usually starts with a business action: prepare a proposal, investigate an exception, check documents, answer a customer, or research a set of accounts. An application submits work to an Endpoint, follows and interacts with an Invocation, and returns the result to its business process.

Choose **the trigger, inputs, and acceptance criteria** before deciding whether execution needs a single Agent, a Team, or a Workflow.

## Choose an integration pattern

| Scenario | User entry point | API pattern | Main deliverable |
| --- | --- | --- | --- |
| [Generate proposals and files inside an application](#generate-proposals-and-files-inside-an-application) | An action in a CRM, task board, or document page | Job + snapshot / SSE | Reports, proposals, files, sources |
| [Investigate incidents and repair code](#investigate-incidents-and-repair-code) | An alert, Issue, or “generate a fix” button | Job + Webhook | Diagnosis, PR, test evidence |
| [Verify documents and check process quality](#verify-documents-and-check-process-quality) | A validation step in a document pipeline | Job + structured result | Findings, evidence locations, review report |
| [Provide an interactive business assistant](#provide-an-interactive-business-assistant) | A conversation inside a product | Conversation + an Invocation per turn | Replies, query results, pending questions |
| [Run recurring research and background batches](#run-recurring-research-and-background-batches) | A schedule or business event | Independent Jobs | A result or exception for each object |
| [Offer a specialist service to other Agents](#offer-a-specialist-service-to-other-agents) | A parent Agent's tool or process step | Endpoint API, optionally wrapped as a tool | A specialist result with a contract |

## A shared integration path

Follow [Publish an Endpoint](/v2/en/service/endpoints) to prepare an execution target, input/output contract, Release, and Application credential. The example slug `proposal` must already be published with a schema accepting `request`.

```bash
curl "$BASE_URL/invoke/v1/endpoints/proposal/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: opportunity-104-proposal-v1' \
  -H 'X-Correlation-ID: opportunity-104' \
  -d '{
    "title": "Prepare a customer proposal draft",
    "input": {
      "request": "Use authorized customer requirements and product sources to prepare a proposal, source list, and open questions for sales review."
    }
  }'
```

Save the returned Invocation ID and associate it with your opportunity, order, or ticket. Continue through the common interfaces:

| Stage | Application behavior |
| --- | --- |
| Accept work | Display HTTP 202 as accepted; retry network failures with the same idempotency key and request |
| Show progress | Read the Invocation and snapshot, then continue events from its `as_of`; do not resubmit on refresh |
| Involve a person | Read required actions and let authorized people respond; expose inputs and cancellation according to capabilities |
| Retrieve delivery | Read `invocation.status/result`, list and download actual Artifacts; backend systems can also receive Webhooks |
| Use the result | Check results and evidence, apply business review, then update the business system |

See [Unified service API](/v2/en/service/service-api) for authentication, command bodies, event recovery, Webhook signatures, and SDK examples. Submission idempotency does not automatically make external effects such as email, order changes, or code commits happen exactly once.

## Generate proposals and files inside an application

**Goal:** A user selects “generate proposal” on a CRM opportunity, leaves while it runs, and returns to a reviewable proposal with sources. Marketing briefs, research reports, and presentations follow the same pattern.

**Industry reference:** Notion embeds managed Agents in task boards and document workflows, with pages, Slack messages, and schedules triggering work whose deliverables return to the product. [Notion interview](https://claude.com/customers/notion-qa)

**Integration design:**

1. Collect opportunity requirements, authorized knowledge, and delivery criteria, then submit a Job. Retrieve sensitive material through controlled tools; a path in the prompt does not make a file accessible.
2. Start with one Agent. Add a Team for proposal writing, implementation planning, and review when specialized delegation is useful. Internal coordination can change behind the published interface.
3. Show progress, questions, files, and sources on the original opportunity page. Associate the Invocation with the opportunity version so an old proposal cannot overwrite newer requirements.
4. A reviewer checks claims and sources before sending anything. Execution completion does not authorize customer commitments.

**Acceptance:** Files can actually be downloaded, important claims identify sources and versions, missing information is explicit, and formal delivery follows review. Failed source access must appear as a failure or missing prerequisite.

Detailed case: [Generate a CRM proposal](/v2/en/service/cases/in-product-delivery), with fixed sources, a Job request, view recovery, and file acceptance.

## Investigate incidents and repair code

**Goal:** A monitoring or engineering product submits incident context and receives a reviewable repair PR. Users follow the work in the original ticket.

**Industry reference:** Sentry's Seer performs root cause analysis, then hands off to a managed Agent that plans changes, implements them, and creates a PR. [Sentry case study](https://claude.com/customers/sentry)

**Integration design:**

1. An alert receiver groups related events and submits a Job with the repository, target commit, diagnostic evidence, and repair scope. Build the idempotency key from the business event and repair iteration.
2. Prepare repository access, build tools, and test capabilities in the execution environment. Use a Managed Agent or reuse a Hosted Coding Agent.
3. Use a Workflow when implementation, testing, and human gates need a fixed order. Show progress with SSE and use Webhooks to update PR and test links in the ticket.
4. Handle new evidence and rework according to task state. A revision after a terminal Invocation requires a new task linked by the application. Merging and deployment follow repository rules.

**Acceptance:** The PR targets the intended repository and commit, test evidence is real, and failures and untested areas remain visible. An Agent's claim that tests passed, or a returned PR URL, cannot replace CI and code review.

Detailed case: [From alert to reviewable PR](/v2/en/service/cases/incident-to-pr), with a reproducible Java defect, submission contract, PR, and test evidence.

## Verify documents and check process quality

**Goal:** Add verification after an existing OCR, extraction, or generation pipeline. Compare source material and rules to identify omissions, contradictions, and supporting evidence for expert review.

**Industry reference:** Wisedocs uses Managed Agents for document verification while retaining its specialized pipeline, combining original PDFs, extraction results, and SOPs. [Wisedocs engineering article](https://www.wisedocs.ai/blogs/building-managed-agents-for-document-verification)

**Integration design:**

1. Prepare controlled references to original documents, existing results, rule versions, and verification scope. Public Jobs currently accept JSON; large files require business file tools or the appropriate runtime file mechanism.
2. Submit a Job per document or case. Design business fields such as `findings`, `source_refs`, and `review_required`, and configure the Endpoint's output schema and result mapping. These names are not reserved platform fields.
3. The Agent reads evidence and produces findings and a report. Keep deterministic checks in code and route ambiguous findings to a reviewer.
4. Store the verification result upstream. Only documents meeting business acceptance rules move to the next stage.

**Acceptance:** Every finding identifies its source; unreadable pages cannot count as checked. Evaluate missed findings and false positives on labeled samples. Schema validation checks structure, not factual correctness.

Detailed case: [Document verification](/v2/en/service/cases/document-verification), comparing fixed source text, an incorrect extracted value, and rules to test findings and review.

## Provide an interactive business assistant

**Goal:** A user asks follow-up questions, adds constraints, queries business data, and hands off to a person or confirms an operation when necessary. Examples include sales consultation, support, and internal assistants.

**Industry reference:** Anthropic's Buying Agent handles sales inquiries and transfers work to people when appropriate, illustrating an interactive business entry point. [Buying Agent practice](https://claude.com/blog/how-anthropics-sales-team-rebuilt-inbound-with-claude-managed-agents)

**Integration design:**

1. Publish a conversation-mode single-Agent Endpoint. Create a Conversation and keep its association with the business user. Teams and Workflows currently use Job mode.
2. Each turn produces a new Invocation in the same Conversation, preserving context. Only one Invocation can be active in a Conversation at a time.
3. Restore the conversation and pending interactions with snapshots and SSE. Offer only controls supported by current capabilities; designated-approver rules still apply.
4. For an independent long task, the application can open a separate Job and display its state. Integrate human handoff with the existing support or ticketing system.

**Acceptance:** Refresh and reconnect do not submit duplicate turns; users cannot read another user's conversation; approvals and cancellation reach the appropriate final state. Keep credentials in the backend and authorize each conversation access. Multiple keys in one Application still represent the same application identity and do not isolate end users.

Detailed case: [Interactive business assistant](/v2/en/service/cases/business-assistant), covering user/order permissions, first and subsequent turns, and pending actions.

## Run recurring research and background batches

**Goal:** Research a set of accounts daily, inspect services, or process new objects in a queue. Users mainly consume completed results and exceptions.

**Industry reference:** The OpenAI Agents API announcement describes Nash's long-running logistics workflows and Dwelly's testing of asynchronous batches. These are different evidence stages; testing is not proof of production scale. [OpenAI announcement](https://openai.com/index/introducing-the-agents-api/)

**Integration design:**

1. An external scheduler or event consumer enumerates objects and submits a Job for each. For example, account ID, research date, and policy version form an idempotency key.
2. Track the business batch and its Invocations, limit concurrency, and back off when admission rejects a request. Keep explicit retry records for failed objects.
3. Collect results through queries or Webhooks, check source freshness, and update business systems. Verify Webhook signatures and deduplicate by event ID.
4. Notify users of results that need decisions. The scheduler owns recurrence and batch state; Service owns each invocation's execution.

Platform [Automation](/v2/en/service/automation) can create Issues, add comments, and start or signal Workflows. It does not currently provide a direct trigger for a published Endpoint. Use a scheduler calling the public API when the work must follow the Application, Release, and Invocation path.

**Acceptance:** Duplicate events do not create duplicate logical work, batches can be reconciled, and one failure does not hide other results. Application token budgets use reported consumption; reconcile actual costs separately rather than treating the budget as an exact prepaid billing cap.

Detailed case: [Recurring research per object](/v2/en/service/cases/scheduled-research), with fixed sources, a batch ledger, retry idempotency, notifications, and reconciliation.

## Offer a specialist service to other Agents

**Goal:** A parent Agent interprets a request and calls a specialist for supplier research, document verification, or order investigation, receiving structured findings and evidence.

**Industry reference:** Pendo describes MCP-initiated work that creates a managed Agent in the background, showing that another Agent can be the caller. [Pendo Novus case study](https://claude.com/customers/pendo-qa)

**Integration design:**

1. Publish the specialist as an independent Endpoint with scope, input/output schemas, a timeout, and usage limits.
2. The parent Agent's tool adapter submits a Job and retains its Invocation ID. For long work, return a task handle and offer query, interaction, or cancellation operations instead of holding a synchronous tool request open.
3. Return the result and sources to the parent Agent so it can continue its own process.
4. If MCP is needed, provide a corresponding MCP tool wrapper in the application. Service's collaboration MCP supports internal task coordination; it does not mean every Endpoint is automatically exported as a public MCP tool.

**Acceptance:** The caller handles waiting, failure, partial success, and cancellation. Nested calls have explicit budget and depth limits. End-user authorization travels through a controlled tool chain; a parent Agent's prompt is not a downstream access grant.

Detailed case: [A specialist service called by another Agent](/v2/en/service/cases/agent-as-tool), with domain input, asynchronous tool contracts, task handles, and control propagation.

## Choose your first integration

Start with one task whose inputs are accessible, tools are connected, and delivery can be checked: a proposal draft, a read-only investigation, or a document quality check. Validate submit–execute–retrieve–review with a single Agent before adding collaboration, fixed workflows, or automation.

Six detailed cases provide fixed inputs, API calls, application integration steps, and acceptance criteria:

| Detailed case | Verifiable delivery | Application integration |
| --- | --- | --- |
| [CRM proposals and files](/v2/en/service/cases/in-product-delivery) | Proposal, open questions, source versions | Opportunity permissions, UI, review, sending |
| [Alert to PR](/v2/en/service/cases/incident-to-pr) | Repair, commits, test evidence | Alert grouping, repository, CI, review |
| [Document verification](/v2/en/service/cases/document-verification) | Field contradictions, source locations, review state | Sources, extraction pipeline, domain rules |
| [Interactive assistant](/v2/en/service/cases/business-assistant) | Follow-up queries, confirmation, real tickets | User identity, tools, chat UI |
| [Recurring research](/v2/en/service/cases/scheduled-research) | Per-object results and batch reconciliation | Scheduler, sources, writeback, notifications |
| [Specialist Agent tool](/v2/en/service/cases/agent-as-tool) | Task handle, findings, evidence | Parent adapter, permissions, cancellation propagation |

Business fixtures are fictional. Expected outputs and industry examples are not AgentScope execution records. Retain input versions, Releases, Invocations, sources, and actual artifacts for production acceptance; test quality, cost, latency, authorization, and interruption recovery.
