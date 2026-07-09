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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.SkillResources;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class SkillRuntimeTest {

    // =========================================================================
    //  SkillCatalog
    // =========================================================================

    @Nested
    class CatalogTests {

        @Test
        void emptyCatalogReportsZero() {
            SkillCatalog c = SkillCatalog.empty();
            assertTrue(c.isEmpty());
            assertEquals(0, c.size());
            assertNull(c.get("anything"));
        }

        @Test
        void preservesInsertionOrder() {
            HarnessSkillEntry a = HarnessSkillEntry.of(skill("alpha", "src"), null);
            HarnessSkillEntry b = HarnessSkillEntry.of(skill("beta", "src"), null);
            HarnessSkillEntry c = HarnessSkillEntry.of(skill("gamma", "src"), null);
            SkillCatalog cat = SkillCatalog.of(List.of(a, b, c));
            assertEquals(List.of("alpha_src", "beta_src", "gamma_src"), cat.ids());
        }

        @Test
        void laterEntryWithSameIdOverwrites() {
            HarnessSkillEntry a = HarnessSkillEntry.of(skill("alpha", "src"), null);
            HarnessSkillEntry aDup = HarnessSkillEntry.of(skill("alpha", "src"), null);
            SkillCatalog cat = SkillCatalog.of(List.of(a, aDup));
            assertEquals(1, cat.size());
            assertSame(aDup.skill(), cat.get("alpha_src").skill());
        }
    }

    // =========================================================================
    //  ShellPathPolicy
    // =========================================================================

    @Nested
    class ShellPathPolicyTests {

        @Test
        void escapeSpacesReplacesWithBackslashSpace() {
            assertEquals("hello", ShellPathPolicy.escapeSpaces("hello"));
            assertEquals("hello\\ world", ShellPathPolicy.escapeSpaces("hello world"));
            assertEquals("V2Ray\\ 代理配置助手", ShellPathPolicy.escapeSpaces("V2Ray 代理配置助手"));
            assertEquals("a\\ b\\ c", ShellPathPolicy.escapeSpaces("a b c"));
        }

        @Test
        void sandboxResolveEscapesSpacesInSkillName() {
            ShellPathPolicy policy = ShellPathPolicy.sandbox();
            String result = policy.resolve("V2Ray 代理配置助手", new StageResult.WorkspaceNative());
            assertEquals("/workspace/skills/V2Ray\\ 代理配置助手", result);
        }

        @Test
        void sandboxResolveEscapesSpacesInCachedSkill() {
            ShellPathPolicy policy = ShellPathPolicy.sandbox();
            String result = policy.resolve("ignored", new StageResult.Cached("ns", "my skill"));
            assertEquals("/workspace/.skills-cache/ns/my\\ skill", result);
        }

        @Test
        void localWithShellResolveEscapesSpaces() {
            ShellPathPolicy policy = ShellPathPolicy.localWithShell(Paths.get("/tmp/my workspace"));
            String result = policy.resolve("my skill", new StageResult.WorkspaceNative());
            String expected =
                    Paths.get("/tmp/my workspace")
                            .resolve("skills")
                            .resolve("my skill")
                            .toAbsolutePath()
                            .toString()
                            .replace(" ", "\\ ");
            assertEquals(expected, result);
        }

        @Test
        void noShellAlwaysReturnsNull() {
            ShellPathPolicy policy = ShellPathPolicy.noShell();
            assertNull(policy.resolve("any name", new StageResult.WorkspaceNative()));
            assertNull(policy.resolve("any name", new StageResult.Cached("ns", "name")));
            assertNull(policy.resolve("any name", StageResult.NONE));
            assertNull(policy.resolve("any name", null));
        }

        @Test
        void nullStageOrNoneReturnsNull() {
            ShellPathPolicy policy = ShellPathPolicy.sandbox();
            assertNull(policy.resolve("alpha", null));
            assertNull(policy.resolve("alpha", StageResult.NONE));
        }
    }

    // =========================================================================
    //  SkillPromptBuilder
    // =========================================================================

    @Nested
    class PromptBuilderTests {

        @Test
        void emptyCatalogReturnsEmptyString() {
            assertEquals("", new SkillPromptBuilder().render(SkillCatalog.empty()));
        }

        @Test
        void rendersSkillIdAndOmitsFilesRootWhenAbsent() {
            HarnessSkillEntry e = HarnessSkillEntry.of(skill("alpha", "wkspace"), null);
            String out = new SkillPromptBuilder().render(SkillCatalog.of(List.of(e)));
            assertTrue(out.contains("<skill-id>alpha_wkspace</skill-id>"));
            // No actual <files-root>...</files-root> element rendered.
            // (The header text mentions "<files-root>" descriptively without a closing tag.)
            assertFalse(out.contains("</files-root>"));
            // No filesRoot anywhere -> no code-execution section.
            assertFalse(out.contains("## Code Execution"));
        }

        @Test
        void rendersFilesRootAndCodeExecutionWhenAvailable() {
            HarnessSkillEntry e =
                    new HarnessSkillEntry(
                            skill("alpha", "wkspace"), null, "/workspace/skills/alpha");
            String out = new SkillPromptBuilder().render(SkillCatalog.of(List.of(e)));
            assertTrue(out.contains("<files-root>/workspace/skills/alpha</files-root>"));
            assertTrue(out.contains("## Code Execution"));
            assertTrue(out.contains("<files-root>"));
        }

        @Test
        void rendersEscapedFilesRootWhenPathContainsSpaces() {
            HarnessSkillEntry e =
                    new HarnessSkillEntry(
                            skill("V2Ray 代理配置助手", "wkspace"),
                            null,
                            "/workspace/skills/V2Ray\\ 代理配置助手");
            String out = new SkillPromptBuilder().render(SkillCatalog.of(List.of(e)));
            assertTrue(out.contains("<files-root>/workspace/skills/V2Ray\\ 代理配置助手</files-root>"));
        }

        @Test
        void filterRemovesHiddenSkills() {
            HarnessSkillEntry visible = HarnessSkillEntry.of(skill("visible", "src"), null);
            HarnessSkillEntry hidden = HarnessSkillEntry.of(skill("hidden", "src"), null);
            SkillCatalog cat = SkillCatalog.of(List.of(visible, hidden));
            SkillFilter only = SkillFilter.only("visible");

            String out = new SkillPromptBuilder().render(cat, only);
            assertTrue(out.contains("<skill-id>visible_src</skill-id>"));
            assertFalse(out.contains("<skill-id>hidden_src</skill-id>"));
        }

        @Test
        void filterRejectingAllReturnsEmpty() {
            HarnessSkillEntry e = HarnessSkillEntry.of(skill("alpha", "src"), null);
            assertEquals(
                    "",
                    new SkillPromptBuilder()
                            .render(SkillCatalog.of(List.of(e)), SkillFilter.none()));
        }

        @Test
        void filterWithBareNameMatchesCompositeId() {
            HarnessSkillEntry visible =
                    HarnessSkillEntry.of(
                            skill("host-forensics-client", "filesystem-agentscope_skills"), null);
            HarnessSkillEntry hidden =
                    HarnessSkillEntry.of(
                            skill("other-skill", "filesystem-agentscope_skills"), null);
            SkillCatalog cat = SkillCatalog.of(List.of(visible, hidden));
            // User passes bare skill name (the natural API usage)
            SkillFilter only = SkillFilter.only("host-forensics-client");

            String out = new SkillPromptBuilder().render(cat, only);
            assertTrue(
                    out.contains(
                            "<skill-id>host-forensics-client_filesystem-agentscope_skills</skill-id>"));
            assertFalse(
                    out.contains("<skill-id>other-skill_filesystem-agentscope_skills</skill-id>"));
        }
    }

    // =========================================================================
    //  SkillLoadTool
    // =========================================================================

    @Nested
    class LoadToolTests {

        @Test
        void returnsSkillMarkdownForSkillMdPath() {
            SkillRuntime r = new SkillRuntime();
            HarnessSkillEntry e =
                    HarnessSkillEntry.of(
                            skillWith("alpha", "Alpha description.", "# Body line\n", null), null);
            r.install(SkillCatalog.of(List.of(e)), null);

            ToolResultBlock res = invoke(r, "alpha_workspace", "SKILL.md");
            String text = textOf(res);
            assertTrue(text.contains("Successfully loaded skill: alpha_workspace"));
            assertTrue(text.contains("# Body line"));
        }

        @Test
        void inMemoryResourceHitsBeforeLazyFallback() {
            SkillRuntime r = new SkillRuntime();
            Map<String, String> mem = new HashMap<>();
            mem.put("scripts/run.py", "in-memory-body");
            SkillResources lazy = recording(Map.of("scripts/run.py", "lazy-body"));
            HarnessSkillEntry e =
                    HarnessSkillEntry.of(skillWith("alpha", "Alpha desc.", "# body", mem), lazy);
            r.install(SkillCatalog.of(List.of(e)), null);

            String text = textOf(invoke(r, "alpha_workspace", "scripts/run.py"));
            assertTrue(text.contains("in-memory-body"));
            assertFalse(text.contains("lazy-body"));
        }

        @Test
        void lazyFallbackUsedWhenInMemoryMisses() {
            SkillRuntime r = new SkillRuntime();
            SkillResources lazy = recording(Map.of("references/guide.md", "from-fs"));
            HarnessSkillEntry e =
                    HarnessSkillEntry.of(skillWith("alpha", "Alpha desc.", "# body", null), lazy);
            r.install(SkillCatalog.of(List.of(e)), null);

            String text = textOf(invoke(r, "alpha_workspace", "references/guide.md"));
            assertTrue(text.contains("from-fs"));
        }

        @Test
        void notFoundEnumeratesBothSources() {
            SkillRuntime r = new SkillRuntime();
            Map<String, String> mem = Map.of("references/a.md", "x");
            SkillResources lazy = recording(Map.of("scripts/b.py", "y"));
            HarnessSkillEntry e =
                    HarnessSkillEntry.of(skillWith("alpha", "Alpha desc.", "# body", mem), lazy);
            r.install(SkillCatalog.of(List.of(e)), null);

            String err = errorOf(invoke(r, "alpha_workspace", "does-not-exist"));
            assertTrue(err.contains("Resource not found"));
            assertTrue(err.contains("SKILL.md"));
            assertTrue(err.contains("references/a.md"));
            assertTrue(err.contains("scripts/b.py"));
        }

        @Test
        void unknownSkillIdReturnsError() {
            SkillRuntime r = new SkillRuntime();
            r.install(SkillCatalog.empty(), null);
            String err = errorOf(invoke(r, "ghost_x", "SKILL.md"));
            assertTrue(err.contains("Skill not found"));
        }

        @Test
        void missingParametersReturnsError() {
            SkillRuntime r = new SkillRuntime();
            r.install(SkillCatalog.empty(), null);
            String err1 =
                    errorOf(
                            r.loadTool()
                                    .callAsync(ToolCallParam.builder().input(Map.of()).build())
                                    .block());
            assertTrue(err1.contains("skillId"));
        }

        private ToolResultBlock invoke(SkillRuntime r, String skillId, String path) {
            ToolCallParam p =
                    ToolCallParam.builder().input(Map.of("skillId", skillId, "path", path)).build();
            return r.loadTool().callAsync(p).block();
        }
    }

    // =========================================================================
    //  SkillRuntime install lifecycle
    // =========================================================================

    @Nested
    class RuntimeLifecycle {

        @Test
        void firstInstallRegistersToolThenIdempotent() {
            Toolkit tk = new Toolkit();
            SkillRuntime r = new SkillRuntime();
            assertNull(tk.getTool(SkillLoadTool.TOOL_NAME));
            r.install(SkillCatalog.empty(), tk);
            assertNotNull(tk.getTool(SkillLoadTool.TOOL_NAME));
            // Second install must not throw or replace with a new instance.
            r.install(SkillCatalog.empty(), tk);
            assertSame(r.loadTool(), tk.getTool(SkillLoadTool.TOOL_NAME));
        }

        @Test
        void catalogSwapVisibleViaToolWithoutReRegistering() {
            Toolkit tk = new Toolkit();
            SkillRuntime r = new SkillRuntime();
            HarnessSkillEntry first = HarnessSkillEntry.of(skill("first", "src"), null);
            r.install(SkillCatalog.of(List.of(first)), tk);
            assertEquals(List.of("first_src"), r.currentCatalog().ids());

            HarnessSkillEntry second = HarnessSkillEntry.of(skill("second", "src"), null);
            r.install(SkillCatalog.of(List.of(second)), tk);
            assertEquals(List.of("second_src"), r.currentCatalog().ids());
            // Same instance still registered.
            assertSame(r.loadTool(), tk.getTool(SkillLoadTool.TOOL_NAME));
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static AgentSkill skill(String name, String source) {
        return new AgentSkill(
                name, "A description for " + name + ".", "# Body for " + name, null, source);
    }

    private static AgentSkill skillWith(
            String name, String description, String body, Map<String, String> resources) {
        return new AgentSkill(name, description, body, resources, "workspace");
    }

    private static String textOf(ToolResultBlock b) {
        if (b == null || b.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock cb : b.getOutput()) {
            if (cb instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private static String errorOf(ToolResultBlock b) {
        return textOf(b);
    }

    private static SkillResources recording(Map<String, String> backing) {
        return new SkillResources() {
            @Override
            public Optional<String> read(String relativePath) {
                return Optional.ofNullable(backing.get(relativePath));
            }

            @Override
            public Optional<byte[]> readBinary(String relativePath) {
                String s = backing.get(relativePath);
                return s == null ? Optional.empty() : Optional.of(s.getBytes());
            }

            @Override
            public List<String> list() {
                return List.copyOf(backing.keySet());
            }
        };
    }
}
