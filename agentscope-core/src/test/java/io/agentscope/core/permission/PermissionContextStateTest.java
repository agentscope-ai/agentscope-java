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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void inheritFromMergesParentScopeAndRulesWithoutMutatingEitherContext() {
        AdditionalWorkingDirectory childShared =
                new AdditionalWorkingDirectory("/child/shared", "child");
        AdditionalWorkingDirectory parentShared =
                new AdditionalWorkingDirectory("/parent/shared", "parent");
        AdditionalWorkingDirectory parentOnly =
                new AdditionalWorkingDirectory("/parent/only", "parent");
        PermissionRule duplicateAllow =
                new PermissionRule("Bash", "git status", PermissionBehavior.ALLOW, "shared");
        PermissionRule parentDeny =
                new PermissionRule("Bash", "rm -rf", PermissionBehavior.DENY, "parent");
        PermissionRule parentAsk =
                new PermissionRule("Write", "/etc/**", PermissionBehavior.ASK, "parent");
        PermissionContextState child =
                PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS)
                        .addWorkingDirectory("shared", childShared)
                        .addAllowRule("Bash", duplicateAllow)
                        .build();
        PermissionContextState parent =
                PermissionContextState.builder()
                        .mode(PermissionMode.DONT_ASK)
                        .addWorkingDirectory("shared", parentShared)
                        .addWorkingDirectory("parent-only", parentOnly)
                        .addAllowRule("Bash", duplicateAllow)
                        .addDenyRule("Bash", parentDeny)
                        .addAskRule("Write", parentAsk)
                        .build();

        PermissionContextState inherited = child.inheritFrom(parent);

        assertNotSame(child, inherited);
        assertEquals(PermissionMode.ACCEPT_EDITS, inherited.getMode());
        assertEquals(childShared, inherited.getWorkingDirectories().get("shared"));
        assertEquals(parentOnly, inherited.getWorkingDirectories().get("parent-only"));
        assertEquals(java.util.List.of(duplicateAllow), inherited.getAllowRules().get("Bash"));
        assertEquals(java.util.List.of(parentDeny), inherited.getDenyRules().get("Bash"));
        assertEquals(java.util.List.of(parentAsk), inherited.getAskRules().get("Write"));
        assertEquals(1, child.getWorkingDirectories().size());
        assertEquals(2, parent.getWorkingDirectories().size());
    }

    @Test
    void inheritFromRejectsNullParent() {
        PermissionContextState child = PermissionContextState.builder().build();

        assertThrows(NullPointerException.class, () -> child.inheritFrom(null));
    }
}
