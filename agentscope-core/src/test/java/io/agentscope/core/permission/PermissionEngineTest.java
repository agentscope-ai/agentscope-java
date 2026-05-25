/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.ToolContext;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.builtin.Bash;
import io.agentscope.core.tool.builtin.Edit;
import io.agentscope.core.tool.builtin.Read;
import io.agentscope.core.tool.builtin.Write;
import io.agentscope.core.tool.permission.ToolBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Behaviour spec for the {@link PermissionEngine}.
 *
 * <p>Engine-only behaviours (rule priority, mode fallbacks, default ASK/DENY) are exercised
 * directly against a {@link FakePermissionTool}. Tool-specific behaviours exercise the real
 * built-in tools ({@link Bash}, {@link Read}, {@link Write}, {@link Edit}).
 *
 * <p>Coverage targets:
 *
 * <ol>
 *   <li>Rule priority — deny &gt; ask &gt; allow
 *   <li>Modes — BYPASS / DONT_ASK / ACCEPT_EDITS / EXPLORE / DEFAULT
 *   <li>Bash rules — prefix, substring, multi-rule
 *   <li>File rules — glob, directory globs
 *   <li>Dangerous paths — dangerous files and dirs
 *   <li>Rule suggestion generation
 *   <li>Read-only detection
 *   <li>Safety checks survive BYPASS
 * </ol>
 */
class PermissionEngineTest {

    /**
     * Minimal {@link ToolBase} used by engine-only tests. {@code checkPermissions} returns
     * PASSTHROUGH by default; tests may override via {@link #withPermissionDecision}.
     */
    private static final class FakePermissionTool extends ToolBase {

        private PermissionDecision toolDecision;

        FakePermissionTool(String name, boolean readOnly) {
            super(
                    name,
                    name + " description",
                    Map.of("type", "object", "properties", Map.of()),
                    /* isReadOnly */ readOnly,
                    /* isConcurrencySafe */ true,
                    /* isMcp */ false,
                    /* mcpName */ null,
                    /* isExternalTool */ false,
                    /* isStateInjected */ false);
        }

        FakePermissionTool withPermissionDecision(PermissionDecision decision) {
            this.toolDecision = decision;
            return this;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContext context) {
            if (toolDecision != null) {
                return Mono.just(toolDecision);
            }
            return Mono.just(PermissionDecision.passthrough("no tool-specific opinion"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new UnsupportedOperationException("not executed in engine tests"));
        }
    }

