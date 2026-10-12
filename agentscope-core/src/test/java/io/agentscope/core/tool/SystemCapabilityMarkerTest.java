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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * System-capability marker tests: annotation/builder passthrough, copy retention,
 * meta-tool marking, description split, legacy-constructor compatibility.
 *
 * @author shanhongyu
 */
@Tag("unit")
@DisplayName("System capability markers (isSystem)")
class SystemCapabilityMarkerTest {

    // ==================== helpers ====================

    /** Tool object with one system-marked and one plain @Tool method. */
    private static final class MarkerTools {

        @Tool(name = "marked_system", description = "system-marked tool", system = true)
        public String markedSystem() {
            return "ok";
        }

        @Tool(name = "plain_tool", description = "plain tool")
        public String plainTool() {
            return "ok";
        }
    }

    /** Minimal ToolBase subclass for builder-flag assertions. */
    private static final class StubToolBase extends ToolBase {
        /** @param builder the pre-configured base builder. */
        private StubToolBase(Builder builder) {
            super(builder);
        }

        /**
         * Creates a stub tool with the given system flag.
         *
         * @param name the tool name
         * @param system the system flag to set
         * @return the built stub
         */
        static StubToolBase create(String name, boolean system) {
            return new StubToolBase(
                    ToolBase.builder()
                            .name(name)
                            .description("stub")
                            .inputSchema(Map.of("type", "object"))
                            .system(system));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("ok"));
        }
    }

    /** Skill repository stub that serves no skills. */
    private static final class EmptyRepo implements AgentSkillRepository {
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
            return List.of();
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

    // ==================== annotation passthrough ====================

    @Test
    @DisplayName("@Tool(system=true) → isSystem() true; default false")
    void annotationPassthrough() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MarkerTools());

        assertTrue(toolkit.getTool("marked_system").isSystem());
        assertFalse(toolkit.getTool("plain_tool").isSystem());
    }

    // ==================== builder passthrough ====================

    @Test
    @DisplayName(
            "builder system flag takes effect on ToolBase, ToolGroup, SkillToolGroup, AgentSkill")
    void builderPassthrough() {
        assertTrue(StubToolBase.create("sys", true).isSystem());
        assertFalse(StubToolBase.create("plain", false).isSystem());

        assertTrue(ToolGroup.builder().name("g").description("d").system(true).build().isSystem());
        assertFalse(ToolGroup.builder().name("g").description("d").build().isSystem());

        assertTrue(
                SkillToolGroup.skillBuilder()
                        .name("sg")
                        .description("d")
                        .activateOnSkill("s")
                        .system(true)
                        .build()
                        .isSystem());
        assertFalse(
                SkillToolGroup.skillBuilder()
                        .name("sg")
                        .description("d")
                        .activateOnSkill("s")
                        .build()
                        .isSystem());

        assertTrue(
                AgentSkill.builder()
                        .name("s")
                        .description("d")
                        .skillContent("c")
                        .system(true)
                        .build()
                        .isSystem());
        assertFalse(
                AgentSkill.builder()
                        .name("s")
                        .description("d")
                        .skillContent("c")
                        .build()
                        .isSystem());
    }

    // ==================== copy retention ====================

    @Test
    @DisplayName("copy/toBuilder/Toolkit.copy preserve the system flag across types")
    void copyRetention() {
        assertTrue(
                ToolGroup.builder()
                        .name("g")
                        .description("d")
                        .system(true)
                        .build()
                        .copy()
                        .isSystem());

        SkillToolGroup skillGroup =
                SkillToolGroup.skillBuilder()
                        .name("sg")
                        .description("d")
                        .activateOnSkill("s")
                        .system(true)
                        .build();
        ToolGroup copy = skillGroup.copy();
        assertTrue(copy instanceof SkillToolGroup, "copy preserves the concrete type");
        assertTrue(copy.isSystem(), "SkillToolGroup.copy() must not silently drop the flag");

        AgentSkill skill =
                AgentSkill.builder()
                        .name("s")
                        .description("d")
                        .skillContent("c")
                        .system(true)
                        .build();
        assertTrue(skill.toBuilder().build().isSystem());

        Toolkit toolkit = new Toolkit();
        toolkit.registerToolGroup(skillGroup);
        assertNotNull(toolkit.copy().getToolGroup("sg"));
        assertTrue(toolkit.copy().getToolGroup("sg").isSystem());
    }

    // ==================== meta-tool marking ====================

    @Test
    @DisplayName("framework meta tools (reset + both load-tool registrations) are marked system")
    void frameworkMetaToolsAreSystem() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerMetaTool();
        assertTrue(toolkit.getTool("reset_equipped_tools").isSystem());

        // The DynamicSkillMiddleware constructor registers the shared runtime load tool.
        new DynamicSkillMiddleware(List.of(new EmptyRepo()), toolkit);
        assertTrue(toolkit.getTool("load_skill_through_path").isSystem());

        // Static skillBox registration: must stay loadable under an inherited surface (grant
        // decisions live in loadSkillResourceImpl).
        Toolkit staticToolkit = new Toolkit();
        new SkillBox(staticToolkit).registerSkillLoadTool();
        assertTrue(staticToolkit.getTool("load_skill_through_path").isSystem());
    }

    // ==================== description split ====================

    @Test
    @DisplayName("baseDescription() excludes group notes; getDescription() = base + full notes")
    void descriptionSplit() {
        ToolGroupManager groupManager = new ToolGroupManager();
        groupManager.createToolGroup("g1", "desc-one", true);
        groupManager.createToolGroup("g2", "desc-two", false);
        MetaToolFactory factory = new MetaToolFactory(groupManager, new ToolRegistry());
        AgentTool metaTool = factory.createResetEquippedToolsAgentTool();

        String base = MetaToolFactory.baseDescription();
        assertFalse(base.contains("g1"), "base description must not embed group notes");
        assertFalse(base.contains("desc-one"));

        String full = metaTool.getDescription();
        assertTrue(full.startsWith(base), "full description starts with the base");
        assertTrue(full.contains("g1") && full.contains("desc-one"));
        assertTrue(full.contains("g2") && full.contains("desc-two"));
        assertEquals(base + groupManager.getNotes(), full);
    }

    // ==================== legacy constructors ====================

    @Test
    @DisplayName("AgentSkill legacy constructors default isSystem() to false")
    void legacySkillConstructorsDefaultFalse() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "s");
        metadata.put("description", "d");
        assertFalse(
                new AgentSkill("s", "d", "content", null).isSystem(),
                "4-arg constructor (name, description, content, resources)");
        assertFalse(
                new AgentSkill("s", "d", "content", null, "src").isSystem(),
                "5-arg constructor (+source)");
        assertFalse(
                new AgentSkill(metadata, "content", null, "src").isSystem(),
                "metadata-based constructor");
        assertFalse(
                new AgentSkill(metadata, "content", null, "src", null).isSystem(),
                "metadata + originDir constructor");
    }

    // ==================== helper methods ====================

    @Test
    @DisplayName("helpers: skill group naming, group-membership lookup, restricted notes")
    void helperMethods() {
        assertEquals("s1_skill_tools", SkillToolGroup.skillToolsGroupName("s1"));
        assertEquals("report_gen_skill_tools", SkillToolGroup.skillToolsGroupName("report_gen"));

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("g1", "one", true);
        toolkit.createToolGroup("g2", "two", false);
        toolkit.registerAgentTool(StubToolBase.create("t1", false));
        toolkit.registerAgentTool(StubToolBase.create("t2", false));
        toolkit.addToolToGroup("g1", "t1");
        toolkit.addToolToGroup("g2", "t1");
        toolkit.addToolToGroup("g1", "t2");
        assertEquals(Set.of("g1", "g2"), toolkit.getToolGroupsOf("t1"));
        assertEquals(Set.of("g1"), toolkit.getToolGroupsOf("t2"));
        assertEquals(Set.of(), toolkit.getToolGroupsOf("unknown"));

        ToolGroupManager groupManager = new ToolGroupManager();
        groupManager.createToolGroup("g1", "desc-one", true);
        groupManager.createToolGroup("g2", "desc-two", false);
        String notes = groupManager.getNotes(List.of("g1"));
        assertTrue(notes.contains("g1") && notes.contains("desc-one"));
        assertFalse(notes.contains("desc-two"), "non-listed group description must not leak");
        assertFalse(notes.contains("g2:"), "non-listed group name must not leak");
    }
}
