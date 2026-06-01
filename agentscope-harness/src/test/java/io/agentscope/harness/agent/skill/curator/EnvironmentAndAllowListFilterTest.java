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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class EnvironmentAndAllowListFilterTest {

    @TempDir Path workspace;
    private SkillUsageStore store;

    @BeforeEach
    void setUp() {
        store = new SkillUsageStore(new LocalFilesystem(workspace));
    }

    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "desc " + name, "# " + name, null);
    }

    @Test
    void environmentFilter_excludesDraftFromProd() {
        store.markAgentDraft("draft-x", null); // environments=[draft]
        store.markAgentCreated("prod-y", "auto", List.of("prod"));

        EnvironmentFilter f = new EnvironmentFilter("prod", store);
        List<AgentSkill> out =
                f.filter(List.of(skill("draft-x"), skill("prod-y")), RuntimeContext.empty());

        assertEquals(1, out.size());
        assertEquals("prod-y", out.get(0).getName());
    }

    @Test
    void environmentFilter_passesThroughExternalSkills() {
        // No sidecar entry → external skill, must always pass through env filter.
        EnvironmentFilter f = new EnvironmentFilter("prod", store);
        AgentSkill external = skill("hand-crafted");
        List<AgentSkill> out = f.filter(List.of(external), RuntimeContext.empty());
        assertEquals(1, out.size());
    }

    @Test
    void allowListFilter_admitsOnlyListedAgentCreated() {
        store.markAgentCreated("yes", "auto", List.of("prod"));
        store.markAgentCreated("no", "auto", List.of("prod"));

        AllowListFilter f = new AllowListFilter(Set.of("yes"), store);
        List<AgentSkill> out = f.filter(List.of(skill("yes"), skill("no")), RuntimeContext.empty());

        assertEquals(1, out.size());
        assertEquals("yes", out.get(0).getName());
    }

    @Test
    void allowListFilter_passesThroughExternalSkills() {
        // External skill — allow-list shouldn't apply.
        AllowListFilter f = new AllowListFilter(Set.of("nothing"), store);
        AgentSkill external = skill("hand-crafted");
        assertEquals(1, f.filter(List.of(external), RuntimeContext.empty()).size());
    }

    @Test
    void compositeFilter_chainsAndShortCircuits() {
        store.markAgentCreated("a", "auto", List.of("prod"));
        store.markAgentCreated("b", "auto", List.of("prod"));

        SkillVisibilityFilter env = new EnvironmentFilter("prod", store);
        SkillVisibilityFilter allow = new AllowListFilter(Set.of("a"), store);
        SkillVisibilityFilter chain = new CompositeFilter(List.of(env, allow));

        List<AgentSkill> out =
                chain.filter(List.of(skill("a"), skill("b")), RuntimeContext.empty());
        assertTrue(out.size() == 1 && out.get(0).getName().equals("a"));
    }
}