    private static PermissionRule allowAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "test");
    }

    private static PermissionRule denyAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.DENY, "test");
    }

    private static PermissionRule askAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ASK, "test");
    }

    private static PermissionContext contextWithMode(PermissionMode mode) {
        return PermissionContext.builder().mode(mode).build();
    }

    private static PermissionContext contextWithWorkingDir(PermissionMode mode, String path) {
        return PermissionContext.builder()
                .mode(mode)
                .addWorkingDirectory(path, new AdditionalWorkingDirectory(path, "test"))
                .build();
    }

    private static ToolContext freshToolContext() {
        return ToolContext.builder().build();
    }

    private static PermissionRule rule(String tool, String content, PermissionBehavior behavior) {
        return new PermissionRule(tool, content, behavior, "test");
    }

    @Nested
    @DisplayName("Rule priority: deny > ask > allow")
    class RulePriority {

        @Test
        @DisplayName("Deny rule overrides allow rule on the same tool")
        void denyOverridesAllow() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("bash"));
            engine.addRule(denyAll("bash"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Ask rule overrides allow rule on the same tool")
        void askOverridesAllow() {
            FakePermissionTool tool = new FakePermissionTool("npm", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("npm"));
            engine.addRule(askAll("npm"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deny > Ask > Allow when all three are registered")
        void fullPriorityOrder() {
            FakePermissionTool tool = new FakePermissionTool("test", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("test"));
            engine.addRule(askAll("test"));
            engine.addRule(denyAll("test"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Modes: BYPASS / DONT_ASK / ACCEPT_EDITS / EXPLORE / DEFAULT")
    class Modes {

        @Test
        @DisplayName("BYPASS allows unmatched tool calls")
        void bypassAllowsByDefault() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("bypass"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deny rule wins even in BYPASS")
        void bypassRespectsDeny() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));
            engine.addRule(denyAll("bash"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Dangerous path is bypass-immune (returns ASK in BYPASS)")
        void bypassAsksOnDangerousPath() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/home/user/.bashrc", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("DONT_ASK converts default ASK into DENY")
        void dontAskDeniesUnknown() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine =
                    new PermissionEngine(contextWithMode(PermissionMode.DONT_ASK));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("dont_ask"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("ACCEPT_EDITS allows Write/Read/Edit within working dir")
        void acceptEditsAllowsInsideWorkingDir() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine =
                    new PermissionEngine(
                            contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, "/tmp/workdir"));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/tmp/workdir/foo.txt", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("ACCEPT_EDITS asks for edits outside working dir")
        void acceptEditsAsksOutsideWorkingDir() {
            Edit edit = new Edit(freshToolContext());
            PermissionEngine engine =
                    new PermissionEngine(
                            contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, "/tmp/workdir"));

            StepVerifier.create(
                            engine.checkPermission(
                                    edit,
                                    Map.of(
                                            "file_path",
                                            "/elsewhere/foo.txt",
                                            "old_string",
                                            "a",
                                            "new_string",
                                            "b")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("EXPLORE allows read-only tools")
        void exploreAllowsRead() {
            FakePermissionTool tool = new FakePermissionTool("reader", /* readOnly */ true);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.EXPLORE));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("explore"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("EXPLORE denies non-read-only tools")
        void exploreDeniesWrite() {
            FakePermissionTool tool = new FakePermissionTool("writer", /* readOnly */ false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.EXPLORE));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("explore"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Tool-supplied decisions")
    class ToolDecisions {

        @Test
        @DisplayName("Tool ALLOW short-circuits before allow rules")
        void toolAllowShortCircuits() {
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false)
                            .withPermissionDecision(PermissionDecision.allow("tool says yes"));
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertEquals("tool says yes", decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool DENY beats BYPASS")
        void toolDenyBeatsBypass() {
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false)
                            .withPermissionDecision(PermissionDecision.deny("tool blocked"));
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertEquals("tool blocked", decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool ASK with safety reason attaches suggestions and is bypass-immune")
        void toolSafetyAskBypassImmune() {
            PermissionDecision safetyAsk =
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message("safety check")
                            .decisionReason("Safety: dangerous arguments detected")
                            .build();
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false).withPermissionDecision(safetyAsk);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertEquals(1, decision.getSuggestedRules().size());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool PASSTHROUGH falls through to allow rules / mode fallback")
        void toolPassthroughFallsThrough() {
            FakePermissionTool tool = new FakePermissionTool("custom", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("custom"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Default ASK")
    class DefaultAsk {

        @Test
        @DisplayName("DEFAULT mode with no rules returns ASK with suggestions")
        void defaultAskWithSuggestions() {
            FakePermissionTool tool = new FakePermissionTool("anything", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertEquals(1, decision.getSuggestedRules().size());
                                PermissionRule suggested = decision.getSuggestedRules().get(0);
                                assertEquals("anything", suggested.toolName());
                                assertEquals(PermissionBehavior.ALLOW, suggested.behavior());
                                assertEquals("suggested", suggested.source());
                                assertNull(suggested.ruleContent());
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Engine snapshot semantics")
    class SnapshotSemantics {

        @Test
        @DisplayName("Engine rule tables are immutable views")
        void ruleTablesAreImmutable() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("bash"));

            Map<String, List<PermissionRule>> snapshot = engine.getAllowRules();
            assertEquals(1, snapshot.size());
            assertEquals(1, snapshot.get("bash").size());

            // Adding more rules after the snapshot does not mutate the returned view.
            engine.addRule(allowAll("bash"));
            assertEquals(1, snapshot.get("bash").size());

            // Engine itself does see the new rule.
            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Bash rules: prefix, substring, multi-rule")
    class BashRules {

        @Test
        @DisplayName("\"git:*\" matches non-read-only git subcommands")
        void bashPrefixWildcardMatches() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Bash", "git:*", PermissionBehavior.ALLOW));

            // "git commit" is not read-only so it must reach the allow-rule layer.
            StepVerifier.create(
                            engine.checkPermission(bash, Map.of("command", "git commit -m hello")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Wildcard pattern \"*install*\" matches mid-command")
        void bashSubstringMatch() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Bash", "*install*", PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(bash, Map.of("command", "npm install lodash")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deny git push:* wins over allow git:* on the same tool")
        void bashMultipleRules() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Bash", "git push:*", PermissionBehavior.DENY));
            engine.addRule(rule("Bash", "git:*", PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(bash, Map.of("command", "git push origin main")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();

            StepVerifier.create(
                            engine.checkPermission(bash, Map.of("command", "git commit -m hello")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("File rules: glob, directory globs")
    class FileRules {

        @Test
        @DisplayName("Glob pattern \"**/*.py\" matches Python file paths")
        void fileGlobPattern() {
            Read read = new Read(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Read", "**/*.py", PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(read, Map.of("file_path", "/some/dir/foo.py")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Directory glob \"src/**\" matches nested paths")
        void fileDirectoryPattern() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Write", "src/**", PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "src/main/Foo.java", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Dangerous path enforcement")
    class DangerousPath {

        @Test
        @DisplayName("Write to dangerous file (.bashrc) requires ASK")
        void dangerousFileBlocksWrite() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/home/user/.bashrc", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Edit on dangerous file requires ASK")
        void dangerousFileBlocksEdit() {
            Edit edit = new Edit(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    edit,
                                    Map.of(
                                            "file_path",
                                            "/home/user/.gitconfig",
                                            "old_string",
                                            "a",
                                            "new_string",
                                            "b")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Write inside dangerous dir (.ssh) requires ASK")
        void dangerousDirectoryBlocksWrite() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/home/user/.ssh/id_rsa", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Bash command touching dangerous path requires ASK")
        void dangerousPathInBashCommand() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "rm /home/user/.bashrc")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Dangerous path is bypass-immune")
        void dangerousPathBypassImmune() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/home/user/.bashrc", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Dangerous path overrides ACCEPT_EDITS inside working dir")
        void dangerousPathInAcceptEditsMode() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine =
                    new PermissionEngine(
                            contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, "/home/user"));

            StepVerifier.create(
                            engine.checkPermission(
                                    write,
                                    Map.of("file_path", "/home/user/.bashrc", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Safe file does not trigger dangerous-path check")
        void safeFileAllowsWrite() {
            Write write = new Write(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Write", null, PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(
                                    write, Map.of("file_path", "/tmp/safe.txt", "content", "x")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Rule suggestions emitted on ASK")
    class Suggestions {

        @Test
        @DisplayName("Bash ASK suggests command prefix patterns")
        void bashSuggestions() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "unknown_tool arg")))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertTrue(
                                        decision.getSuggestedRules().stream()
                                                .anyMatch(
                                                        r ->
                                                                "unknown_tool:*"
                                                                        .equals(r.ruleContent())));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Read ASK suggests parent dir glob pattern")
        void fileSuggestions() {
            Read read = new Read(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(read, Map.of("file_path", "/var/log/app.log")))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertTrue(
                                        decision.getSuggestedRules().stream()
                                                .anyMatch(
                                                        r -> "/var/log/*".equals(r.ruleContent())));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Read-only tool detection")
    class ReadOnly {

        @Test
        @DisplayName("git status is read-only → ALLOW")
        void gitStatusReadOnly() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "git status")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("ls is read-only → ALLOW")
        void lsReadOnly() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "ls -la /tmp")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("cat is read-only → ALLOW")
        void catReadOnly() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "cat /tmp/foo.txt")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("git commit is not read-only → ASK")
        void gitCommitNotReadOnly() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(bash, Map.of("command", "git commit -m hello")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Compound command with dangerous path triggers ASK")
        void compoundCommandDangerousPath() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "ls && rm /home/user/.bashrc")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Compound all-read-only command is allowed")
        void compoundAllReadOnly() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "ls -la && cat /tmp/foo.txt")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Compound with one write op fails read-only check → ASK")
        void compoundWithWriteOp() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "ls && touch /tmp/foo.txt")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Output redirection to dangerous path triggers ASK")
        void redirectToDangerousPath() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "echo x > /home/user/.bashrc")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Output redirection to safe path is allowed by rule")
        void redirectToSafePath() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Bash", "echo:*", PermissionBehavior.ALLOW));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "echo x > /tmp/safe.txt")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Safety checks survive BYPASS")
    class BypassImmune {

        @Test
        @DisplayName("Injection-style check survives BYPASS")
        void injectionCheckBypassImmune() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "ls $(pwd)")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Injection-style check is not bypassed by allow rule")
        void injectionCheckNotBypassedByAllow() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(rule("Bash", null, PermissionBehavior.ALLOW));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "ls $(pwd)")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Dangerous removal survives BYPASS")
        void dangerousRemovalBypassImmune() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(bash, Map.of("command", "rm /etc")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("sed -i constraint survives BYPASS")
        void sedConstraintBypassImmune() {
            Bash bash = new Bash();
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(
                            engine.checkPermission(
                                    bash, Map.of("command", "sed -i 's/x/y/' /home/user/.bashrc")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Dangerous config path survives BYPASS")
        void dangerousConfigPathBypassImmune() {
            Edit edit = new Edit(freshToolContext());
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(
                            engine.checkPermission(
                                    edit,
                                    Map.of(
                                            "file_path",
                                            "/home/user/.bashrc",
                                            "old_string",
                                            "a",
                                            "new_string",
                                            "b")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }
    }
}
