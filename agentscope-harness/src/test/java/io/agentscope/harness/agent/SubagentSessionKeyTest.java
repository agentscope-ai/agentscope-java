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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.SessionKey;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.Test;

/**
 * Phase B-0 — composed child {@code SessionKey} algorithm. Lives in
 * {@code io.agentscope.harness.agent} so it can reach the package-private
 * {@link HarnessAgentBuilderSupport#deriveChildSessionKey}. The derivation works the same way
 * regardless of which {@link io.agentscope.core.session.Session} backend (Workspace, Redis,
 * InMemory, custom) the agent uses.
 */
class SubagentSessionKeyTest {

    private static SubagentDeclaration decl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("test")
                .inlineAgentsBody("body")
                .build();
    }

    private static SubagentDeclaration sharedDecl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("test")
                .inlineAgentsBody("body")
                .workspaceMode(WorkspaceMode.SHARED)
                .build();
    }

    private static String id(SessionKey k) {
        return k.toIdentifier();
    }

    @Test
    void nullParentRc_legacyBucket() {
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), null);
        assertEquals("worker", id(k));
    }

    @Test
    void emptyParentRc_legacyBucket() {
        SessionKey k =
                HarnessAgentBuilderSupport.deriveChildSessionKey(
                        decl("worker"), RuntimeContext.empty());
        assertEquals("worker", id(k));
    }

    @Test
    void onlySessionId_appendsAtSegment() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), rc);
        assertEquals("worker@s1", id(k));
    }

    @Test
    void onlyUserId_appendsHashSegment() {
        RuntimeContext rc = RuntimeContext.builder().userId("alice").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), rc);
        assertEquals("worker#alice", id(k));
    }

    @Test
    void bothIds_composedInOrder() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), rc);
        // Order is fixed: declName then @sid then #uid — keeps the format greppable.
        assertEquals("worker@s1#alice", id(k));
    }

    @Test
    void sharedMode_alwaysLegacyBucket() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(sharedDecl("worker"), rc);
        // SHARED subagents intentionally share the parent's bucket; do NOT segment by parent rc.
        assertEquals("worker", id(k));
    }

    @Test
    void slashesInIds_areSanitised() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("a/b/c").userId("dom\\user").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), rc);
        assertEquals("worker@a_b_c#dom_user", id(k));
    }

    @Test
    void blankSessionId_treatedAsAbsent() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("   ").userId("alice").build();
        SessionKey k = HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), rc);
        assertEquals("worker#alice", id(k));
    }

    @Test
    void differentParents_produceDifferentKeys() {
        RuntimeContext alice = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().sessionId("s1").userId("bob").build();
        assertNotEquals(
                id(HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), alice)),
                id(HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), bob)));

        RuntimeContext sessA = RuntimeContext.builder().sessionId("A").build();
        RuntimeContext sessB = RuntimeContext.builder().sessionId("B").build();
        assertNotEquals(
                id(HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), sessA)),
                id(HarnessAgentBuilderSupport.deriveChildSessionKey(decl("worker"), sessB)));
    }
}
