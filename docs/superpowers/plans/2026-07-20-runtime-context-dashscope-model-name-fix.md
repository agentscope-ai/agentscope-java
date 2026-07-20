# RuntimeContext DashScope Model Name Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent `RuntimeContextExample` from sending the ModelRegistry provider prefix to the DashScope API when it directly constructs `DashScopeChatModel`.

**Architecture:** Keep the provider/API model-name boundary unchanged. Correct the example's direct `DashScopeChatModel.builder().modelName(...)` argument and lock the exact source configuration with a focused JUnit test; leave `ReActAgent.Builder.model("dashscope:qwen-plus")` behavior untouched.

**Tech Stack:** Java 17, Maven, JUnit Jupiter, Spotless.

---

### Task 1: Add a failing regression test for the direct model configuration

**Files:**
- Create: `agentscope-examples/documentation/src/test/java/io/agentscope/examples/documentation2/context/RuntimeContextExampleTest.java`

- [ ] **Step 1: Write the failing test**

Create a test that reads the example source from the documentation module and asserts that the
direct builder uses the raw DashScope API model name while rejecting the registry-prefixed value:

```java
package io.agentscope.examples.documentation2.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeContextExampleTest {

    @Test
    void directDashScopeBuilderUsesApiModelName() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/io/agentscope/examples/documentation2/context/"
                                        + "RuntimeContextExample.java"));

        assertTrue(source.contains(".modelName(\"qwen-plus\")"));
        assertFalse(source.contains(".modelName(\"dashscope:qwen-plus\")"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails for the current source**

Run from the repository root with Java 17 so the local SNAPSHOT BOMs are resolved in the reactor:

```bash
JAVA17_HOME=$(/usr/libexec/java_home -v 17)
JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
  mvn -pl agentscope-examples/documentation -am \
      -Dtest=RuntimeContextExampleTest -DfailIfNoTests=false test
```

Expected: one assertion failure because the source currently contains
`.modelName("dashscope:qwen-plus")` and does not contain `.modelName("qwen-plus")`.

### Task 2: Apply the minimal example fix

**Files:**
- Modify: `agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/context/RuntimeContextExample.java:66`

- [ ] **Step 1: Replace only the direct API model name**

Change:

```java
.modelName("dashscope:qwen-plus")
```

to:

```java
.modelName("qwen-plus")
```

Do not alter the explicit formatter, streaming setting, state store, session ID, or registry-based
examples elsewhere in the repository.

- [ ] **Step 2: Run the focused regression test to verify it passes**

Run the same Java 17 Maven command from Task 1. Expected: `RuntimeContextExampleTest` passes with
zero failures.

- [ ] **Step 3: Run formatting and compile checks for the touched module**

Run from the repository root:

```bash
JAVA17_HOME=$(/usr/libexec/java_home -v 17)
JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
  mvn -pl agentscope-examples/documentation -am -DskipTests compile
```

Expected: exit code 0, Spotless reports the touched Java files clean, and the documentation module
compiles successfully.

### Task 3: Review, commit, push, and open the PR

**Files:**
- No additional files; review the source fix, regression test, design, and plan commits.

- [ ] **Step 1: Inspect the final diff and repository status**

Run:

```bash
git diff --check
git status --short
git diff --stat main...HEAD
```

Expected: only the design/plan documentation, the example model-name correction, and the focused
regression test are present; no generated files or credentials are staged.

- [ ] **Step 2: Commit the implementation**

```bash
git add \
  agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/context/RuntimeContextExample.java \
  agentscope-examples/documentation/src/test/java/io/agentscope/examples/documentation2/context/RuntimeContextExampleTest.java
git commit -m "fix(examples): use raw DashScope model name"
```

- [ ] **Step 3: Push the branch as the authenticated GitHub user**

```bash
gh auth status
git push -u origin codex/fix-runtime-context-dashscope-model
```

Expected: `gh auth status` reports the authenticated account and the branch is available on origin.

- [ ] **Step 4: Create the pull request**

```bash
gh pr create \
  --base main \
  --head codex/fix-runtime-context-dashscope-model \
  --title "fix(examples): use raw DashScope model name" \
  --body-file /tmp/runtime-context-dashscope-pr.md
```

The PR body must summarize that direct `DashScopeChatModel` construction now uses `qwen-plus`,
preserve the registry-prefixed form for `ModelRegistry`, and list the focused test and Java 17
Maven compile verification commands.

## Plan self-review

- The design's only production change is covered by Task 2.
- The source-level regression test in Task 1 asserts the exact configuration that caused the 400
  response and is run red before the correction and green afterward.
- No runtime provider normalization, persistence changes, database changes, or unrelated refactors
  are included.
- No unresolved placeholders remain.
