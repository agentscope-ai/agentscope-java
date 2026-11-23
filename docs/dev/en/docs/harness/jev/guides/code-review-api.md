---
title: "Review changes and locate code evidence"
zh_link: /dev/zh/docs/harness/jev/guides/code-review-api
---

`JevCodeReviewer` reviews a diff or source snapshot in stages: screen risks, select evidence regions, then assess mechanism and severity. Use it before submission, during review or to inspect a coding Agent's output. Inputs can be in any language. Findings support review; they cannot replace compilation, tests or merge authorization.

## Example: a change removes an authorization check

A change used to check `authorized` before returning a record and now returns it directly. It may still compile, and existing tests might not cover unauthorized reads. A useful review identifies the possible mechanism and points the developer to evidence in this change.

The offline example uses a Python diff. The host supplies an authorized, consistent snapshot. JEV screens for risk, then selects from actual parsed regions. It cannot invent paths or line numbers or submit a review.

## 1. Construct a snapshot and start a review

Initialize `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client); `context` is the current runtime context:

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import java.time.Duration;
import java.util.List;

var reviewer = new JevCodeReviewer(
    client::systemOne,
    JevCodeReviewer.Config.referencePolicy(),
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(10), "review-v1",
        (current, record) -> System.out.println(record)));
var input = new JevReviewInput(
    "demo-revision", JevReviewInput.Mode.CHANGES,
    List.of(new JevReviewInput.File(
        "src/auth.py",
        "@@ -1,2 +1,1 @@\n"
            + "-if not authorized: raise Forbidden()\n"
            + "-return record\n"
            + "+return record",
        List.of())),
    List.of());
var decision = reviewer.review(context, input).block();
System.out.println(decision);
```

`CHANGES` takes a unified diff per file; `CODEBASE` takes complete source files. No tests are supplied here, which says nothing about tests elsewhere. In a real integration supply relevant tests from the same snapshot through `TestEvidence` and reference them from the file records.

`referencePolicy()` supplies example, uncalibrated thresholds and limits. Ten seconds bounds the entire review. Only OFF and SHADOW are supported; there is no ENFORCE mode for approving or blocking merges. Pass a `Supplier<Mono<JevReviewInput>>` to include snapshot loading in the budget.

In CHANGES each File.text is one file's unified diff; in CODEBASE it is the complete current file. Relative paths identify evidence. Absolute paths, backslashes, `..`, control characters and duplicates are rejected. Referenced tests must exist in the input; constructors copy collections. These checks do not replace ACLs. Use a revision identifying the entire snapshot, including uncommitted changes; the host keeps source, diffs and tests consistent.

## 2. Read findings, evidence and incomplete work

Check whether removing the authorization condition could permit unauthorized reads. A Finding requires support for the mechanism, region and severity. Even with no findings, inspect incomplete items before drawing conclusions.

Screening dimensions are correctness, security, reliability, compatibility and testGap. A testGap concerns supplied evidence, not the absence of tests across the project. Taking the maximum across CODEBASE chunks helps surface possible risks; it is not a calibrated file-level probability.

Risk signals record path, dimension and probability. Only signals at or above screenThreshold enter bounded follow-up; those between lowRiskThreshold and screenThreshold remain uncertain. File profiles and review priority are independent of whether an issue is substantiated. Even low-risk files can appear among the top five profiles.

FollowUp distinguishes LOCATED, NO_MATCH, NO_ISSUE, LOW_CONFIDENCE, DEFERRED and ERROR. No matching evidence or no supported issue differs from a failed call; neither warrants inventing a finding. A Finding includes the actual OLD/NEW region, line range, location confidence, mechanism, severity, optional role and recommendation. The range locates evidence, not necessarily an exact defective line. Request a role only at severity 1.5 or above. Severity 2 or higher recommends REQUEST_CHANGES_REVIEW; otherwise COMMENT. Neither publishes a review nor changes merge state.

If role assessment fails or is uncertain, retain evidence and severity, leave owner empty and mark the report incomplete. Uncertain severity or mechanism produces no Finding. JevClient rejects missing questions, wrong types and invalid probabilities or distributions.

`Report` retains the matrix, profiles, followUps, unscreenedFiles, unprofiledFiles, complete and per-request calls. Complete means the configured review finished with no identified gaps. maxProfiles still limits profiles, and unprofiled counts are reported separately. Neither complete nor DECIDED means defect-free or tests passed. Empty input is SKIPPED; limited evidence, outstanding signals or uncertainty yield INCONCLUSIVE; total screening failure or a stage exception may yield ERROR. Available partial results are retained where possible.

## 3. Let an Agent request review explicitly

Register a read-only tool and supply a snapshot Source per invocation. The example grants only the specified demo user and session access to the fixed snapshot. Replace it with your authorized snapshot store.

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

var toolkit = new Toolkit();
toolkit.registerTool(new JevCodeReviewTool(reviewer));
var agent = HarnessAgent.builder().name("code-review-assistant")
    .model(model).toolkit(toolkit).build();
var runContext = RuntimeContext.builder()
    .userId("demo-user").sessionId("demo-session")
    .put(JevCodeReviewTool.Source.class, new JevCodeReviewTool.Source((current, snapshotId) -> {
        if (!"demo-user".equals(current.getUserId())
                || !"demo-session".equals(current.getSessionId())
                || !"demo-revision".equals(snapshotId)) {
            return Mono.error(new IllegalArgumentException("Snapshot unavailable"));
        }
        return Mono.just(input);
    }))
    .build();
```

Pass `runContext` when invoking the Agent. The model supplies only `snapshotId`; never treat it as a filesystem path. Missing Source, user or session produces an error rather than automatic repository discovery. Source code and test text are material to review, not authorization to execute commands.

Register the tool before construction so Harness visibility and filtering apply. SHADOW produces recommendations for an Agent or human without carrying them out. Registration adds a schema; invocation adds a tool call/result pair, unlike passive middleware that leaves events unchanged. PermissionEngine and tool filters still apply; readOnly does not bypass authorization.

## Budgets, cancellation and errors

Config.referencePolicy allows 50 files, 100,000 characters per file and per request state, 128 evidence regions per file, 5 profiles, 8 follow-ups and 64 model requests. Exceeding the file count prevents review. Oversized files, binary/combined diffs, invalid hunks and empty regions are explicitly skipped, never passed. Truncated test evidence or more than four associated tests marks context as limited. Other source code is not silently truncated.

All stages and evidence acquisition share the JevExecution budget. Execution is sequential by default. The entire review or Agent is never automatically retried; JevClient controls retries for individual requests. Exhausted request quota leaves DEFERRED work. Timeout returns TIMEOUT with partial results. Cancellation stops loading, in-flight requests and later stages, but cannot guarantee remote billing stops.

OFF neither loads evidence nor calls a model. Explicitly select SHADOW; construction rejects ENFORCE. Observer RuntimeExceptions are isolated. Each Call records stage, backend version, duration, status and known usage. Unknown usage stays null. An invalid text response retains parseable usage; failures before a parseable response may have unknown usage.

Tool results contain evidence regions: control storage and display of Agent messages and reports accordingly. Inspect per-request usage in the partial Report when the total budget is exhausted.

## Runnable example

[JevCodeReviewExample](/examples/jev/source/JevCodeReviewExample.java.txt) supplies the diff removing the authorization check and invokes the read-only review tool through a real Agent. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevCodeReviewExample
```

By default the example neither reads your repository nor uses credentials. Scripted generation and review responses exercise snapshot loading, staged review and tool result delivery. They do not measure vulnerability detection rates. Run tests and inspect the evidence before changing or merging real code.
