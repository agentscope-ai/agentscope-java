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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grant-layer coverage of the harness skill chain: prompt visibility, per-call catalog,
 * and the load tool's hard gate (including its defense-in-depth path and byte-identical
 * denial wording).
 *
 * @author shanhongyu
 */
@DisplayName("Harness skill chain grant coverage")
class HarnessSkillMiddlewareGrantTest {

    /** In-memory repository stub serving a fixed skill list. */
    private static final class StubRepo implements AgentSkillRepository {
        private final List<AgentSkill> skills;

        StubRepo(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return false;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return null;
        }

        @Override
        public String getSource() {
            return "stub";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    /** A skill named {@code name} with skillId {@code name_custom}. */
    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "Skill " + name, "# " + name, null);
    }

    private static AgentSkill systemSkill(String name) {
        return AgentSkill.builder()
                .name(name)
                .description("Skill " + name)
                .skillContent("# " + name)
                .system(true)
                .build();
    }

    private static RuntimeContext rc(CapabilitySurface surface) {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        if (surface != null) {
            ctx.put(CapabilitySurface.class, surface);
        }
        return ctx;
    }

    private static HarnessSkillMiddleware middleware(List<AgentSkill> skills) {
        return new HarnessSkillMiddleware(List.of(new StubRepo(skills)), new Toolkit());
    }

    /** Grants exactly the alpha skill. */
    private static final CapabilitySurface ALPHA_ONLY =
            new CapabilitySurface(java.util.Set.of("alpha"), null, null);

    // ==================== prompt + catalog visibility ====================

    @Test
    @DisplayName("granted skill only: prompt and per-call catalog keep the granted subset")
    void grantedSkillOnlyInPromptAndCatalog() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(ALPHA_ONLY);

        String prompt = mw.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.contains("alpha"), "granted skill kept in the prompt");
        assertFalse(prompt.contains("beta"), "un-granted skill cut from the prompt");
        List<String> ids = mw.runtime().currentCatalog(ctx).ids();
        assertTrue(ids.contains("alpha"));
        assertFalse(ids.contains("beta"));
    }

    @Test
    @DisplayName("DENY_ALL: empty catalog, prompt unchanged")
    void denyAllYieldsEmptyCatalogAndUnchangedPrompt() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(CapabilitySurface.DENY_ALL);

        String prompt = mw.onSystemPrompt(null, ctx, "base").block();

        assertEquals("base", prompt);
        assertTrue(mw.runtime().currentCatalog(ctx).ids().isEmpty());
    }

    @Test
    @DisplayName("system skill survives a restrictive surface")
    void systemSkillSurvivesRestrictiveSurface() {
        HarnessSkillMiddleware mw = middleware(List.of(systemSkill("gamma"), skill("beta")));
        RuntimeContext ctx = rc(new CapabilitySurface(java.util.Set.of(), null, null));

        String prompt = mw.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.contains("gamma"), "system skill kept");
        assertFalse(prompt.contains("beta"), "non-system skill cut");
        assertTrue(mw.runtime().currentCatalog(ctx).ids().contains("gamma"));
    }

    @Test
    @DisplayName("missing surface: no filtering (trusted direct middleware use)")
    void missingSurfaceLeavesAllSkillsVisible() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(null);

        String prompt = mw.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.contains("alpha") && prompt.contains("beta"));
        assertEquals(2, mw.runtime().currentCatalog(ctx).ids().size());
    }

    // ==================== load tool gate ====================

    /** Loads SKILL.md through the middleware's load tool and returns the result text. */
    private static String load(HarnessSkillMiddleware mw, RuntimeContext ctx, String skillId) {
        AgentTool loadTool = mw.runtime().loadTool();
        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-" + skillId)
                        .name(loadTool.getName())
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(use).input(input).runtimeContext(ctx).build();
        ToolResultBlock result = loadTool.callAsync(param).block();
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    @DisplayName("filtered catalog denies the un-granted skill; granted skill loads")
    void loadToolRespectsFilteredCatalog() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(ALPHA_ONLY);
        mw.onSystemPrompt(null, ctx, "base").block();

        assertTrue(load(mw, ctx, "alpha").contains("Successfully loaded skill"));
        assertFalse(load(mw, ctx, "beta").contains("Successfully loaded skill"));
    }

    @Test
    @DisplayName("denial wording is byte-identical for unknown and un-granted skills")
    void loadToolDenialWordingIsIndistinguishable() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(ALPHA_ONLY);
        mw.onSystemPrompt(null, ctx, "base").block();

        String ungranted = load(mw, ctx, "beta");
        String unknown = load(mw, ctx, "nope");
        assertEquals("Error: Skill not found: 'beta'. Check the available skills list.", ungranted);
        assertEquals(
                ungranted.replace("beta", "nope"),
                unknown,
                "unknown and un-granted denials must be indistinguishable");
    }

    @Test
    @DisplayName("defense in depth: the gate denies even when an unfiltered catalog is installed")
    void loadToolGateCoversUnfilteredCatalogInstall() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        // No surface during the prompt pass: the full catalog is installed (trusted direct).
        RuntimeContext ctx = rc(null);
        mw.onSystemPrompt(null, ctx, "base").block();
        // A surface appearing afterwards (alternate installer / ordering) must still gate.
        ctx.put(CapabilitySurface.class, ALPHA_ONLY);

        assertTrue(load(mw, ctx, "alpha").contains("Successfully loaded skill"));
        assertEquals(
                "Error: Skill not found: 'beta'. Check the available skills list.",
                load(mw, ctx, "beta"));
    }

    @Test
    @DisplayName("system skill loads under a restrictive surface; unrestricted without a surface")
    void loadToolSystemSkillAndMissingSurface() {
        HarnessSkillMiddleware mw =
                middleware(List.of(systemSkill("gamma"), skill("beta"), skill("alpha")));
        RuntimeContext restricted =
                rc(new CapabilitySurface(java.util.Set.of("alpha"), null, null));
        mw.onSystemPrompt(null, restricted, "base").block();
        assertTrue(load(mw, restricted, "gamma").contains("Successfully loaded skill"));

        RuntimeContext direct = rc(null);
        mw.onSystemPrompt(null, direct, "base").block();
        assertTrue(load(mw, direct, "beta").contains("Successfully loaded skill"));
    }
}
