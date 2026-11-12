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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Decision-matrix tests for {@link CapabilitySurface}.
 *
 * @author shanhongyu
 */
@Tag("unit")
@DisplayName("CapabilitySurface grant decision matrix")
class CapabilitySurfaceTest {

    /** Minimal non-system AgentTool stub. */
    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "stub";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
    }

    /** Minimal system AgentTool stub (isSystem → true). */
    private static AgentTool systemTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "system stub";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public boolean isSystem() {
                return true;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
    }

    /** Reverse lookup stub: no tool belongs to any group. */
    private static final Function<String, Set<String>> NO_GROUPS = name -> Set.of();

    /** Reverse lookup stub: t1 and t2 belong to group g1. */
    private static final Function<String, Set<String>> GROUPS_OF_G1 =
            name -> "t1".equals(name) || "t2".equals(name) ? Set.of("g1") : Set.of();

    // ==================== tri-state semantics ====================

    @Test
    @DisplayName("skill dimension: all-null = no trimming; unset or empty = no skill grants")
    void skillDimensionThreeStates() {
        assertTrue(new CapabilitySurface(null, null, null).allowsSkill("anything"));
        assertFalse(
                new CapabilitySurface(null, Set.of("g1"), null).allowsSkill("anything"),
                "an unset skill dimension grants no skills once any dimension is set");
        assertFalse(new CapabilitySurface(Set.of(), null, null).allowsSkill("anything"));
        CapabilitySurface surface = new CapabilitySurface(Set.of("s1"), null, null);
        assertTrue(surface.allowsSkill("s1"));
        assertFalse(surface.allowsSkill("s2"));
    }

    @Test
    @DisplayName("skill object entry: id delegation, system bypass, and the null guard")
    void skillObjectEntryMatrix() {
        // Id delegation: builder default source "custom" → skillName "s1".
        AgentSkill s1 = AgentSkill.builder().name("s1").description("d").skillContent("c").build();
        AgentSkill s2 = AgentSkill.builder().name("s2").description("d").skillContent("c").build();
        CapabilitySurface surface = new CapabilitySurface(Set.of("s1"), Set.of(), Set.of());
        assertTrue(surface.allowsSkill(s1));
        assertFalse(surface.allowsSkill(s2));
        assertTrue(CapabilitySurface.ALLOW_ALL.allowsSkill(s2));

        // System bypass survives DENY_ALL, mirroring system tools; the id-only fallback stays
        // unaware of the mark.
        AgentSkill systemSkill =
                AgentSkill.builder()
                        .name("sys")
                        .description("d")
                        .skillContent("c")
                        .system(true)
                        .build();
        assertTrue(CapabilitySurface.DENY_ALL.allowsSkill(systemSkill));
        assertFalse(CapabilitySurface.DENY_ALL.allowsSkill(systemSkill.getName()));

        // Null guard: a registry miss is never granted, not even on ALLOW_ALL.
        AgentSkill registryMiss = null;
        assertFalse(CapabilitySurface.ALLOW_ALL.allowsSkill(registryMiss));
    }

    @Test
    @DisplayName("skill grant entries use the skill name; ids resolve through the name part")
    void skillGrantEntryVocabulary() {
        AgentSkill alphaCustom =
                AgentSkill.builder()
                        .name("alpha")
                        .description("d")
                        .skillContent("c")
                        .build(); // source defaults to "custom" → id "alpha_custom"
        AgentSkill alphaMarket =
                AgentSkill.builder()
                        .name("alpha")
                        .description("d")
                        .skillContent("c")
                        .source("market")
                        .build();

        // The grant vocabulary is the name: one entry grants every copy of that name.
        CapabilitySurface byName = new CapabilitySurface(Set.of("alpha"), Set.of(), Set.of());
        assertTrue(byName.allowsSkill(alphaCustom));
        assertTrue(byName.allowsSkill(alphaMarket));

        // String fallback: the standard name charset forbids underscores, so the name part of
        // an id is everything before the first underscore — the check is deterministic.
        assertTrue(byName.allowsSkill(alphaCustom.getSkillId()));
        assertTrue(byName.allowsSkill(alphaMarket.getSkillId()));
        assertFalse(byName.allowsSkill("beta_custom"));
    }

    @Test
    @DisplayName("full-skillId entries: program-level compatibility, exact-copy match")
    void skillIdEntryCompatibility() {
        AgentSkill alphaCustom =
                AgentSkill.builder()
                        .name("alpha")
                        .description("d")
                        .skillContent("c")
                        .build(); // source defaults to "custom" → id "alpha_custom"
        AgentSkill alphaMarket =
                AgentSkill.builder()
                        .name("alpha")
                        .description("d")
                        .skillContent("c")
                        .source("market")
                        .build(); // id "alpha_market"

        // Exact-copy binding: the id entry grants only that copy, not the name.
        CapabilitySurface byId = new CapabilitySurface(Set.of("alpha_market"), Set.of(), Set.of());
        assertTrue(byId.allowsSkill(alphaMarket));
        assertFalse(byId.allowsSkill(alphaCustom));

        // Companion group path: the id entry authorizes its own convention group (and only
        // that one) — no "skill granted but tools denied" gap.
        assertTrue(byId.allowsToolGroup("alpha_market_skill_tools"));
        assertFalse(byId.allowsToolGroup("alpha_custom_skill_tools"));
        assertTrue(byId.allowsTool(tool("t1"), Set.of("alpha_market_skill_tools")));
        assertFalse(byId.allowsTool(tool("t1"), Set.of("alpha_custom_skill_tools")));

        // The name remains the stable recommended form: it covers every copy and every
        // source's convention group.
        CapabilitySurface byName = new CapabilitySurface(Set.of("alpha"), Set.of(), Set.of());
        assertTrue(byName.allowsSkill(alphaCustom));
        assertTrue(byName.allowsSkill(alphaMarket));
        assertTrue(byName.allowsToolGroup("alpha_market_skill_tools"));
        assertTrue(byName.allowsToolGroup("alpha_custom_skill_tools"));
    }

    @Test
    @DisplayName("toolGroup dimension: all-null = no trimming; unset or empty = no group grants")
    void toolGroupDimensionThreeStates() {
        assertTrue(new CapabilitySurface(null, null, null).allowsToolGroup("anyGroup"));
        assertFalse(
                new CapabilitySurface(null, null, Set.of("t1")).allowsToolGroup("anyGroup"),
                "an unset group dimension grants no groups once any dimension is set");
        assertFalse(
                new CapabilitySurface(null, Set.of(), null).allowsToolGroup("anyGroup"),
                "empty group set denies every group (skill path only rescues derived groups)");
        CapabilitySurface surface = new CapabilitySurface(null, Set.of("g1"), null);
        assertTrue(surface.allowsToolGroup("g1"));
        assertFalse(surface.allowsToolGroup("g2"));
    }

    @Test
    @DisplayName("tool dimension: all-null = no trimming; an explicit whitelist trims the rest")
    void toolDimensionThreeStates() {
        assertTrue(
                new CapabilitySurface(null, null, null).allowsTool(tool("t"), Set.of("g1")),
                "an all-null surface allows every (non-system) tool");
        CapabilitySurface surface = new CapabilitySurface(null, null, Set.of("t1"));
        assertTrue(surface.allowsTool(tool("t1"), Set.of()));
        assertFalse(
                surface.allowsTool(tool("t2"), Set.of("g1")),
                "an unset group dimension does not rescue tools once any dimension is set");
        assertFalse(surface.allowsTool(tool("t2"), Set.of()));
    }

    // ==================== allowsTool paths ====================

    @Test
    @DisplayName("per-name authorization ignores grouping")
    void perNamePath() {
        CapabilitySurface surface = new CapabilitySurface(null, Set.of("otherGroup"), Set.of("t1"));
        assertTrue(surface.allowsTool(tool("t1"), Set.of("g1")));
    }

    @Test
    @DisplayName("group whitelist path")
    void groupPath() {
        CapabilitySurface surface = new CapabilitySurface(null, Set.of("g1"), Set.of("unrelated"));
        assertTrue(surface.allowsTool(tool("t1"), Set.of("g1", "g2")));
        assertFalse(surface.allowsTool(tool("t1"), Set.of("g2")));
    }

    @Test
    @DisplayName("skill path — granted name authorizes the skill tool group of any source copy")
    void skillGroupPath() {
        // Group dimension restricted (empty) so only the skill path can rescue the tool.
        CapabilitySurface surface = new CapabilitySurface(Set.of("s1"), Set.of(), Set.of());
        // Convention group = skillId + "_skill_tools" = name + "_" + source + "_skill_tools".
        assertTrue(surface.allowsTool(tool("t1"), Set.of("s1_custom_skill_tools")));
        assertTrue(surface.allowsTool(tool("t1"), Set.of("s1_market_skill_tools")));
        assertFalse(surface.allowsTool(tool("t1"), Set.of("s2_custom_skill_tools")));
    }

    @Test
    @DisplayName(
            "skill path — backward derivation: group name → name part → grant entry, source-free")
    void skillGroupPathBackwardDerivation() {
        // The derivation peels the "_skill_tools" suffix and takes everything before the first
        // underscore as the name (the standard charset forbids underscores in names), so the
        // source part is irrelevant — deterministic regardless of the source's charset.
        CapabilitySurface surface = new CapabilitySurface(Set.of("s1"), Set.of(), Set.of());
        assertTrue(surface.allowsToolGroup("s1_custom_skill_tools"));
        assertTrue(surface.allowsToolGroup("s1_my_repo_v2_skill_tools"));

        // Not granted names and non-convention groups stay denied. (A group whose idPart is
        // exactly the entry — e.g. "s1_skill_tools" for entry "s1" — is matched by the
        // exact-id clause; main-semantics ids always carry a source, so this form cannot
        // occur for a real skill, and matching it is harmless.)
        assertFalse(surface.allowsToolGroup("s2_custom_skill_tools"));
        assertFalse(surface.allowsToolGroup("s1_custom_tools"));
    }

    // ==================== trimming intent ====================

    @Test
    @DisplayName("any explicit dimension establishes trimming; only an all-null surface is open")
    void anyExplicitDimensionEstablishesTrimming() {
        // Group whitelist with unset skill/tool dimensions: only granted members pass — unset
        // dimensions are "not set", not "anything goes".
        CapabilitySurface surface = new CapabilitySurface(null, Set.of("g1"), null);
        assertTrue(surface.allowsTool(tool("t1"), Set.of("g1")));
        assertFalse(surface.allowsTool(tool("tx"), Set.of("g2")));
        assertFalse(surface.allowsTool(tool("ungrouped"), Set.of()));

        // null (not set) and Set.of() (explicitly empty) are distinct values with the same
        // grant outcome once any dimension is set.
        CapabilitySurface withNulls = new CapabilitySurface(null, Set.of("g1"), null);
        CapabilitySurface withEmpties = new CapabilitySurface(Set.of(), Set.of("g1"), Set.of());
        assertNull(withNulls.allowedSkills());
        assertEquals(Set.of(), withEmpties.allowedSkills());
        assertFalse(withNulls.equals(withEmpties));
        assertTrue(withNulls.allowsTool(tool("t1"), Set.of("g1")));
        assertTrue(withEmpties.allowsTool(tool("t1"), Set.of("g1")));
    }

    // ==================== no amplification ====================

    @Test
    @DisplayName("no amplification: name-authorizing one tool does not free its group siblings")
    void perNameAuthorizationDoesNotAmplify() {
        CapabilitySurface surface = new CapabilitySurface(null, Set.of(), Set.of("t1"));
        assertTrue(surface.allowsTool(tool("t1"), Set.of("g1")));
        assertFalse(surface.allowsTool(tool("t2"), Set.of("g1")));
    }

    // ==================== allowsToolGroupActivation ====================

    @Test
    @DisplayName(
            "group activation: group path, skill path, per-name reachability, and the null guard")
    void allowsToolGroupActivationMatrix() {
        // Group whitelist path.
        assertTrue(
                new CapabilitySurface(null, Set.of("g1"), Set.of())
                        .allowsToolGroupActivation("g1", GROUPS_OF_G1));
        // Skill path: the skill tool-group name (name + "_" + source + "_skill_tools") is
        // activatable via backward derivation from the group name.
        assertTrue(
                new CapabilitySurface(Set.of("s1"), Set.of(), Set.of())
                        .allowsToolGroupActivation("s1_custom_skill_tools", NO_GROUPS));
        // Reachability: some per-name granted member tool belongs to the group.
        assertTrue(
                new CapabilitySurface(null, Set.of(), Set.of("t1"))
                        .allowsToolGroupActivation("g1", GROUPS_OF_G1));
        // Null guard: no NPE and no reachability when allowedTools is null.
        assertFalse(
                new CapabilitySurface(null, Set.of("gOther"), null)
                        .allowsToolGroupActivation("g1", GROUPS_OF_G1));
    }

    // ==================== defensive copy ====================

    @Test
    @DisplayName(
            "defensive copy: mutating the input sets after construction does not affect decisions")
    void defensiveCopy() {
        Set<String> skills = new HashSet<>();
        skills.add("s1");
        Set<String> groups = new HashSet<>();
        groups.add("g1");
        Set<String> tools = new HashSet<>();
        tools.add("t1");
        CapabilitySurface surface = new CapabilitySurface(skills, groups, tools);

        skills.add("s2");
        groups.add("g2");
        tools.add("t2");

        assertFalse(surface.allowsSkill("s2"));
        assertFalse(surface.allowsToolGroup("g2"));
        assertFalse(surface.allowsTool(tool("t2"), Set.of()));
        assertEquals(Set.of("s1"), surface.allowedSkills());
        assertEquals(Set.of("g1"), surface.allowedToolGroups());
        assertEquals(Set.of("t1"), surface.allowedTools());
    }

    // ==================== sentinels ====================

    @Test
    @DisplayName(
            "sentinels: all-null = ALLOW_ALL (no trimming); all-empty = DENY_ALL (system only)")
    void sentinels() {
        assertNull(CapabilitySurface.ALLOW_ALL.allowedSkills());
        assertNull(CapabilitySurface.ALLOW_ALL.allowedToolGroups());
        assertNull(CapabilitySurface.ALLOW_ALL.allowedTools());
        assertEquals(CapabilitySurface.ALLOW_ALL, new CapabilitySurface(null, null, null));

        assertEquals(Set.of(), CapabilitySurface.DENY_ALL.allowedSkills());
        assertEquals(Set.of(), CapabilitySurface.DENY_ALL.allowedToolGroups());
        assertEquals(Set.of(), CapabilitySurface.DENY_ALL.allowedTools());
        assertFalse(CapabilitySurface.DENY_ALL.allowsSkill("any"));
        assertFalse(CapabilitySurface.DENY_ALL.allowsToolGroup("any"));
        assertFalse(
                CapabilitySurface.DENY_ALL.allowsTool(tool("any"), Set.of("g1")),
                "all paths miss → denied");
        assertTrue(
                CapabilitySurface.DENY_ALL.allowsTool(systemTool("any"), Set.of()),
                "system tools bypass grant trimming even under DENY_ALL");
        assertEquals(
                new CapabilitySurface(Set.of("s"), Set.of("g"), Set.of("t")),
                new CapabilitySurface(Set.of("s"), Set.of("g"), Set.of("t")));
    }
}
