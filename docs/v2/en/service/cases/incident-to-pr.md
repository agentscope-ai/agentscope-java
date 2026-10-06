---
title: "Incident repair: from alert to reviewable PR"
description: "Submit diagnosis and repository context as a Job, then verify commits, tests, and review."
zh_link: /v2/zh/service/cases/incident-to-pr
---

The engineering product has diagnosed an order query that ignores filtering and pagination. A user requests a fix. The product submits diagnosis, repository, and target commit to an Agent service and retrieves a PR with test evidence. Existing code review decides whether to merge. Start with one repair Job rather than requiring a complete engineering Team.

## 1. Prepare a reproducible defect

Place these files in an exercise repository, removing the final `.txt` suffix:

| File | Destination |
| --- | --- |
| [OrderQuery.java](/examples/service/incident-to-pr/OrderQuery.java.txt) | Repository root |
| [OrderQueryTest.java](/examples/service/incident-to-pr/OrderQueryTest.java.txt) | Repository root |
| [CI workflow](/examples/service/incident-to-pr/ci.yml.txt) | .github/workflows/ci.yml |
| [Job request](/examples/service/incident-to-pr/input.json.txt) | Local input.json, with no credentials |

```bash
mkdir -p out
javac --release 17 -d out OrderQuery.java OrderQueryTest.java
java -cp out OrderQueryTest
```

The initial implementation intentionally fails three of five checks. Keep the tests and commit this baseline. Replace repository and base_commit in input.json with the real exercise repository and commit. The fixture provides a query function, not a production order server.

## 2. Define the repair service

Prepare execution with repository access, code editing, Java 17 tests, and PR creation. Use a Managed environment or a Hosted Coding Agent; verify the provider, work directory, and tool identity first. Passing a repository URL grants no access by itself.

Instructions constrain scope to filtering and pagination: filter first, preserve order and input, page starts at 1, size is 1..100, and an out-of-range page returns empty. Preserve existing tests and add blank status, no matches, size bounds, and maximum integer page cases. Deliver a PR, head SHA, test commands, exit codes, and logs. Do not merge or deploy.

[Publish a job Endpoint](/v2/en/service/endpoints) named `incident-repair`, accepting all fixture input fields. Prepare Bash, curl, jq, BASE_URL, and an ENDPOINT_KEY with invoke/read.

## 3. Submit one repair iteration

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/incident-repair/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-204-repair-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

Associate incident_id, repository, base_commit, iteration, and Invocation in the engineering product. Group duplicate alerts before deciding to start a new iteration; network retries reuse the original key. This guide does not configure your GitHub Webhook or create a real PR for you.

Use [snapshots and events](/v2/en/service/service-api) for progress. Authorized people answer actual pending actions; permission to create a PR does not imply permission to merge.

## 4. Return delivery to the product

With a public HTTPS receiver and webhooks:write scope, subscribe to completion and failure events using the [Webhook protocol](/v2/en/service/service-api#credentials-budgets-and-notifications). Verify signatures and deduplicate. A quick task may finish before registration, so also query status after subscribing; callbacks are prompts to refresh state.

Read final results and Artifacts, then verify repository, head SHA, test logs, and current CI. Output fields belong to the service contract; a returned PR URL is not proof that Service validated the PR.

## 5. Accept or revise

| Check | Evidence |
| --- | --- |
| Scope | Diff covers the requested behavior and necessary tests |
| Fixed baseline | Three initial failures; all original five checks pass after repair |
| Boundaries | Added checks actually run, with commands, exit codes, and logs |
| Commit consistency | Evidence matches delivered head SHA; latest CI checked before merge |
| Review | Authorized repository reviewers decide; Invocation completed is not approval |

A revision after completion uses a new Job/key with the original PR and latest commit, linked by the application. Cancellation must reach a confirmed terminal state; it does not automatically revert pushed commits or close a PR.

The local fixture checker validates the failing baseline and reference repair. Real models, providers, GitHub, CI, and review still require execution in your environment.
