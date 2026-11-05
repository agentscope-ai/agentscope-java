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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Engine integration of model-requested escalation: deny rules stay supreme, a valid
 * strictly-wider request becomes an ASK carrying the justification, and every rejection fails
 * closed — while calls without escalation arguments keep byte-for-byte the previous evaluation.
 */
class PermissionEngineEscalationTest {

    private static class FakeShellTool extends ToolBase {

        FakeShellTool() {
            super(
                    "execute",
                    "shell",
                    escalationAdvertisingSchema(),
                    /* isReadOnly */ false,
                    /* isConcurrencySafe */ true,
                    /* isMcp */ false,
                    /* mcpName */ null,
                    /* isExternalTool */ false,
                    /* isStateInjected */ false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("no tool-specific opinion"));
        }

        @Override
        public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new UnsupportedOperationException("not executed in engine tests"));
        }
    }

    /** Schema of an escalation-advertising shell tool (properties include sandbox_permissions). */
    private static Map<String, Object> escalationAdvertisingSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put(PermissionEscalation.ARG_PERMISSIONS, Map.of("type", "string"));
        props.put(PermissionEscalation.ARG_JUSTIFICATION, Map.of("type", "string"));
        schema.put("properties", props);
        return schema;
    }

    private static Map<String, Object> escalationArgs(String target, String justification) {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "npm test");
        if (target != null) {
            input.put(PermissionEscalation.ARG_PERMISSIONS, target);
        }
        if (justification != null) {
            input.put(PermissionEscalation.ARG_JUSTIFICATION, justification);
        }
        return input;
    }

    @Test
    @DisplayName("valid strictly-wider request -> ASK carrying target and justification")
    void validRequest_asksWithJustification() {
        PermissionContextState ctx =
                PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .escalationEnabled(true)
                        .build();
        PermissionEngine engine = new PermissionEngine(ctx);

        StepVerifier.create(
                        engine.checkPermission(
                                new FakeShellTool(),
                                escalationArgs("workspace-write", "must run the build")))
                .assertNext(
                        decision -> {
                            assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                            assertTrue(
                                    decision.getMessage().contains("workspace-write"),
                                    decision.getMessage());
                            assertTrue(
                                    decision.getMessage().contains("must run the build"),
                                    decision.getMessage());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("deny rules stay supreme — escalation can never override them")
    void denyRulesSupreme() {
        PermissionContextState ctx =
                PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .escalationEnabled(true)
                        .addDenyRule(
                                "execute",
                                new PermissionRule(
                                        "execute", null, PermissionBehavior.DENY, "test"))
                        .build();
        PermissionEngine engine = new PermissionEngine(ctx);

        StepVerifier.create(
                        engine.checkPermission(
                                new FakeShellTool(),
                                escalationArgs("danger-full-access", "please")))
                .assertNext(
                        decision -> {
                            assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                            assertTrue(
                                    decision.getDecisionReason().contains("Rule"),
                                    decision.getDecisionReason());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("malformed pairing -> fail-closed DENY with model-facing reason")
    void malformedPairing_denies() {
        PermissionContextState ctx =
                PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .escalationEnabled(true)
                        .build();
        PermissionEngine engine = new PermissionEngine(ctx);

        StepVerifier.create(
                        engine.checkPermission(
                                new FakeShellTool(), escalationArgs("workspace-write", null)))
                .assertNext(
                        decision -> {
                            assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                            assertTrue(
                                    decision.getMessage().contains("justification"),
                                    decision.getMessage());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("escalation arguments on a disabled agent -> fail-closed DENY")
    void disabledAgent_denies() {
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.DEFAULT).build());

        StepVerifier.create(
                        engine.checkPermission(
                                new FakeShellTool(),
                                escalationArgs("workspace-write", "must run the build")))
                .assertNext(
                        decision -> {
                            assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                            assertTrue(
                                    decision.getMessage().contains("not enabled"),
                                    decision.getMessage());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("call without escalation arguments keeps the previous evaluation")
    void noEscalationArgs_previousBehavior() {
        // DEFAULT mode, no rules: previous default is ASK.
        PermissionEngine disabledEngine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.DEFAULT).build());
        StepVerifier.create(
                        disabledEngine.checkPermission(
                                new FakeShellTool(), Map.of("command", "ls")))
                .assertNext(d -> assertEquals(PermissionBehavior.ASK, d.getBehavior()))
                .verifyComplete();

        // Enabled agent, plain call: still the default ASK — enabling the flag changes nothing
        // for calls that do not opt in.
        PermissionEngine enabledEngine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.DEFAULT)
                                .escalationEnabled(true)
                                .build());
        StepVerifier.create(
                        enabledEngine.checkPermission(new FakeShellTool(), Map.of("command", "ls")))
                .assertNext(d -> assertEquals(PermissionBehavior.ASK, d.getBehavior()))
                .verifyComplete();
    }

    @Test
    @DisplayName("escalation flag survives serialization; old JSON without it stays disabled")
    void serializationCompatibility() throws Exception {
        PermissionContextState enabled =
                PermissionContextState.builder()
                        .mode(PermissionMode.EXPLORE)
                        .escalationEnabled(true)
                        .build();
        String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(enabled);
        assertTrue(json.contains("escalation_enabled"), json);
        PermissionContextState roundTripped =
                io.agentscope.core.util.JsonUtils.getJsonCodec()
                        .fromJson(json, PermissionContextState.class);
        assertTrue(roundTripped.isEscalationEnabled());
        assertEquals(PermissionMode.EXPLORE, roundTripped.getMode());

        // Pre-feature JSON has no escalation_enabled field — must deserialize to false.
        String legacyJson =
                "{\"mode\":\"default\",\"working_directories\":{},\"allow_rules\":{},"
                        + "\"deny_rules\":{},\"ask_rules\":{}}";
        PermissionContextState legacy =
                io.agentscope.core.util.JsonUtils.getJsonCodec()
                        .fromJson(legacyJson, PermissionContextState.class);
        assertFalse(legacy.isEscalationEnabled());
        assertTrue(legacy.isTrivial());
    }

    @Test
    @DisplayName("the escalation flag alone does NOT flip the agent's permission posture")
    void escalationFlagAloneKeepsContextTrivial() {
        // Enabling escalation must not switch an otherwise default-configured agent from
        // auto-execution to ask-per-call: only calls that actually carry escalation arguments
        // engage the engine (ReActAgent routes per call via hasEscalationArgs).
        assertTrue(PermissionContextState.builder().build().isTrivial());
        assertTrue(PermissionContextState.builder().escalationEnabled(true).build().isTrivial());
    }

    @Test
    @DisplayName("withMode preserves the escalation flag")
    void withModePreservesFlag() {
        PermissionContextState enabled =
                PermissionContextState.builder().escalationEnabled(true).build();
        assertTrue(enabled.withMode(PermissionMode.EXPLORE).isEscalationEnabled());
        assertTrue(
                enabled.withEscalationEnabled(false)
                                .withMode(PermissionMode.BYPASS)
                                .isEscalationEnabled()
                        == false);
    }

    @Test
    @DisplayName("P1: a tool's own hard DENY cannot be overridden by an approved escalation")
    void toolSelfCheckHardDeny_beatsEscalation() {
        FakeShellTool denying =
                new FakeShellTool() {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState ctx) {
                        return Mono.just(
                                PermissionDecision.builder()
                                        .behavior(PermissionBehavior.DENY)
                                        .message("business rule: this command is forbidden")
                                        .build());
                    }
                };
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.DEFAULT)
                                .escalationEnabled(true)
                                .build());

        StepVerifier.create(
                        engine.checkPermission(
                                denying, escalationArgs("danger-full-access", "please")))
                .assertNext(
                        decision -> {
                            assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                            assertTrue(
                                    decision.getMessage().contains("business rule"),
                                    decision.getMessage());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("P2: a business tool's own `justification` argument is not escalation intent")
    void businessJustificationArg_notEscalationIntent() {
        // An ordinary tool whose schema does NOT advertise sandbox_permissions, called with a
        // legitimate business `justification` parameter — on BOTH a feature-off and a
        // feature-on agent it must keep its previous evaluation (mode default ASK here), never
        // the escalation "not enabled" denial.
        io.agentscope.core.tool.ToolBase businessTool =
                new io.agentscope.core.tool.ToolBase(
                        "deploy",
                        "business",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("justification", Map.of("type", "string"))),
                        /* isReadOnly */ false,
                        /* isConcurrencySafe */ true,
                        /* isMcp */ false,
                        /* mcpName */ null,
                        /* isExternalTool */ false,
                        /* isStateInjected */ false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState ctx) {
                        return Mono.just(PermissionDecision.passthrough("business tool"));
                    }

                    @Override
                    public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                            io.agentscope.core.tool.ToolCallParam param) {
                        return Mono.error(new UnsupportedOperationException("not executed"));
                    }
                };
        Map<String, Object> businessArgs = new HashMap<>();
        businessArgs.put("justification", "rolling out the new build");

        for (boolean flag : new boolean[] {false, true}) {
            PermissionEngine engine =
                    new PermissionEngine(
                            PermissionContextState.builder()
                                    .mode(PermissionMode.DEFAULT)
                                    .escalationEnabled(flag)
                                    .build());
            StepVerifier.create(engine.checkPermission(businessTool, businessArgs))
                    .assertNext(
                            d ->
                                    assertEquals(
                                            PermissionBehavior.ASK,
                                            d.getBehavior(),
                                            "flag=" + flag + " must keep normal evaluation"))
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("empty self-check Mono on a valid escalation request stays ASK (fail-closed)")
    void emptySelfCheck_fallsBackToEscalationAsk() {
        // ToolBase's contract only promises a non-null Mono — an empty one is legal, and the
        // normal path already defends with switchIfEmpty. The escalation branch must not be
        // the one hole: an empty stream must never drop the call out of the gate (which would
        // execute it unapproved).
        FakeShellTool emptyChecking =
                new FakeShellTool() {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState ctx) {
                        return Mono.empty();
                    }
                };
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.DEFAULT)
                                .escalationEnabled(true)
                                .build());

        StepVerifier.create(
                        engine.checkPermission(
                                emptyChecking, escalationArgs("workspace-write", "run build")))
                .assertNext(
                        d -> {
                            assertEquals(PermissionBehavior.ASK, d.getBehavior());
                            assertTrue(d.getMessage().contains("workspace-write"), d.getMessage());
                        })
                .verifyComplete();
    }
}
