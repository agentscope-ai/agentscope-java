/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.skill.curator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class CanaryFilterTest {

    @TempDir Path workspace;
    private SkillUsageStore store;

    @BeforeEach
    void setUp() {
        store = new SkillUsageStore(new LocalFilesystem(workspace));
    }

    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "desc " + name, "# " + name, null);
    }

    private static RuntimeContext ctxFor(String userId) {
        return RuntimeContext.builder().userId(userId).build();
    }

    @Test
    void agentCreatedSkill_canaryAtTenPercent_admitsRoughlyTenPercent() {
        store.markAgentCreated("rolled", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(10, store);

        AgentSkill s = skill("rolled");
        int admitted = 0;
        int total = 200;
        for (int i = 0; i < total; i++) {
            String user = "user-" + i;
            List<AgentSkill> out = f.filter(List.of(s), ctxFor(user));
            if (!out.isEmpty()) {
                admitted++;
            }
        }
        // Allow a generous range: 10% of 200 = 20; tolerate 5..40.
        assertTrue(
                admitted >= 5 && admitted <= 40,
                "expected ~10% admit; got " + admitted + "/" + total);
    }

    @Test
    void preExistingSkill_passesThroughAt100Percent() {
        // Plant a skill with no sidecar entry — must be treated as user-authored / external.
        // CanaryFilter should NOT gate it.
        AgentSkill s = skill("hand-crafted");
        CanaryFilter f = new CanaryFilter(0, store); // 0% would block agent-created skills
        List<AgentSkill> admitted = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            admitted.addAll(f.filter(List.of(s), ctxFor("user-" + i)));
        }
        assertEquals(
                50, admitted.size(), "external skill must always pass through (createdBy=null)");
    }

    @Test
    void agentDraft_isAlsoTreatedAsExternal_byCanaryFilter() {
        // Drafts have createdBy="agent-draft" (NOT "agent"). Canary filter only gates
        // "agent" — drafts effectively pass through this filter, but EnvironmentFilter
        // is what actually hides drafts from prompts. Here we just confirm CanaryFilter
        // doesn't accidentally block them.
        store.markAgentDraft("draft-x", null);
        CanaryFilter f = new CanaryFilter(0, store);
        AgentSkill s = skill("draft-x");
        assertEquals(1, f.filter(List.of(s), ctxFor("user-7")).size());
    }

    @Test
    void zeroPercent_admitsNothingAgentCreated() {
        store.markAgentCreated("none", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(0, store);
        AgentSkill s = skill("none");
        for (int i = 0; i < 20; i++) {
            assertTrue(
                    f.filter(List.of(s), ctxFor("user-" + i)).isEmpty(), "0% must admit nothing");
        }
    }

    @Test
    void hundredPercent_admitsEverythingAgentCreated() {
        store.markAgentCreated("yes", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(100, store);
        AgentSkill s = skill("yes");
        for (int i = 0; i < 20; i++) {
            assertEquals(1, f.filter(List.of(s), ctxFor("user-" + i)).size());
        }
    }

    @Test
    void rampUpDays_acceptedButNotImplemented() {
        // Acceptance: rampUpDays parameter compiles + getter works. Behaviour is currently
        // identical to a static percentage; full implementation is a future milestone.
        CanaryFilter f = new CanaryFilter(50, 7, store);
        assertEquals(7, f.getRampUpDays());
    }
}
