---
title: "Generate browser action proposals"
zh_link: /dev/zh/docs/harness/jev/guides/browser-proposals
---

`JevBrowserPlanner` recommends a next step from actions your driver already observed and checks whether the page version still matches. Use it when you own the browser driver and want only semantic selection.

## Example: a refund policy page refreshes during judgment

A user needs the refund deadline. Your driver can see the policy section and JEV can recommend reading it, but a refresh can change a candidate's location or meaning during judgment.

Assign `pageVersion` to the observed snapshot and check it before and after selection. If it changes, the planner returns `STALE_PAGE`. Observe again rather than executing the old proposal.

## 1. Convert visible targets into candidates

```java
import io.agentscope.extensions.judge.jev.application.JevBrowserPlanner;
import java.util.List;

var actions = List.of(new JevBrowserPlanner.Action(
    "policy", JevBrowserPlanner.Operation.READ, "Currently visible refund terms"));
String pageVersion = "page-v1";
```

Candidates must come from actual observation. This interface supports only READ, SCROLL, WAIT, DONE and BLOCKED; it does not click, type or submit.

## 2. Recheck the version after selection

```java
var planner = new JevBrowserPlanner(selector);
var pending = planner.propose(ctx, "Read the refund deadline",
    pageVersion, actions,
    () -> browser.currentPageVersion(), false);
```

Configure `selector` as in [Candidate classification](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). `browser.currentPageVersion()` stands for your driver's API, not a framework method. The final `false` means the host has not verified completion; even a recommendation of DONE cannot establish success.

## 3. Decide whether to execute

```java
var observed = pending.doOnNext(proposal -> {
    System.out.println("Proposal status: " + proposal.reason());
    System.out.println("Still valid: " +
        proposal.validFor(browser.currentPageVersion()));
});
```

`validFor` means only that an action exists and the page version matches. Observe only in SHADOW. When adopting a proposal, bind the version check and execution in the same driver operation to avoid a race.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios browser
```

The offline example recommends `policy` on the current page, returns STALE_PAGE after a page change, and COMPLETION_NOT_VERIFIED without independent evidence. It starts no browser and executes no action, so it cannot measure browser task success. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Abstention and full execution

No selection, uncertain candidates or judgment failure yield ABSTAIN. Duplicate action IDs and an empty page version are invalid arguments. The selector supplies the budget and mode; cancellation stops the subscription.

To have AgentScope perform read-only navigation, record actual actions and independently verify outcomes, use the [browser execution tool](/dev/en/docs/harness/jev/guides/browser-execution-api).
