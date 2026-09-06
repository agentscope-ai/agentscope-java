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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour spec for {@link PermissionEscalation}: the strictly-wider ladder, argument-pairing
 * validation, and fail-closed resolution of a {@code sandbox_permissions} request.
 */
class PermissionEscalationTest {

    private static Map<String, Object> args(Object permissions, Object justification) {
        Map<String, Object> input = new HashMap<>();
        if (permissions != null) {
            input.put(PermissionEscalation.ARG_PERMISSIONS, permissions);
        }
        if (justification != null) {
            input.put(PermissionEscalation.ARG_JUSTIFICATION, justification);
        }
        return input;
    }

    @Test
    @DisplayName("no escalation arguments -> NOT_PRESENT, normal evaluation proceeds")
    void noArgs_notPresent() {
        assertEquals(
                PermissionEscalation.Outcome.Type.NOT_PRESENT,
                PermissionEscalation.resolve(Map.of(), PermissionMode.DEFAULT, true).type());
        assertEquals(
                PermissionEscalation.Outcome.Type.NOT_PRESENT,
                PermissionEscalation.resolve(null, PermissionMode.DEFAULT, true).type());
    }

    @Test
    @DisplayName("escalation arguments on a disabled agent -> fail-closed DENY")
    void disabled_denyClosed() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("workspace-write", "need to run the build"),
                        PermissionMode.DEFAULT,
                        false);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("not enabled"), out.denialReason());
    }

    @Test
    @DisplayName("permissions without justification -> DENY (pairing)")
    void permissionsAlone_deny() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("workspace-write", null), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("requires a"), out.denialReason());
    }

    @Test
    @DisplayName("justification without permissions -> DENY (pairing)")
    void justificationAlone_deny() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args(null, "need to run the build"), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("only valid together"), out.denialReason());
    }

    @Test
    @DisplayName("blank justification -> DENY")
    void blankJustification_deny() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("workspace-write", "   "), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("non-empty"), out.denialReason());
    }

    @Test
    @DisplayName("unknown target -> DENY with the closed vocabulary")
    void unknownTarget_deny() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("root", "need root"), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("read-only"), out.denialReason());
        assertTrue(out.denialReason().contains("danger-full-access"), out.denialReason());
    }

    @Test
    @DisplayName("DONT_ASK (no approver available) -> DENY")
    void dontAsk_deny() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("danger-full-access", "need it"), PermissionMode.DONT_ASK, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("unavailable"), out.denialReason());
    }

    @Test
    @DisplayName("non-wider target -> DENY: current mode already covers it")
    void notWider_deny() {
        // read-only is the floor: nobody can escalate DOWN to it.
        assertEquals(
                PermissionEscalation.Outcome.Type.DENY,
                PermissionEscalation.resolve(
                                args("read-only", "reading only"), PermissionMode.EXPLORE, true)
                        .type());
        // BYPASS is the ceiling: nothing is wider.
        assertEquals(
                PermissionEscalation.Outcome.Type.DENY,
                PermissionEscalation.resolve(
                                args("danger-full-access", "full speed"),
                                PermissionMode.BYPASS,
                                true)
                        .type());
        // Same rung, not strictly wider.
        assertEquals(
                PermissionEscalation.Outcome.Type.DENY,
                PermissionEscalation.resolve(
                                args("workspace-write", "build it"),
                                PermissionMode.ACCEPT_EDITS,
                                true)
                        .type());
    }

    @Test
    @DisplayName("strictly-wider targets -> ASK carrying target + justification")
    void wider_asks() {
        PermissionEscalation.Outcome explore =
                PermissionEscalation.resolve(
                        args("workspace-write", "must run the test suite"),
                        PermissionMode.EXPLORE,
                        true);
        assertEquals(PermissionEscalation.Outcome.Type.ASK, explore.type());
        assertEquals("workspace-write", explore.target());
        assertEquals("must run the test suite", explore.justification());
        assertNull(explore.denialReason());

        assertEquals(
                PermissionEscalation.Outcome.Type.ASK,
                PermissionEscalation.resolve(
                                args("danger-full-access", "install system deps"),
                                PermissionMode.ACCEPT_EDITS,
                                true)
                        .type());
        // Two rungs up in one hop is allowed (read-only -> danger-full-access from EXPLORE).
        assertEquals(
                PermissionEscalation.Outcome.Type.ASK,
                PermissionEscalation.resolve(
                                args("danger-full-access", "reboot the box"),
                                PermissionMode.EXPLORE,
                                true)
                        .type());
    }

    @Test
    @DisplayName("target matching is case-insensitive and trims whitespace")
    void targetNormalization() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args(" Workspace-Write ", "build"), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.ASK, out.type());
        assertEquals("workspace-write", out.target());
    }

    @Test
    @DisplayName("non-string argument values are rejected, not coerced")
    void nonStringValues_deny() {
        Map<String, Object> numericTarget = new HashMap<>();
        numericTarget.put(PermissionEscalation.ARG_PERMISSIONS, 42);
        numericTarget.put(PermissionEscalation.ARG_JUSTIFICATION, "why");
        assertEquals(
                PermissionEscalation.Outcome.Type.DENY,
                PermissionEscalation.resolve(numericTarget, PermissionMode.DEFAULT, true).type());

        Map<String, Object> objectReason = new HashMap<>();
        objectReason.put(PermissionEscalation.ARG_PERMISSIONS, "workspace-write");
        objectReason.put(PermissionEscalation.ARG_JUSTIFICATION, Map.of("why", "because"));
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(objectReason, PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("must be a string"), out.denialReason());
    }

    @Test
    @DisplayName("justification is capped with a visible truncation marker")
    void longJustification_capped() {
        String longReason = "r".repeat(PermissionEscalation.MAX_JUSTIFICATION_LENGTH + 100);
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("workspace-write", longReason), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.ASK, out.type());
        assertEquals(PermissionEscalation.MAX_JUSTIFICATION_LENGTH, out.justification().length());
        assertTrue(out.justification().endsWith("…"), out.justification());
    }

    @Test
    @DisplayName("keys explicitly mapped to null carry no escalation intent")
    void nullValuedKeys_notEscalation() {
        Map<String, Object> input = new HashMap<>();
        input.put(PermissionEscalation.ARG_PERMISSIONS, null);
        input.put(PermissionEscalation.ARG_JUSTIFICATION, null);
        input.put("command", "npm test");
        assertFalse(PermissionEscalation.hasEscalationArgs(input));
        assertEquals(
                PermissionEscalation.Outcome.Type.NOT_PRESENT,
                PermissionEscalation.resolve(input, PermissionMode.DEFAULT, true).type());
    }

    @Test
    @DisplayName("not-wider denial talks about the ladder, not about mode coverage")
    void notWiderMessage_isLadderPhrased() {
        PermissionEscalation.Outcome out =
                PermissionEscalation.resolve(
                        args("read-only", "read"), PermissionMode.DEFAULT, true);
        assertEquals(PermissionEscalation.Outcome.Type.DENY, out.type());
        assertTrue(out.denialReason().contains("not strictly wider"), out.denialReason());
    }
}
