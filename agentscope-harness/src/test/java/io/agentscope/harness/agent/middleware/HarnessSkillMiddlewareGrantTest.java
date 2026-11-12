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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
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
        return middleware(skills, new Toolkit());
    }

    private static HarnessSkillMiddleware middleware(List<AgentSkill> skills, Toolkit toolkit) {
        return new HarnessSkillMiddleware(List.of(new StubRepo(skills)), toolkit);
    }

    /**
     * The rendered skills block as registered by {@code onSystemPrompt}: a tail runtime material
     * (keyed {@code harness:skills}), not part of the system prompt.
     */
    private static String skillsMaterial(RuntimeContext ctx) {
        WorkspaceContextMaterials materials = ctx.get(WorkspaceContextMaterials.class);
        assertNotNull(materials);
        return materials.items().stream()
                .filter(item -> "harness:skills".equals(item.sourceId()))
                .findFirst()
                .orElseThrow()
                .content();
    }

    /** Grants exactly the alpha skill. */
    private static final CapabilitySurface ALPHA_ONLY =
            new CapabilitySurface(java.util.Set.of("alpha"), null, null);

    // ==================== prompt + catalog visibility ====================

    @Test
    @DisplayName("granted skill only: skills material and per-call catalog keep the granted subset")
    void grantedSkillOnlyInPromptAndCatalog() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(ALPHA_ONLY);

        String prompt = mw.onSystemPrompt(null, ctx, "base").block();

        assertEquals("base", prompt, "system prompt stays untouched");
        String block = skillsMaterial(ctx);
        assertTrue(block.contains("alpha"), "granted skill kept in the skills material");
        assertFalse(block.contains("beta"), "un-granted skill cut from the skills material");
        List<String> ids = mw.runtime().currentCatalog(ctx).ids();
        assertTrue(ids.contains("alpha_custom"));
        assertFalse(ids.contains("beta_custom"));
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

        mw.onSystemPrompt(null, ctx, "base").block();

        String block = skillsMaterial(ctx);
        assertTrue(block.contains("gamma"), "system skill kept");
        assertFalse(block.contains("beta"), "non-system skill cut");
        assertTrue(mw.runtime().currentCatalog(ctx).ids().contains("gamma_custom"));
    }

    @Test
    @DisplayName("missing surface: no filtering (trusted direct middleware use)")
    void missingSurfaceLeavesAllSkillsVisible() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(null);

        mw.onSystemPrompt(null, ctx, "base").block();

        String block = skillsMaterial(ctx);
        assertTrue(block.contains("alpha") && block.contains("beta"));
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

        assertTrue(load(mw, ctx, "alpha_custom").contains("Successfully loaded skill"));
        assertFalse(load(mw, ctx, "beta_custom").contains("Successfully loaded skill"));
    }

    @Test
    @DisplayName("denial wording is byte-identical for unknown and un-granted skills")
    void loadToolDenialWordingIsIndistinguishable() {
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha"), skill("beta")));
        RuntimeContext ctx = rc(ALPHA_ONLY);
        mw.onSystemPrompt(null, ctx, "base").block();

        String ungranted = load(mw, ctx, "beta_custom");
        String unknown = load(mw, ctx, "nope_custom");
        assertEquals(
                "Error: Skill not found: 'beta_custom'. Check the available skills list.",
                ungranted);
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

        assertTrue(load(mw, ctx, "alpha_custom").contains("Successfully loaded skill"));
        assertEquals(
                "Error: Skill not found: 'beta_custom'. Check the available skills list.",
                load(mw, ctx, "beta_custom"));
    }

    @Test
    @DisplayName("system skill loads under a restrictive surface; unrestricted without a surface")
    void loadToolSystemSkillAndMissingSurface() {
        HarnessSkillMiddleware mw =
                middleware(List.of(systemSkill("gamma"), skill("beta"), skill("alpha")));
        RuntimeContext restricted =
                rc(new CapabilitySurface(java.util.Set.of("alpha"), null, null));
        mw.onSystemPrompt(null, restricted, "base").block();
        assertTrue(load(mw, restricted, "gamma_custom").contains("Successfully loaded skill"));

        RuntimeContext direct = rc(null);
        mw.onSystemPrompt(null, direct, "base").block();
        assertTrue(load(mw, direct, "beta_custom").contains("Successfully loaded skill"));
    }

    @Test
    @DisplayName("load tool is a system capability: survives grant-trimmed schemas under DENY_ALL")
    void loadToolSurvivesGrantTrimmedSchemas() {
        Toolkit toolkit = new Toolkit();
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha")), toolkit);
        mw.onSystemPrompt(null, rc(CapabilitySurface.DENY_ALL), "base").block();

        // Regression: the harness loader must be exempt from tool-grant trimming (aligned
        // with the core load tool), otherwise a surface granting skills but not the loader
        // per-name makes every granted skill unloadable.
        assertTrue(
                mw.runtime().loadTool().isSystem(),
                "load_skill_through_path must be system-marked");
        boolean visible =
                toolkit
                        .getToolSchemas(
                                List.of(), ToolRequestConfig.NONE, CapabilitySurface.DENY_ALL)
                        .stream()
                        .anyMatch(s -> "load_skill_through_path".equals(s.getName()));
        assertTrue(
                visible,
                "load_skill_through_path must stay in model-facing schemas under DENY_ALL");
    }

    @Test
    @DisplayName("SKILL.md response reports the source; a null source never renders as 'null'")
    void loadSkillMarkdownReportsSource() {
        // Under the standard semantics a skill always carries a source ("custom" by default),
        // so the line can never degenerate to "Source: null"; the conditional rendering keeps
        // that guarantee even if a null ever slips through.
        HarnessSkillMiddleware mw = middleware(List.of(skill("alpha")));
        RuntimeContext ctx = rc(null);
        mw.onSystemPrompt(null, ctx, "base").block();

        assertTrue(
                load(mw, ctx, "alpha_custom").contains("Source: custom"),
                "the default source is reported");

        AgentSkill sourced =
                AgentSkill.builder()
                        .name("sourced")
                        .description("Sourced skill")
                        .skillContent("# sourced")
                        .source("repo1")
                        .build();
        HarnessSkillMiddleware sourcedMw = middleware(List.of(sourced));
        RuntimeContext sourcedCtx = rc(null);
        sourcedMw.onSystemPrompt(null, sourcedCtx, "base").block();
        assertTrue(
                load(sourcedMw, sourcedCtx, sourced.getSkillId()).contains("Source: repo1"),
                "an explicit source is reported");
    }
}
