/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillPromptProviderTest {

    private SkillRegistry skillRegistry;
    private AgentSkillPromptProvider provider;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        provider = new AgentSkillPromptProvider(skillRegistry);
    }

    @Test
    @DisplayName("Should return empty string when no skills registered")
    void testNoSkillsReturnsEmpty() {
        String prompt = provider.getSkillSystemPrompt();

        assertEquals("", prompt);
    }

    @Test
    @DisplayName("Should render metadata as XML for single skill")
    void testSingleSkill() {
        AgentSkill skill =
                new AgentSkill("test_skill", "Test Skill Description", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("## Available Skills"));
        assertTrue(prompt.contains("<available_skills>"));
        assertTrue(prompt.contains("<skill>"));
        assertTrue(prompt.contains("<name>test_skill</name>"));
        assertTrue(prompt.contains("<description>Test Skill Description</description>"));
        assertTrue(prompt.contains("<skill-id>test_skill_custom</skill-id>"));
        assertTrue(prompt.contains("</skill>"));
        assertTrue(prompt.contains("</available_skills>"));
    }

    @Test
    @DisplayName("Should preserve metadata order in XML output")
    void testMetadataOrder() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "trello");
        metadata.put("description", "Manage Trello boards");
        metadata.put("homepage", "https://developer.atlassian.com/cloud/trello/rest/");
        AgentSkill skill = new AgentSkill(metadata, "# Content", null, null);
        skillRegistry.registerSkill("trello_custom", skill, new RegisteredSkill("trello_custom"));

        String prompt = provider.getSkillSystemPrompt();

        int nameIndex = prompt.indexOf("<name>trello</name>");
        int descriptionIndex = prompt.indexOf("<description>Manage Trello boards</description>");
        int homepageIndex =
                prompt.indexOf(
                        "<homepage>https://developer.atlassian.com/cloud/trello/rest/</homepage>");
        int skillIdIndex = prompt.indexOf("<skill-id>trello_custom</skill-id>");

        assertTrue(nameIndex < descriptionIndex);
        assertTrue(descriptionIndex < homepageIndex);
        assertTrue(homepageIndex < skillIdIndex);
    }

    @Test
    @DisplayName("Should render nested metadata as nested XML")
    void testNestedMetadataXml() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "trello");
        metadata.put("description", "Manage Trello boards");
        metadata.put(
                "metadata",
                Map.of(
                        "clawdbot",
                        Map.of(
                                "emoji",
                                "📋",
                                "requires",
                                Map.of(
                                        "bins", List.of("jq"),
                                        "env", List.of("TRELLO_API_KEY", "TRELLO_TOKEN")))));

        AgentSkill skill = new AgentSkill(metadata, "# Content", null, null);
        skillRegistry.registerSkill("trello_custom", skill, new RegisteredSkill("trello_custom"));

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("<metadata>"));
        assertTrue(prompt.contains("<clawdbot>"));
        assertTrue(prompt.contains("<emoji>📋</emoji>"));
        assertTrue(prompt.contains("<requires>"));
        assertTrue(prompt.contains("<bins>"));
        assertTrue(prompt.contains("<item>jq</item>"));
        assertTrue(prompt.contains("<env>"));
        assertTrue(prompt.contains("<item>TRELLO_API_KEY</item>"));
        assertTrue(prompt.contains("<item>TRELLO_TOKEN</item>"));
    }

    @Test
    @DisplayName("Should expose only name and description when all metadata is disabled")
    void testExposeOnlyCoreMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "trello");
        metadata.put("description", "Manage Trello boards");
        metadata.put("homepage", "https://developer.atlassian.com/cloud/trello/rest/");
        metadata.put("metadata", Map.of("clawdbot", Map.of("emoji", "📋")));
        AgentSkill skill = new AgentSkill(metadata, "# Content", null, null);
        skillRegistry.registerSkill("trello_custom", skill, new RegisteredSkill("trello_custom"));

        provider.setExposeAllMetadata(false);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("<name>trello</name>"));
        assertTrue(prompt.contains("<description>Manage Trello boards</description>"));
        assertTrue(prompt.contains("<skill-id>trello_custom</skill-id>"));
        assertFalse(prompt.contains("<homepage>"));
        assertFalse(prompt.contains("<metadata>"));
    }

    @Test
    @DisplayName("Should escape special characters in XML")
    void testXmlEscaping() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "test_skill");
        metadata.put("description", "Description with <xml> & \"quotes\" and 'apostrophes'");
        AgentSkill skill = new AgentSkill(metadata, "# Content", null, null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(
                prompt.contains(
                        "<description>Description with &lt;xml&gt; &amp; &quot;quotes&quot; and"
                                + " &apos;apostrophes&apos;</description>"));
    }

    @Test
    @DisplayName("Should fallback to entry tag for invalid metadata keys")
    void testInvalidMetadataKeyFallback() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "test_skill");
        metadata.put("description", "desc");
        metadata.put("tool:config", "enabled");
        AgentSkill skill = new AgentSkill(metadata, "# Content", null, null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("<entry key=\"tool:config\">enabled</entry>"));
    }

    @Test
    @DisplayName("Should not include code execution section when not enabled")
    void testNoCodeExecutionSectionByDefault() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        String prompt = provider.getSkillSystemPrompt();

        assertFalse(prompt.contains("## Code Execution"));
        assertFalse(prompt.contains("<code_execution>"));
    }

    @Test
    @DisplayName("Should not include code execution section when enabled but uploadDir not set")
    void testNoCodeExecutionSectionWhenEnabledButNoUploadDir() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        provider.setCodeExecutionEnable(true);

        String prompt = provider.getSkillSystemPrompt();

        assertFalse(prompt.contains("## Code Execution"));
        assertFalse(prompt.contains("<code_execution>"));
    }

    @Test
    @DisplayName("Should include code execution section with uploadDir when enabled")
    void testCodeExecutionSectionIncludedWhenEnabled(@TempDir Path tempDir) {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        provider.setCodeExecutionEnable(true);
        provider.setUploadDir(tempDir);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("## Code Execution"));
        assertTrue(prompt.contains("<code_execution>"));
        assertTrue(prompt.contains("</code_execution>"));
        assertTrue(prompt.contains(tempDir.toAbsolutePath().toString()));
    }

    @Test
    @DisplayName("Code execution section should appear after </available_skills>")
    void testCodeExecutionSectionAppearsAfterAvailableSkills(@TempDir Path tempDir) {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        provider.setCodeExecutionEnable(true);
        provider.setUploadDir(tempDir);

        String prompt = provider.getSkillSystemPrompt();

        int availableSkillsEnd = prompt.indexOf("</available_skills>");
        int codeExecutionStart = prompt.indexOf("## Code Execution");
        assertTrue(availableSkillsEnd < codeExecutionStart);
    }

    @Test
    @DisplayName("Should use custom code execution instruction when set")
    void testCustomCodeExecutionInstruction(@TempDir Path tempDir) {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillRegistry.registerSkill(
                "test_skill_custom", skill, new RegisteredSkill("test_skill_custom"));

        provider.setCodeExecutionEnable(true);
        provider.setUploadDir(tempDir);
        provider.setCodeExecutionInstruction("## Custom Section\nSkills dir: %s");

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("## Custom Section"));
        assertTrue(prompt.contains("Skills dir: " + tempDir.toAbsolutePath()));
        assertFalse(prompt.contains("## Code Execution"));
    }
}
