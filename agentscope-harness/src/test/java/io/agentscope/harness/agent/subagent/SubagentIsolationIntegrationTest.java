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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end Phase B-0 isolation: spawn the same {@code SubagentDeclaration} under different
 * parent {@link RuntimeContext}s and verify each child {@link ReActAgent} ends up with a
 * distinct {@link io.agentscope.core.state.SessionKey}. Because {@code Session.save}/{@code get}
 * already partition by SessionKey, distinct keys are sufficient to guarantee state isolation
 * across all configured Session backends.
 */
class SubagentIsolationIntegrationTest {

    @TempDir Path workspace;

    private List<SubagentEntry> buildEntries() throws Exception {
        Files.createDirectories(workspace);
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Test worker")
                        .inlineAgentsBody("You are a worker.")
                        .build();
        return HarnessAgent.builder()
                .model(new MockModel("ok"))
                .workspace(workspace)
                .subagent(decl)
                .buildSubagentEntries(workspace);
    }

    private static SubagentEntry workerEntry(List<SubagentEntry> entries) {
        return entries.stream().filter(e -> "worker".equals(e.name())).findFirst().orElseThrow();
    }

    @Test
    void differentParentSessions_yieldDistinctSessionKeys() throws Exception {
        SubagentEntry entry = workerEntry(buildEntries());

        HarnessAgent child1 =
                (HarnessAgent)
                        entry.factory().create(RuntimeContext.builder().sessionId("s1").build());
        HarnessAgent child2 =
                (HarnessAgent)
                        entry.factory().create(RuntimeContext.builder().sessionId("s2").build());

        assertNotEquals(
                child1.getSessionKey().toIdentifier(),
                child2.getSessionKey().toIdentifier(),
                "different parent sessions must produce different child SessionKeys");
        assertEquals("worker@s1", child1.getSessionKey().toIdentifier());
        assertEquals("worker@s2", child2.getSessionKey().toIdentifier());
    }

    @Test
    void differentParentUsers_yieldDistinctSessionKeys() throws Exception {
        SubagentEntry entry = workerEntry(buildEntries());

        HarnessAgent alice =
                (HarnessAgent)
                        entry.factory()
                                .create(
                                        RuntimeContext.builder()
                                                .sessionId("s1")
                                                .userId("alice")
                                                .build());
        HarnessAgent bob =
                (HarnessAgent)
                        entry.factory()
                                .create(
                                        RuntimeContext.builder()
                                                .sessionId("s1")
                                                .userId("bob")
                                                .build());

        assertEquals("worker@s1#alice", alice.getSessionKey().toIdentifier());
        assertEquals("worker@s1#bob", bob.getSessionKey().toIdentifier());
        assertNotEquals(alice.getSessionKey(), bob.getSessionKey());
    }

    @Test
    void sameParent_yieldsIdenticalSessionKey() throws Exception {
        SubagentEntry entry = workerEntry(buildEntries());
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();

        HarnessAgent first = (HarnessAgent) entry.factory().create(rc);
        HarnessAgent second = (HarnessAgent) entry.factory().create(rc);

        // Same (sid, uid) → same SessionKey → both children load/save the same persisted state.
        assertEquals(first.getSessionKey().toIdentifier(), second.getSessionKey().toIdentifier());
    }

    @Test
    void emptyParentRuntimeContext_legacyBucket() throws Exception {
        SubagentEntry entry = workerEntry(buildEntries());

        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());

        // Single-tenant demo: rc empty → falls back to "worker" only, matching pre-B-0 behaviour.
        assertEquals("worker", child.getSessionKey().toIdentifier());
    }

    @Test
    void sameSessionIdDifferentUsers_isolated_endToEnd() throws Exception {
        // Sanity: two children with different uids do not collide on the persisted Session.
        // We don't need to exercise the full Session.save round-trip — distinct SessionKeys are
        // sufficient: every Session implementation already partitions by key. We just verify
        // the two keys differ AND aren't accidentally equal as strings (defence vs sanitiser
        // quirks like uid containing the literal "@").
        SubagentEntry entry = workerEntry(buildEntries());

        HarnessAgent a =
                (HarnessAgent)
                        entry.factory()
                                .create(
                                        RuntimeContext.builder()
                                                .sessionId("s1")
                                                .userId("user@evil")
                                                .build());
        HarnessAgent b =
                (HarnessAgent)
                        entry.factory()
                                .create(
                                        RuntimeContext.builder()
                                                .sessionId("s1")
                                                .userId("user")
                                                .build());

        // sanitiser only strips slashes/spaces/controls; '@' is allowed but lives only in the
        // user segment so the prefix `worker@s1#` already disambiguates these.
        assertNotEquals(a.getSessionKey().toIdentifier(), b.getSessionKey().toIdentifier());
        assertTrue(a.getSessionKey().toIdentifier().endsWith("#user@evil"));
        assertTrue(b.getSessionKey().toIdentifier().endsWith("#user"));
    }
}
