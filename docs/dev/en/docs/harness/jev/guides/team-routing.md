---
title: "Choose team candidates for an explicit stage"
zh_link: /dev/zh/docs/harness/jev/guides/team-routing
---

`JevTeamPlanner` recommends authorized, available members for a task stage. Use it when planning, implementation and review belong to different members. Your scheduler owns task creation, dispatch and team state.

## Example: select a reviewer to verify a patch

A refund patch is ready to review against test evidence. The team has a code reviewer, a documentation writer and another reviewer who is offline. Selecting for general programming skill could miss that this is now an acceptance review.

The host first removes unavailable or unauthorized members and supplies the explicit `review` stage. JEV evaluates remaining roles for that stage without creating subtasks.

## 1. Supply member roles and host filters

```java
import io.agentscope.extensions.judge.jev.application.JevTeamPlanner;
import java.util.List;
import java.util.Set;

var members = List.of(
    new JevTeamPlanner.Member("reviewer", "Code review and verification of test evidence"),
    new JevTeamPlanner.Member("writer", "Write user documentation"),
    new JevTeamPlanner.Member("offline-reviewer", "Code review and verification of test evidence"));
var availableAndAuthorized = Set.of("reviewer", "writer");
```

The example uses sets as a member directory. In production determine membership through authorization and health checks. Role descriptions guide semantic judgments but cannot replace access controls.

## 2. Request a recommendation for the current stage

```java
var planner = new JevTeamPlanner(selector);
var pending = planner.recommend(ctx, "review",
    "Check whether the refund patch meets acceptance criteria", members,
    member -> availableAndAuthorized.contains(member.id()));
```

Configure `selector` as in [Candidate classification](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). Supply `stage` explicitly. After filtering, unique member IDs identify results. Check for DECIDED before reading `value().selected()` and `value().uncertain()`.

In SHADOW record the suggested reviewer and preserve existing dispatch. Even when adopting a suggestion, recheck authorization and availability before starting a member.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios team
```

The example recommends `reviewer`, ignores `offline-reviewer` and creates zero tasks. This checks that host filtering precedes judgment and recommendations do not dispatch; it does not measure delivery quality. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Relationship to model routing

Team planning chooses a member for a stage; [model routing](/dev/en/docs/harness/jev/guides/model-routing-api) chooses a model for a member's invocation. For sticky model selection within stages, use the [explicit stage API](/dev/en/docs/harness/jev/guides/phase-routing-api#explicit-stages).

The planner does not infer stage changes from tool output. On failure or uncertainty, retain the original assignment or ask for clarification. No recommendation is not a successful dispatch.
