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
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.permission.ToolBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Behaviour spec for the {@link PermissionEngine}.
 *
 * <p>Engine-only behaviours (rule priority, mode fallbacks, default ASK/DENY) are exercised
 * directly against a {@link FakePermissionTool}. Test cases that depend on real built-in tools
 * ({@code Bash}, {@code Read}, {@code Write}, {@code Edit}) remain {@link Disabled} until the
 * built-in tools land — they document the contract the built-ins will need to satisfy.
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
        @Disabled("Stage 4 unlocks: requires real Write tool with dangerous-path check")
        @DisplayName("Dangerous path is bypass-immune (returns ASK in BYPASS)")
        void bypassAsksOnDangerousPath() {}

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
        @Disabled("Stage 4 unlocks: requires Write/Read/Edit tools with working-dir awareness")
        @DisplayName("ACCEPT_EDITS allows Write/Read/Edit within working dir")
        void acceptEditsAllowsInsideWorkingDir() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Edit tool with working-dir awareness")
        @DisplayName("ACCEPT_EDITS asks for edits outside working dir")
        void acceptEditsAsksOutsideWorkingDir() {}

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
        @Disabled("Stage 4 unlocks: requires real Bash tool with prefix matcher")
        @DisplayName("\"git:*\" matches \"git\", \"git status\", \"git add .\"")
        void bashPrefixWildcardMatches() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Bash tool with substring matcher")
        @DisplayName("Substring pattern \"install\" matches mid-command")
        void bashSubstringMatch() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Bash tool with multi-rule resolution")
        @DisplayName("Mixed rules resolve by tool+pattern match precedence")
        void bashMultipleRules() {}
    }

    @Nested
    @DisplayName("File rules: glob, directory globs")
    class FileRules {

        @Test
        @Disabled("Stage 4 unlocks: requires real Read tool with glob matcher")
        @DisplayName("Glob pattern \"*.py\" matches Python file paths")
        void fileGlobPattern() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Write tool with directory glob matcher")
        @DisplayName("Directory glob \"src/**\" matches nested paths")
        void fileDirectoryPattern() {}
    }

    @Nested
    @DisplayName("Dangerous path enforcement")
    class DangerousPath {

        @Test
        @Disabled("Stage 4 unlocks: requires real Write tool with dangerous-file check")
        @DisplayName("Write to dangerous file (.bashrc) requires ASK")
        void dangerousFileBlocksWrite() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Edit tool with dangerous-file check")
        @DisplayName("Edit on dangerous file requires ASK")
        void dangerousFileBlocksEdit() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Write tool with dangerous-directory check")
        @DisplayName("Write inside dangerous dir (.ssh) requires ASK")
        void dangerousDirectoryBlocksWrite() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Bash tool with dangerous-path inspection")
        @DisplayName("Bash command touching dangerous path requires ASK")
        void dangerousPathInBashCommand() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Write tool with bypass-immune dangerous-path")
        @DisplayName("Dangerous path is bypass-immune")
        void dangerousPathBypassImmune() {}

        @Test
        @Disabled(
                "Stage 4 unlocks: requires real Write tool with dangerous-path overriding"
                        + " ACCEPT_EDITS")
        @DisplayName("Dangerous path overrides ACCEPT_EDITS inside working dir")
        void dangerousPathInAcceptEditsMode() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Write tool exercising the safe-path path")
        @DisplayName("Safe file does not trigger dangerous-path check")
        void safeFileAllowsWrite() {}
    }

    @Nested
    @DisplayName("Rule suggestions emitted on ASK")
    class Suggestions {

        @Test
        @Disabled("Stage 4 unlocks: requires real Bash tool with command-prefix suggestions")
        @DisplayName("Bash ASK suggests command prefix pattern")
        void bashSuggestions() {}

        @Test
        @Disabled("Stage 4 unlocks: requires real Read tool with parent-dir glob suggestions")
        @DisplayName("File tool ASK suggests parent dir glob pattern")
        void fileSuggestions() {}
    }

    @Nested
    @DisplayName("Read-only tool detection")
    class ReadOnly {

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST read-only classification")
        @DisplayName("git status is read-only")
        void gitStatusReadOnly() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST read-only classification")
        @DisplayName("ls is read-only")
        void lsReadOnly() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST read-only classification")
        @DisplayName("cat is read-only")
        void catReadOnly() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST read-only classification")
        @DisplayName("git commit is not read-only")
        void gitCommitNotReadOnly() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST compound-command analysis")
        @DisplayName("Compound command with dangerous path triggers ASK")
        void compoundCommandDangerousPath() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST compound-command analysis")
        @DisplayName("Compound all-read-only command is allowed in EXPLORE")
        void compoundAllReadOnly() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST compound-command analysis")
        @DisplayName("Compound with one write op fails read-only check")
        void compoundWithWriteOp() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST redirect-target analysis")
        @DisplayName("Output redirection to dangerous path triggers ASK")
        void redirectToDangerousPath() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST redirect-target analysis")
        @DisplayName("Output redirection to safe path is allowed by rule")
        void redirectToSafePath() {}
    }

    @Nested
    @DisplayName("Safety checks survive BYPASS")
    class BypassImmune {

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST injection detection")
        @DisplayName("Injection-style check survives BYPASS")
        void injectionCheckBypassImmune() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST injection detection")
        @DisplayName("Injection-style check is not bypassed by allow rule")
        void injectionCheckNotBypassedByAllow() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST dangerous-removal detection")
        @DisplayName("Dangerous removal survives BYPASS")
        void dangerousRemovalBypassImmune() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Bash AST sed -i constraint detection")
        @DisplayName("sed -i constraint survives BYPASS")
        void sedConstraintBypassImmune() {}

        @Test
        @Disabled("Stage 4 unlocks: requires Edit tool with dangerous-config-path check")
        @DisplayName("Dangerous config path survives BYPASS")
        void dangerousConfigPathBypassImmune() {}
    }
}
