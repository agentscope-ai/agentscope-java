---
title: "Managed and mixed Teams: from customer needs to a proposal"
description: "Create a Managed Team for a proposal and PoC plan, then add External queries and Hosted implementation."
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A presales team needs to clarify customer requirements, verify product capabilities, design a solution, and plan a PoC. Create five Managed Agents directly in the console, bind knowledge, and form a Team. Then extend it with live capability queries and PoC implementation to combine Managed, External, and Hosted work around one deliverable.

The baseline needs no custom Agent application. It produces downloadable, sourced files for human review.

## Prepare requirements and knowledge

You need Service, a working Managed model, an Environment with the required file tools, and permission to create Agents, Teams, Memory Stores, and Issues. First verify conversation and file delivery using [Managed setup](/v2/en/service/create-managed-agent).

Download three text packs. Northstar Retail and the product catalogue are fictional exercise material, not AgentScope Service feature promises:

| Material | Use |
| --- | --- |
| [Customer brief](/examples/service/presales-team/customer-brief.txt) | Paste into the Issue and retain the `customer-v1` marker |
| [Product knowledge](/examples/service/presales-team/product-knowledge.txt) | Split at File markers into `product/capabilities.md` and `product/reference-case.md` Memory documents |
| [Delivery guide](/examples/service/presales-team/delivery-guide.txt) | Create `delivery/poc-guide.md` in Memory |

The customer has 80 stores and about 3,000 daily enquiries and wants web support plus order lookup. Residency, SSO, peak concurrency, model budget, and quality targets are unconfirmed. Daily volume does not establish peak traffic; a four-week PoC does not establish a production launch date.

## 1. Create five Managed Agents

Under **DESIGN → Agents**, create these Managed roles with a model and default Environment. Start with a tested model and adjust by role if needed.

| Role | Responsibility | Knowledge and output |
| --- | --- | --- |
| Solution Lead | Clarify, delegate, consolidate, complete coordination | All sources; proposal and delivery index |
| Requirements Analyst | Separate requirements, inference, and missing conditions | Customer input and delivery guide; requirements/questions |
| Solution Designer | Map needs to confirmed capabilities | Product knowledge; capability mapping, architecture, limits |
| Delivery Planner | Define PoC phases, dependencies, and acceptance | Product/delivery knowledge; plan and checks |
| Quality Reviewer | Check promises, evidence, and contradictions | All sources; findings and review outcome |

Bind the required Stores under **Runtime → Session defaults → Default memory stores**. Start new conversations and inspect actual `memory_store_list` / `memory_store_read` calls. A coincidentally correct answer does not verify binding. See [Memory](/v2/en/service/memory) and [Environment](/v2/en/service/environments).

Add shared instructions:

```text
Base critical judgments on the input, knowledge actually read, or real tool results.
Cite document paths and versions; mark unsupported facts as open questions.
Separate available capabilities, integrations to build, and unsupported scope.
Propose PoC metrics without claiming the customer has already agreed to them.
Deliver files actually created and uploaded; disclose inability to deliver.
```

## 2. Form the Team and verify delegation

Select the Solution Lead under **DESIGN → Teams** and add the other four members:

```text
Delegate requirements analysis first, then arrange solution design and delivery planning.
Ask Quality Reviewer to check the proposal; assign revisions for contradictions or missing sources.
Share results through persistent Comments and Artifacts, not assumed shared filesystems.
Deliver proposal.md, requirements.csv, poc-plan.md, and open-questions.md.
After necessary member work finishes, complete the coordinator node for human acceptance.
```

Validate each member independently first. Team delegation is dynamic rather than an unconditional fan-out. Use a [Workflow](/v2/en/service/workflows) when approval order must be enforced by fixed steps.

## 3. Submit customer requirements

Create “Northstar Retail support proposal and PoC” under **WORK → Issues**, assign the Team, and require human review. Paste the complete customer brief and add:

