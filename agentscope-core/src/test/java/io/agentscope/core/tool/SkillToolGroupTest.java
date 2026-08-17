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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkillToolGroupTest {

    @Test
    void testBasicProperties() {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("code_tools")
                        .description("Code execution tools")
                        .activateOnSkill("coding")
                        .build();

        assertEquals("code_tools", group.getName());
        assertEquals("coding", group.getActivateOnSkill());
        assertFalse(group.isActive());
        assertEquals(ToolGroupScope.META, group.getScope());
    }

    @Test
    void testDescriptionIncludesSkillReminder() {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("analysis_tools")
                        .description("Data analysis tools")
                        .activateOnSkill("data_analysis")
                        .build();

        String desc = group.getDescription();
        assertTrue(desc.contains("Data analysis tools"));
        assertTrue(desc.contains("IMPORTANT"));
        assertTrue(desc.contains("data_analysis"));
        assertTrue(desc.contains("MUST be activated"));
    }

    @Test
    void testScopeIsAlwaysMeta() {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("tools")
                        .description("desc")
                        .activateOnSkill("skill")
                        .build();

        assertEquals(ToolGroupScope.META, group.getScope());
    }

    @Test
    void testDefaultActiveIsFalse() {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("tools")
                        .description("desc")
                        .activateOnSkill("skill")
                        .build();

        assertFalse(group.isActive());
    }

    @Test
    void testActiveCanBeSetToTrue() {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("tools")
                        .description("desc")
                        .activateOnSkill("skill")
                        .active(true)
                        .build();

        assertTrue(group.isActive());
    }

    @Test
    void testCopyPreservesSubclassType() {
        SkillToolGroup original =
                SkillToolGroup.skillBuilder()
                        .name("code_tools")
                        .description("Code execution tools")
                        .activateOnSkill("coding")
                        .active(true)
                        .build();
        original.addTool("run_code");

        ToolGroup copy = original.copy();

        assertInstanceOf(SkillToolGroup.class, copy);
        SkillToolGroup skillCopy = (SkillToolGroup) copy;
        assertEquals("code_tools", skillCopy.getName());
        assertEquals("coding", skillCopy.getActivateOnSkill());
        assertTrue(skillCopy.isActive());
        assertEquals(ToolGroupScope.META, skillCopy.getScope());
        assertTrue(skillCopy.containsTool("run_code"));
        assertTrue(skillCopy.getDescription().contains("MUST be activated"));
    }

    @Test
    void testCopyDoesNotDuplicateReminder() {
        SkillToolGroup original =
                SkillToolGroup.skillBuilder()
                        .name("tools")
                        .description("Base desc")
                        .activateOnSkill("skill")
                        .build();

        ToolGroup copy = original.copy();
        String desc = copy.getDescription();

        int count = countOccurrences(desc, "MUST be activated");
        assertEquals(1, count, "Reminder should appear exactly once after copy");
    }

    @Test
    void testRegisterInToolGroupManager() {
        ToolGroupManager manager = new ToolGroupManager();
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name("code_tools")
                        .description("Code execution tools")
                        .activateOnSkill("coding")
                        .build();

        manager.registerToolGroup(group);

        ToolGroup retrieved = manager.getToolGroup("code_tools");
        assertNotNull(retrieved);
        assertInstanceOf(SkillToolGroup.class, retrieved);
        assertEquals("coding", ((SkillToolGroup) retrieved).getActivateOnSkill());
    }

    @Test
    void testCreateSkillToolGroupViaManager() {
        ToolGroupManager manager = new ToolGroupManager();
        manager.createSkillToolGroup("code_tools", "Code execution tools", false, "coding");

        ToolGroup retrieved = manager.getToolGroup("code_tools");
        assertNotNull(retrieved);
        assertInstanceOf(SkillToolGroup.class, retrieved);
        assertEquals("coding", ((SkillToolGroup) retrieved).getActivateOnSkill());
        assertFalse(retrieved.isActive());
    }

    @Test
    void testCopyToPreservesSkillToolGroup() {
        ToolGroupManager source = new ToolGroupManager();
        source.createSkillToolGroup("code_tools", "Code execution tools", true, "coding");

        ToolGroupManager target = new ToolGroupManager();
        source.copyTo(target);

        ToolGroup copied = target.getToolGroup("code_tools");
        assertNotNull(copied);
        assertInstanceOf(SkillToolGroup.class, copied);
        assertEquals("coding", ((SkillToolGroup) copied).getActivateOnSkill());
        assertTrue(copied.isActive());
    }

    @Test
    void testSkillToolGroupAppearsInMetaGroupNames() {
        ToolGroupManager manager = new ToolGroupManager();
        manager.createSkillToolGroup("skill_tools", "desc", false, "my_skill");

        assertTrue(manager.getMetaGroupNames().contains("skill_tools"));
    }

    @Test
    void testFindSkillToolGroupsByActivateOnSkill() {
        ToolGroupManager manager = new ToolGroupManager();
        manager.createSkillToolGroup("analysis-tools", "Analysis tools", false, "data-analysis");
        manager.createSkillToolGroup("viz-tools", "Visualization tools", false, "data-analysis");
        manager.createSkillToolGroup("code-tools", "Code tools", false, "coding");

        var matched = manager.findSkillToolGroupsByActivateOnSkill("data-analysis");
        assertEquals(2, matched.size());
        assertTrue(matched.contains("analysis-tools"));
        assertTrue(matched.contains("viz-tools"));

        var codingMatched = manager.findSkillToolGroupsByActivateOnSkill("coding");
        assertEquals(1, codingMatched.size());
        assertTrue(codingMatched.contains("code-tools"));

        var noMatch = manager.findSkillToolGroupsByActivateOnSkill("unknown-skill");
        assertTrue(noMatch.isEmpty());
    }

    @Test
    void testFindSkillToolGroupsByActivateOnSkillIgnoresRegularGroups() {
        ToolGroupManager manager = new ToolGroupManager();
        manager.createToolGroup("regular-group", "A regular group", true);
        manager.createSkillToolGroup("skill-group", "Skill group", false, "my-skill");

        var matched = manager.findSkillToolGroupsByActivateOnSkill("my-skill");
        assertEquals(1, matched.size());
        assertEquals("skill-group", matched.get(0));
    }

    @Test
    void testFindSkillToolGroupsByActivateOnSkillNullSafe() {
        ToolGroupManager manager = new ToolGroupManager();
        manager.createSkillToolGroup("group", "desc", false, "skill");

        var result = manager.findSkillToolGroupsByActivateOnSkill(null);
        assertTrue(result.isEmpty());
    }

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
