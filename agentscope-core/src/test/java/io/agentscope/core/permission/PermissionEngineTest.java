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
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
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
 * directly against a {@link FakePermissionTool}.
 *
 * <p>Coverage targets:
 *
 * <ol>
 *   <li>Rule priority — deny &gt; ask &gt; allow
 *   <li>Modes — BYPASS / DONT_ASK / EXPLORE / DEFAULT
 *   <li>Tool-supplied decisions (ALLOW / DENY / ASK / PASSTHROUGH)
 *   <li>Default ASK with suggestions
 *   <li>Snapshot semantics
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
                    /* isStateInjected */ false,
                    null);
        }

        FakePermissionTool withPermissionDecision(PermissionDecision decision) {
            this.toolDecision = decision;
            return this;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
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

    private static PermissionContextState contextWithMode(PermissionMode mode) {
        return PermissionContextState.builder().mode(mode).build();
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
    @DisplayName("Modes: BYPASS / DONT_ASK / EXPLORE / DEFAULT")
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
}
