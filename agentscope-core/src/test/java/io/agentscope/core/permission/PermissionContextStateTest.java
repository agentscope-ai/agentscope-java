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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionContextStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultModeIsDEFAULT() {
        PermissionContextState ctx = PermissionContextState.builder().build();
        assertSame(PermissionMode.DEFAULT, ctx.getMode());
        assertTrue(ctx.getWorkingDirectories().isEmpty());
        assertTrue(ctx.getAllowRules().isEmpty());
        assertTrue(ctx.getDenyRules().isEmpty());
        assertTrue(ctx.getAskRules().isEmpty());
    }

    @Test
    void rulesAccumulateUnderSameToolName() {
        PermissionRule ruleA =
                new PermissionRule("Bash", "git status", PermissionBehavior.ALLOW, "test");
        PermissionRule ruleB =
                new PermissionRule("Bash", "git diff", PermissionBehavior.ALLOW, "test");
        PermissionContextState ctx =
                PermissionContextState.builder()
                        .addAllowRule("Bash", ruleA)
                        .addAllowRule("Bash", ruleB)
                        .build();
        assertEquals(2, ctx.getAllowRules().get("Bash").size());
    }

    @Test
    void ruleTablesAreImmutable() {
        PermissionContextState ctx =
                PermissionContextState.builder()
                        .addAllowRule(
                                "Bash",
                                new PermissionRule("Bash", null, PermissionBehavior.ALLOW, "test"))
                        .build();
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        ctx.getAllowRules()
                                .put(
                                        "Read",
                                        java.util.List.of(
                                                new PermissionRule(
                                                        "Read",
                                                        null,
                                                        PermissionBehavior.ALLOW,
                                                        "test"))));
    }

    @Test
    void workingDirectoryEntriesAreCopied() {
        AdditionalWorkingDirectory dir =
                new AdditionalWorkingDirectory("/tmp/proj", "userSettings");
        PermissionContextState ctx =
                PermissionContextState.builder().addWorkingDirectory("/tmp/proj", dir).build();
        assertEquals(dir, ctx.getWorkingDirectories().get("/tmp/proj"));
    }

    @Test
    void builderRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> PermissionContextState.builder().mode(null));
        assertThrows(
                NullPointerException.class,
                () -> PermissionContextState.builder().addAllowRule(null, null));
        assertThrows(
                NullPointerException.class,
                () -> PermissionContextState.builder().addWorkingDirectory("path", null));
    }

    @Test
    void jsonRoundTripPreservesAllRuleTables() throws Exception {
        PermissionContextState original =
                PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS)
                        .addWorkingDirectory(
                                "/tmp/proj",
                                new AdditionalWorkingDirectory("/tmp/proj", "userSettings"))
                        .addAllowRule(
                                "Read",
                                new PermissionRule(
                                        "Read", "src/**", PermissionBehavior.ALLOW, "test"))
                        .addDenyRule(
                                "Bash",
                                new PermissionRule(
                                        "Bash", "rm -rf", PermissionBehavior.DENY, "test"))
                        .addAskRule(
                                "Write",
                                new PermissionRule(
                                        "Write", "/etc/**", PermissionBehavior.ASK, "test"))
                        .build();
        String json = mapper.writeValueAsString(original);
        PermissionContextState decoded = mapper.readValue(json, PermissionContextState.class);
        assertEquals(original, decoded);
    }

    @Test
    void withAddedRulesRoutesByBehaviorAndPreservesContext() {
        PermissionContextState original =
                PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS)
                        .addWorkingDirectory(
                                "/tmp/proj",
                                new AdditionalWorkingDirectory("/tmp/proj", "userSettings"))
                        .addAskRule(
                                "Read",
                                new PermissionRule(
                                        "Read", "src/**", PermissionBehavior.ASK, "declared"))
                        .build();

        PermissionRule newAllow =
                new PermissionRule("Write", null, PermissionBehavior.ALLOW, "user_confirm");
        PermissionRule newDeny =
                new PermissionRule("Bash", "rm -rf", PermissionBehavior.DENY, "user_confirm");
        PermissionRule newAsk =
                new PermissionRule("Write", "/etc/**", PermissionBehavior.ASK, "user_confirm");
        PermissionRule passthrough =
                new PermissionRule("Bash", null, PermissionBehavior.PASSTHROUGH, "user_confirm");

        // Null list and empty list return the same instance.
        assertSame(original, original.withAddedRules(null));
        assertSame(original, original.withAddedRules(java.util.List.of()));

        PermissionRule nullEntry = null;
        PermissionContextState merged =
                original.withAddedRules(
                        java.util.Arrays.asList(newAllow, newDeny, newAsk, passthrough, nullEntry));

        // Mode, working directories and the declared ASK rule are preserved.
        org.junit.jupiter.api.Assertions.assertEquals(
                PermissionMode.ACCEPT_EDITS, merged.getMode());
        org.junit.jupiter.api.Assertions.assertEquals(
                original.getWorkingDirectories(), merged.getWorkingDirectories());
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getAskRules()
                        .getOrDefault("Read", java.util.List.of())
                        .contains(
                                new PermissionRule(
                                        "Read", "src/**", PermissionBehavior.ASK, "declared")));

        // New rules routed by behavior.
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getAllowRules()
                        .getOrDefault("Write", java.util.List.of())
                        .contains(newAllow));
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getDenyRules().getOrDefault("Bash", java.util.List.of()).contains(newDeny));
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getAskRules().getOrDefault("Write", java.util.List.of()).contains(newAsk));

        // PASSTHROUGH rules are not persisted anywhere.
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getAllowRules().values().stream().noneMatch(l -> l.contains(passthrough)));
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getDenyRules().values().stream().noneMatch(l -> l.contains(passthrough)));
        org.junit.jupiter.api.Assertions.assertTrue(
                merged.getAskRules().values().stream().noneMatch(l -> l.contains(passthrough)));

        // The original instance is never mutated.
        org.junit.jupiter.api.Assertions.assertFalse(
                original.getAllowRules()
                        .getOrDefault("Write", java.util.List.of())
                        .contains(newAllow));
    }

    @Test
    void withAddedRulesDeduplicatesIdenticalRules() {
        PermissionContextState original =
                PermissionContextState.builder()
                        .addAllowRule(
                                "Write",
                                new PermissionRule(
                                        "Write", null, PermissionBehavior.ALLOW, "user_confirm"))
                        .build();

        PermissionRule duplicate =
                new PermissionRule("Write", null, PermissionBehavior.ALLOW, "user_confirm");

        // The same rule accepted twice must not grow the persisted table.
        PermissionContextState merged =
                original.withAddedRules(java.util.List.of(duplicate, duplicate));

        org.junit.jupiter.api.Assertions.assertEquals(
                1, merged.getAllowRules().get("Write").size());
        org.junit.jupiter.api.Assertions.assertEquals(
                merged, original.withAddedRules(java.util.List.of(duplicate)));
    }

    @Test
    void withAddedRulesTreatsNullAndEmptyContentAsEquivalent() {
        PermissionContextState original =
                PermissionContextState.builder()
                        .addAllowRule(
                                "Write",
                                new PermissionRule(
                                        "Write", null, PermissionBehavior.ALLOW, "user_confirm"))
                        .build();

        // "" ruleContent is evaluation-equivalent to null: re-accepting it
        // must not grow the table even though record equality would differ.
        PermissionContextState merged =
                original.withAddedRules(
                        java.util.List.of(
                                new PermissionRule(
                                        "Write", "", PermissionBehavior.ALLOW, "user_confirm")));

        org.junit.jupiter.api.Assertions.assertEquals(
                1, merged.getAllowRules().get("Write").size());

        // A rule differing only in source is a different rule: it appends.
        PermissionContextState mergedWithSuggested =
                original.withAddedRules(
                        java.util.List.of(
                                new PermissionRule(
                                        "Write", null, PermissionBehavior.ALLOW, "suggested")));
        org.junit.jupiter.api.Assertions.assertEquals(
                2, mergedWithSuggested.getAllowRules().get("Write").size());
    }

    @Test
    void addIfAbsentAppendsOnlyWhenEveryComparisonDiffers() {
        // Direct unit test of the dedup comparison on a synthetic table:
        // the behavior-routed merge cannot produce bucket entries whose
        // toolName or behavior differ from the candidate (routing keys on
        // both), so those short-circuit arms are exercised here.
        PermissionRule candidate =
                new PermissionRule("Write", null, PermissionBehavior.ALLOW, "user_confirm");
        Map<String, java.util.List<PermissionRule>> table = new LinkedHashMap<>();
        table.put(
                "Write",
                new ArrayList<>(
                        java.util.List.of(
                                new PermissionRule(
                                        "Write", "x", PermissionBehavior.DENY, "suggested"),
                                new PermissionRule(
                                        "Other", "x", PermissionBehavior.ALLOW, "suggested"),
                                new PermissionRule(
                                        "Write", "x", PermissionBehavior.ALLOW, "imported"),
                                new PermissionRule(
                                        "Write", "z", PermissionBehavior.ALLOW, "user_confirm"))));

        PermissionContextState.addIfAbsent(table, candidate);
        org.junit.jupiter.api.Assertions.assertEquals(
                5, table.get("Write").size(), "every entry differs from the candidate");

        PermissionContextState.addIfAbsent(table, candidate);
        org.junit.jupiter.api.Assertions.assertEquals(
                5, table.get("Write").size(), "exact duplicate must not append");
    }
}
