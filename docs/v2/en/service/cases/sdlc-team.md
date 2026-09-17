---
title: "Engineering: from GitHub Issue to merged PR"
description: "Use an all-Hosted Team for analysis, implementation, review, CI, rework, and approval."
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Have an all-Hosted Team add status filtering and pagination to order queries. The Leader, Developer, Reviewer, and QA run on Runtime Hosts and carry a GitHub Issue through implementation, PR creation, review, CI, rework, approval, and merge. Roles may use the same provider or different supported providers.

## Goal and prerequisites

Prepare Service, working Runtime Hosts and authenticated providers, Git, GitHub CLI, and JDK 17+. Use an exercise repository with developer and independent reviewer identities. A repository administrator configures required checks, branch rules, and merge permissions. Hosted identity and GitHub identity are separate; creating Agent roles does not create GitHub accounts.

Download these files, remove the final `.txt`, and place them in the exercise repository:

| Download | Destination and purpose |
| --- | --- |
| [OrderQuery.java](/examples/service/sdlc-team/OrderQuery.java.txt) | Repository root; query implementation |
| [OrderQueryTest.java](/examples/service/sdlc-team/OrderQueryTest.java.txt) | Repository root; fixed acceptance checks |
| [Issue requirements](/examples/service/sdlc-team/issue.md.txt) | GitHub Issue body |
| [CI workflow](/examples/service/sdlc-team/ci.yml.txt) | `.github/workflows/ci.yml` |

The fixture implements the query logic behind an order API, not an HTTP server. Filtering and pagination are intentionally absent: three of five checks fail initially. The Issue also requires additional boundary tests. Ignore `out/`, commit the fixture and CI, and create the Issue. Initial CI failure represents the unimplemented feature.

```bash
mkdir -p out
javac --release 17 -d out OrderQuery.java OrderQueryTest.java
java -cp out OrderQueryTest
```

The baseline and a reference repair were checked locally. Execute the GitHub and Team steps below in your configured environment.

## 1. Create four Hosted roles

Follow [Hosted setup](/v2/en/service/connect-hosted-agent), then verify each Agent's task and collaboration capabilities. The Developer needs push/PR access, Reviewer needs review access, and QA needs Actions status/log access. Merge uses an authorized identity. Isolate GitHub identities using Host users, execution environments, or tool credentials rather than concurrently switching a shared CLI login.

| Role | Instructions | Deliverable |
| --- | --- | --- |
| Leader | Analyze, delegate, follow rework, and explicitly complete coordination after obligations finish | Acceptance checklist, task graph, delivery index |
| Developer | Preserve supplied checks, implement and add boundary tests; leave final review to the Reviewer | Branch, commit, PR, local logs |
| Reviewer | Inspect actual changes, correctness, and tests for a specific commit | Commit-associated review and follow-up findings |
| QA | Test the same commit independently and inspect CI | Commands, exit codes, CI links, failure evidence |

Under **DESIGN → Teams**, select the Hosted Leader and add the other three members. Use team instructions such as:

```text
Deliver the supplied GitHub Issue. The Leader establishes criteria and delegates implementation.
After PR creation, Reviewer and QA inspect the same head commit.
Explicitly arrange rework when needed; recheck affected tests and review after new commits.
Use real files, commands, reviews, and CI records as evidence, identified by commit.
Report missing access or requirements precisely. Finish the coordinator node after delivery.
```

## 2. Start from a GitHub Issue

Create a Service Issue under **WORK → Issues** with the GitHub Issue URL, `OWNER/REPO`, base branch, test command, criteria, and authorization scope: delivery through a merge-ready PR or including merge. Assign the Team and require human acceptance. The Leader reads the original GitHub Issue through its tools and retains its identity.

This minimal path does not require event bridging. If GitHub Work Source is configured, use the synchronized Service Issue. The current adapter handles Issues and comments; PR review and CI still require GitHub tools.

Set `REPO`, `ISSUE_NUMBER`, `BASE_BRANCH`, and subsequent variables to actual task values. Check identity in the task environment:

```bash
gh auth status
gh issue view "$ISSUE_NUMBER" --repo "$REPO" --json number,title,body,url,comments
```

