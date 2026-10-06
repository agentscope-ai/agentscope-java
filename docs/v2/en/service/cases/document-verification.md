---
title: "Document verification: add a quality-check step"
description: "Compare sources, extracted fields, and rules through a Job that returns evidence-backed findings."
zh_link: /v2/zh/service/cases/document-verification
---

Keep the existing OCR and extraction pipeline. An Agent service checks consistency between source material, extracted values, and rules, then routes findings to a reviewer. The entire pipeline does not need to become agentic.

## 1. Prepare a known inconsistency

Download the [request fixture](/examples/service/document-verification/input.json.txt) as `input.json`. Fictional invoice DOC-101 / doc-v1 contains one inline text page:

| Object | Value |
| --- | --- |
| Source page 1 | Quantity 4, unit price 32.00 USD, total 128.00 USD |
| Extracted values | Quantity 4, unit price 32, total **182** |
| sop-v1 | Total equals quantity times unit price; values match source; unreadable pages are not verified |

Expected behavior is a total-field finding, expected value 128, referencing page 1. No PDF upload is required for this fixture. Production PDF access, large files, and page location require controlled file tools or the appropriate native file mechanism. A URL inside Job input does not automatically download a file.

## 2. Publish the verifier

Start with one Agent instructed to cover all supplied pages, distinguish contradictions from unverifiable content, and retain original values and evidence. Use deterministic tools for arithmetic; the Agent explains findings in context.

[Publish](/v2/en/service/endpoints) the job Endpoint `document-check`, accepting request, document_id, source_version, pages, extracted, and rules. Structured delivery requires the execution adapter to return a business object that can be mapped. JSON text inside a reply does not guarantee an equivalent public result object. Inspect the real raw result before configuring resultMapping/outputSchema.

This is an **expected business result shape**, not an execution record or built-in platform schema:

```json
{
  "document_id": "DOC-101",
  "verified_pages": [1],
  "findings": [
    {"field": "total", "observed": 182, "expected": 128,
     "source_page": 1, "rule_version": "sop-v1"}
  ],
  "review_required": true
}
```

## 3. Call it from the pipeline

Prepare Bash, curl, jq, BASE_URL, and a backend ENDPOINT_KEY with invoke/read after publishing the Endpoint.

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/document-check/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: doc-101-doc-v1-sop-v1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

Record document version, extraction batch, rule version, and Invocation in the upstream check record. Other documents can continue processing while this document remains awaiting review.

## 4. Decide using evidence

Wait for a terminal Invocation, read `invocation.result`, and download a report Artifact if produced. Validate structure before showing findings alongside source locations. A successful check with nonempty findings is a normal detection result, not an infrastructure failure.

After a reviewer corrects the total to 128, the business system creates a new extraction version and submits another Job. Keep both results; do not overwrite the original invoice.

## 5. Test counterexamples

| Change | Expected behavior |
| --- | --- |
| Original fixture | Report 182 versus 128 and reference page 1 |
| Correct total to 128 | Remove that finding rather than copy previous output |
| Remove source text or deny file access | State unverified scope; do not report all pages verified |
| Change rules | New invocation records the version; conflicting idempotent replay is rejected |
| Output schema mismatch | Public invocation fails; pipeline must not consume it as valid structured output |
| Reviewer disagreement | Retain evidence and review state without inventing approval |

This fixture covers one contradiction. Production acceptance needs labeled samples, missed/false finding rates, unverifiable coverage, expert review time, and real file authorization and large-document tests.
