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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentSpawnTool#deterministicHash} — the deterministic key derivation used when
 * {@code SubagentDeclaration.persistSession = true}.
 */
class AgentSpawnToolKeyTest {

    @Test
    void sameInputs_produceSameHash() {
        String h1 = AgentSpawnTool.deterministicHash("session-1", "code-reviewer", "review");
        String h2 = AgentSpawnTool.deterministicHash("session-1", "code-reviewer", "review");
        assertEquals(h1, h2);
    }

    @Test
    void hashIs12HexChars() {
        String h = AgentSpawnTool.deterministicHash("s1", "worker", null);
        assertEquals(12, h.length());
        assertTrue(h.matches("[0-9a-f]{12}"));
    }

    @Test
    void differentParentSession_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("session-A", "worker", null);
        String h2 = AgentSpawnTool.deterministicHash("session-B", "worker", null);
        assertNotEquals(h1, h2);
    }

    @Test
    void differentAgentId_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "code-reviewer", null);
        String h2 = AgentSpawnTool.deterministicHash("s1", "test-runner", null);
        assertNotEquals(h1, h2);
    }

    @Test
    void differentLabel_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "worker", "alpha");
        String h2 = AgentSpawnTool.deterministicHash("s1", "worker", "beta");
        assertNotEquals(h1, h2);
    }

    @Test
    void nullLabel_differentFromNonNullLabel() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "worker", null);
        String h2 = AgentSpawnTool.deterministicHash("s1", "worker", "label");
        assertNotEquals(h1, h2);
    }

    @Test
    void nullParentSession_usesAnonFallback() {
        String h1 = AgentSpawnTool.deterministicHash(null, "worker", null);
        String h2 = AgentSpawnTool.deterministicHash(null, "worker", null);
        assertEquals(h1, h2);

        // "anon" seed differs from a real session id
        String h3 = AgentSpawnTool.deterministicHash("anon", "worker", null);
        // null → "anon" so these are the same
        assertEquals(h1, h3);
    }

    @Test
    void declarationPersistSession_defaultsFalse() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .build();
        assertFalse(decl.isPersistSession());
    }

    @Test
    void declarationPersistSession_canBeEnabled() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .persistSession(true)
                        .build();
        assertTrue(decl.isPersistSession());
    }

    @Test
    void declarationInheritParentPermissions_defaultsTrue() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .build();
        assertTrue(decl.isInheritParentPermissions());
    }

    @Test
    void declarationInheritParentPermissions_canBeDisabled() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .inheritParentPermissions(false)
                        .build();
        assertFalse(decl.isInheritParentPermissions());
    }
}