The Leader turns filter-before-pagination, blank filters, unmatched results, large pages, invalid arguments, and input immutability into a checklist before delegation.

## 3. Implement and create a PR

The Developer clones the exercise repository into its Host task directory and creates a task-specific branch. A repository open on the administrator's computer is not automatically task input. Reviewer and QA use their own checkouts; commits share code, while Comments and Artifacts share reports.

Run baseline checks, implement the feature, add tests, and record commands, exit codes, and commits. Write requirements, changes, tests, and the Issue link into `pr-body.md`. If merge should close the Issue, include the actual `Closes #number` reference.

```bash
git push -u origin "$FEATURE_BRANCH"
gh pr create --repo "$REPO" --base "$BASE_BRANCH" --head "$FEATURE_BRANCH"   --title 'feat: filter and paginate order queries' --body-file pr-body.md
```

Save the returned URL. Check for an existing PR before retrying creation. See [GitHub CLI PR creation](https://cli.github.com/manual/gh_pr_create) for branch and body-file options.

## 4. Review, CI, and rework

Reviewer and QA first record the head SHA, then check out and inspect the PR:

```bash
gh pr view "$PR_URL" --json headRefOid,url,baseRefName
gh pr checkout "$PR_URL"
git rev-parse HEAD
gh pr diff "$PR_URL"
gh pr checks "$PR_URL" --required
```

Verify local HEAD matches the intended commit. QA runs tests there and checks CI logs and the tested revision. Include `Integer.MAX_VALUE` as a page to detect overflow in `(page - 1) * size`.

Write concrete findings and acceptance requirements into `review.md` and request changes. The Leader reads findings and explicitly dispatches follow-up work; **Service Inbox Request changes does not automatically rerun execution**.

```bash
gh pr review "$PR_URL" --request-changes --body-file review.md
```

After a revision is pushed, record the new SHA and recheck affected work. `--required` lists only configured required checks; an empty set is not evidence that tests passed. Pending, cancelled, and missing checks do not establish success. See [checks](https://cli.github.com/manual/gh_pr_checks).

## 5. Approve, merge, and accept

The independent reviewer verifies the final commit and submits GitHub approval with evidence in the report:

```bash
gh pr review "$PR_URL" --approve --body-file review.md
```

See [review options](https://cli.github.com/manual/gh_pr_review). A Workflow can wrap the Team with an approval gate, but Service tool approval and Issue acceptance do not substitute for GitHub review.

When merge is authorized and the final commit meets required CI and repository review rules, the authorized executor merges. `REVIEWED_SHA` must be the inspected commit, not a newly fetched value used to skip review:

```bash
gh pr merge "$PR_URL" --squash --match-head-commit "$REVIEWED_SHA"
```

If a merge queue applies, observe actual completion. An accepted command is not a merged PR. New commits, conflicts, or unmet rules return the work to validation; do not bypass them with administrator options. See [merge behavior](https://cli.github.com/manual/gh_pr_merge).

The Leader delivers the Issue, PR, commit, review, CI, merge status, and open items. Inspect them in [Inbox](/v2/en/service/inbox). If scope ends at merge readiness, state that administrator merge remains outstanding.

## Failure paths and event-driven extension

| Situation | Response |
| --- | --- |
| Unclear requirement | Record a precise question and continue after input |
| Failed CI | QA supplies logs and run URL; Developer fixes and reruns |
| New commit after review | Recheck that commit; retain earlier evidence as history |
| Host interruption | Inspect original Task/Attempt and remote branch before recovery |
| Push or merge conflict | Update, retest, and review without dropping others' commits |
| Missing review identity or access | Preserve output and hand it to an authorized reviewer |

First complete the loop through explicit CI queries. Then add [Automation](/v2/en/service/automation): a bridge verifies GitHub events, correlates repository/PR/commit with existing work, and deduplicates delivery IDs. A generic webhook's new task does not automatically resume the old Team; define correlation and subsequent dispatch explicitly.

Keep delivery evidence, clean exercise branches and credentials, and inspect outstanding Host tasks. Include a real rework or recovery path in acceptance, not only a successful screenshot.