```text
Goal: prepare a proposal for discussion, without sending it externally or deploying production.
Scope: web support, knowledge answers, read-only order lookup, and human handoff.
Deliver a proposal, capability mapping, four-week PoC recommendation, and open questions.
Cite evidence, avoid unsupported promises, and identify every unconfirmed condition.
The PoC clock starts when access and sample data are ready; it is not a production-launch promise.
```

Watch Task map and Comments. Analysis should identify missing facts, design should cite knowledge, planning should state prerequisites, and review should inspect claims before the Lead consolidates them.

## 4. Inspect files and facts

| File | Acceptance |
| --- | --- |
| `proposal.md` | Goals, solution, boundaries, integrations, limits, sources |
| `requirements.csv` | Requirement, mapped capability, source, support status, open item |
| `poc-plan.md` | Four phases, prerequisites, verification methods; unagreed targets marked TBD |
| `open-questions.md` | Residency, SSO, peak load, budget, API access, quality targets |

Review must identify that this catalogue excludes voice calls and an offline mobile app. The reference case provides no production accuracy percentage or guaranteed response time. Order lookup and handoff require API integration; attaching documents does not implement it.

Download Artifacts in [Inbox](/v2/en/service/inbox), inspect content and sources, then accept or request changes. A local path in a comment is not a downloadable file. After Request changes, explicitly arrange revision execution; Agent completion and human acceptance remain separate.

Ask “Can we promise voice support?” as a boundary check. A correct answer identifies unsupported scope and questions for further evaluation.

## 5. Verify a knowledge update

Update `product/capabilities.md` to `product-v2`, adding that voice calls may be separately evaluated as a pilot and are not standard delivery. Start new work and require another knowledge read. The proposal should distinguish standard capability from a proposed pilot and cite the new version.

Previous proposals do not update automatically. Retain their inputs and source versions. Regression checks should compare facts from fixed sources rather than exact model wording.

## 6. Add External live capability queries

For changing capabilities, regions, or delivery capacity, add an AgentScope External member with read-only enterprise tools. Complete [registration and task acceptance](/v2/en/service/register-agentscope-agent) before adding it to the Team.

Return product, region, actual availability, constraints, source version, and query time. The Lead compares live evidence with Memory. Stale or conflicting sources require clarification rather than combined promises. On lookup failure, retain the static proposal and mark live availability as unconfirmed.

The External application manages enterprise credentials. If a Managed Agent calls authenticated MCP directly, configure the appropriate [Vault](/v2/en/service/vault) and verify its binding; secret text in Instructions is not tool authentication.

## 7. Add Hosted PoC implementation

After proposal review, add a Hosted PoC Agent and provide the approved scope, exercise repository, available APIs, and acceptance criteria:

```text
Implement only the approved PoC scope using demo data or explicitly supplied test APIs.
Deliver run instructions, commits or Artifacts, real test commands/results, and open items.
When an API is unavailable, label simulated data; do not claim a production integration.
```

The Hosted member codes and tests in its own task directory and returns commits and Artifacts. The Managed reviewer checks delivery against the proposal and the Lead consolidates results. Directories are not automatically shared. For PR, review, CI, and approval, follow the [engineering case](/v2/en/service/cases/sdlc-team).

## Failures and cleanup

| Situation | Response |
| --- | --- |
| No knowledge reads | Check grants and bindings; verify tools in a new conversation |
| Unsupported promises | Require sources and revision regardless of execution status |
| Member timeout or failure | Retain partial output, identify gaps, and arrange follow-up |
| Undownloadable files | Check actual Artifact upload rather than a printed path |
| Live/static source conflict | Compare versions and times, then resolve before promising |
| PoC uses simulated APIs | Preserve that scope in demonstration and acceptance |

Archive exercise work and retain reusable sources and checks. Inspect active tasks before disabling the Team. The supplied knowledge is ready for practice; model output, mixed collaboration, and file delivery require verification in your deployment.
