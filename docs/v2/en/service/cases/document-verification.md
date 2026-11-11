---
title: "Integrate document verification into a business process"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/document-verification
---

A document pipeline needs to compare extracted fields from invoice DOC-101 with its source. The Agent identifies discrepancies and evidence; the application decides whether processing continues or requires human review.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/document-verification/input.json.txt) as `input.json`. The sample includes source pages, extracted fields and rules. Compare quantity 4, unit price USD 32 and total USD 128 together. Report discrepancies with field names, page references and supporting evidence; unreadable content should remain pending review.

Configure a read-only verification Agent with clear source and rule versions. The example sends a small source snapshot as text. For original documents, upload a Session File and reference it in a content block. OCR, document authorization and domain rules remain part of the business pipeline.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: document-verification-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: document-verification-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

Associate the document version, extraction batch and rule version with the Session and Turn. Keep the document pending while verification runs. Validate the result structure before displaying findings alongside source references. A detected business inconsistency is a valid finding, distinct from an execution failure such as an unreadable file.

## Verify the actual delivery

Test correct, contradictory, missing-page and unreadable examples. Check both findings and their evidence. Validate the actual result structure rather than treating JSON-looking prose as success, and advance the business process after the required review.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
