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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionDecisionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void staticFactoriesProduceExpectedBehavior() {
        assertSame(PermissionBehavior.ALLOW, PermissionDecision.allow("ok").getBehavior());
        assertSame(PermissionBehavior.DENY, PermissionDecision.deny("no").getBehavior());
        assertSame(PermissionBehavior.ASK, PermissionDecision.ask("?").getBehavior());
        assertSame(
                PermissionBehavior.PASSTHROUGH,
                PermissionDecision.passthrough("defer").getBehavior());
    }

    @Test
    void builderRequiresBehaviorAndMessage() {
        assertThrows(NullPointerException.class, () -> PermissionDecision.builder().build());
        assertThrows(
                NullPointerException.class,
                () -> PermissionDecision.builder().behavior(PermissionBehavior.ALLOW).build());
    }

    @Test
    void optionalFieldsDefaultToNull() {
        PermissionDecision decision = PermissionDecision.allow("ok");
        assertNull(decision.getDecisionReason());
        assertNull(decision.getUpdatedInput());
        assertNull(decision.getSuggestedRules());
    }

    @Test
    void updatedInputIsDefensivelyCopied() {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("path", "/tmp/foo");
        PermissionDecision decision =
                PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message("ok")
                        .updatedInput(input)
                        .build();
        input.put("path", "/tmp/MUTATED");
        assertEquals("/tmp/foo", decision.getUpdatedInput().get("path"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.getUpdatedInput().put("more", "no"));
    }

    @Test
    void suggestedRulesAreImmutable() {
        List<PermissionRule> source =
                new java.util.ArrayList<>(
                        List.of(
                                new PermissionRule(
                                        "Bash", null, PermissionBehavior.ALLOW, "suggested")));
        PermissionDecision decision =
                PermissionDecision.builder()
                        .behavior(PermissionBehavior.ASK)
                        .message("confirm?")
                        .suggestedRules(source)
                        .build();
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        decision.getSuggestedRules()
                                .add(
                                        new PermissionRule(
                                                "Read", null, PermissionBehavior.DENY, "test")));
    }

    @Test
    void jsonOmitsNullOptionalFields() throws Exception {
        String json = mapper.writeValueAsString(PermissionDecision.allow("ok"));
        assertTrue(json.contains("\"behavior\":\"allow\""), json);
        assertTrue(json.contains("\"message\":\"ok\""), json);
        assertFalse(json.contains("decision_reason"), json);
        assertFalse(json.contains("updated_input"), json);
        assertFalse(json.contains("suggested_rules"), json);
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        PermissionDecision original =
                PermissionDecision.builder()
                        .behavior(PermissionBehavior.ASK)
                        .message("Confirm running git push?")
                        .decisionReason("matches ask rule for Bash")
                        .updatedInput(Map.of("command", "git push --force"))
                        .suggestedRules(
                                List.of(
                                        new PermissionRule(
                                                "Bash",
                                                "git push:*",
                                                PermissionBehavior.ALLOW,
                                                "suggested")))
                        .build();
        String json = mapper.writeValueAsString(original);
        PermissionDecision decoded = mapper.readValue(json, PermissionDecision.class);
        assertEquals(original, decoded);
    }
}
