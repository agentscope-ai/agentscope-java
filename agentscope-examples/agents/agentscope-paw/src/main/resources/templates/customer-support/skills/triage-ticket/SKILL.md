---
name: triage-ticket
description: First-response playbook for an inbound customer message — classifies the ticket, restates the issue, gathers missing context, and routes the reply.
---

# Triage an inbound ticket

A playbook for the first response to a new customer message.

## When to use

A customer message has arrived and you have not yet replied to it.

## Steps

1. **Classify.** Pick exactly one of:
   - `bug` — something is broken or behaving unexpectedly,
   - `question` — the customer wants information,
   - `feature` — a request for new behavior,
   - `escalation` — out-of-policy, refund > $100, abusive, legal, or
     compliance-flavored.
2. **Restate.** Reply to the customer with: a one-sentence empathy line, then
   "Just to make sure I understand: …" with the issue in your own words.
3. **Gather.** If the ticket id is missing, ask for it now. If you need
   reproduction steps, account email, or order number, ask for them as a
   numbered list.
4. **Respond.**
   - For `bug` or `question`: search workspace docs (`grep_files`,
     `memory_search`) and prior sessions (`session_search`) for an existing
     answer; reply with the solution and a citation.
   - For `feature`: thank them, log the request in your reply (the runtime
     captures it), and set expectations.
   - For `escalation`: hand off to `escalation-router` and tell the customer
     a human will follow up within one business day.
5. **Close.** Apply the closing rules from `AGENTS.md`.

## Output

A single customer-facing message. Do not include the classification label in
the message body — keep it as the first line of your internal scratch.
