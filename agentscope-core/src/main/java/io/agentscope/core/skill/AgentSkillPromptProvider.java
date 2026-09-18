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
package io.agentscope.core.skill;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates skill system prompts for agents to understand available skills.
 *
 * <p>This provider creates system prompts containing information about available skills
 * that the LLM can dynamically load and use.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * AgentSkillPromptProvider provider = new AgentSkillPromptProvider(registry);
 * String prompt = provider.getSkillSystemPrompt();
 * }</pre>
 *
 */
public class AgentSkillPromptProvider {
    private static final String INDENT = "  ";
    private static final Pattern XML_TAG_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final SkillRegistry skillRegistry;
    private final String instruction;
    private boolean exposeAllMetadata = true;
    private boolean codeExecutionEnabled;
    private String uploadDir;
    private String codeExecutionInstruction;

    public static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded using the same tool with different paths

            Example:
            1. User asks to analyze data → find a matching skill below (e.g. <skill-id>data-analysis_builtin</skill-id>)
            2. Load it: load_skill_through_path(skillId="data-analysis_builtin", path="SKILL.md")
            3. Follow the instructions returned by the skill

            Metadata is rendered as XML under each <skill> element:
            - scalar metadata becomes a simple child element
            - nested maps become nested XML elements
            - lists become repeated <item> elements
            - <skill-id> is always appended for tool loading
            </usage>

            <available_skills>

            """;

    // Every %s placeholder in the template will be replaced with the uploadDir absolute path
    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. When a task can be accomplished by running\s
            a pre-deployed skill script, you MUST execute it yourself using execute_shell_command rather\s
            than describing or suggesting commands to the user.

            Skills root directory: %s
            Each skill's files are located under a subdirectory named by its <skill-id>:
              %s/<skill-id>/scripts/
              %s/<skill-id>/assets/

            Workflow:
            1. After loading a skill, use ls to explore its directory structure and discover available scripts/assets
            2. Once you find the right script, execute it immediately with its absolute path
            3. If execution fails, diagnose and retry — do not fall back to describing the command

            Rules:
            - Always use absolute paths when executing scripts
            - If a script exists for the task, run it directly — do not rewrite its logic inline
            - If asset/data files exist for the task, read them directly — do not recreate them

            Example:
              # Explore what scripts are available for a skill
              execute_shell_command(command="ls %s/data-analysis_builtin/scripts/")

              # Run an existing script with absolute path
              execute_shell_command(command="python3 %s/data-analysis_builtin/scripts/analyze.py")
            </code_execution>
            """;

    /**
     * Code-execution instruction used when every visible skill carries its own {@code
     * <files-root>} (e.g. {@link io.agentscope.core.skill.repository.FileSystemSkillRepository}).
     * No single root path to substitute — the LLM reads each skill's {@code <files-root>}
     * directly from the {@code <available_skills>} block.
     */
    public static final String DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. Each skill in <available_skills>
            includes a <files-root> element giving the absolute path to that skill's files.

            Workflow:
            1. After loading a skill, look at its <files-root> in <available_skills>
            2. List its files:    ls <files-root>/
            3. Run scripts:       python3 <files-root>/scripts/<script-name>
            4. Always use absolute paths derived from <files-root>; never invent paths
            5. If a script exists for the task, run it directly — do not rewrite its logic inline
            </code_execution>
            """;

    /**
     * Creates a skill prompt provider.
     *
     * @param registry The skill registry containing registered skills
     */
    public AgentSkillPromptProvider(SkillRegistry registry) {
        this(registry, null);
    }

    /**
     * Creates a skill prompt provider with custom instruction.
     *
     * @param registry The skill registry containing registered skills
     * @param instruction Custom instruction header (null or blank uses default)
     */
    public AgentSkillPromptProvider(SkillRegistry registry, String instruction) {
        this.skillRegistry = registry;
        this.instruction =
                instruction == null || instruction.isBlank()
                        ? DEFAULT_AGENT_SKILL_INSTRUCTION
                        : instruction;
    }

    /**
     * Gets the skill system prompt for the agent with all skills included.
     *
     * @return The skill system prompt, or empty string if no skills exist
     */
    public String getSkillSystemPrompt() {
        return getSkillSystemPrompt(SkillFilter.all());
    }

    /**
     * Gets the skill system prompt filtered by the given {@link SkillFilter}.
     *
     * @param filter the filter deciding which skills to include (null treated as all)
     * @return The skill system prompt, or empty string if no skills pass the filter
     */
    public String getSkillSystemPrompt(SkillFilter filter) {
        SkillFilter effectiveFilter = filter != null ? filter : SkillFilter.all();

        if (skillRegistry.getAllRegisteredSkills().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean hasSkills = false;
        int visibleCount = 0;
        int withOriginDir = 0;

        for (RegisteredSkill registered : skillRegistry.getAllRegisteredSkills().values()) {
            String skillId = registered.getSkillId();
            if (!effectiveFilter.isAllowed(skillId)) {
                continue;
            }
            AgentSkill skill = skillRegistry.getSkill(skillId);
            if (skill == null) {
                continue;
            }
            if (!hasSkills) {
                sb.append(instruction);
                hasSkills = true;
            }
            appendSkill(sb, skill);
            visibleCount++;
            if (skill.getOriginDir().isPresent()) {
                withOriginDir++;
            }
        }

        if (!hasSkills) {
            return "";
        }

        sb.append("</available_skills>");

        if (codeExecutionEnabled) {
            sb.append(resolveCodeExecutionBlock(visibleCount, withOriginDir));
        }

        return sb.toString();
    }

    /**
     * Picks the code-execution prompt block.
     *
     * <ul>
     *   <li>If a custom template was set via {@link #setCodeExecutionInstruction(String)}, use it
     *       verbatim ({@code %s} substituted with {@code uploadDir} when both are present).
     *   <li>If every visible skill has a {@code <files-root>}, use {@link
     *       #DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION} — no substitution needed.
     *   <li>Otherwise fall back to {@link #DEFAULT_CODE_EXECUTION_INSTRUCTION} (single uploadDir).
     *       Returns empty if {@code uploadDir} is null in this branch, since the template's
     *       absolute path is required to be meaningful.
     * </ul>
     */
    private String resolveCodeExecutionBlock(int visibleCount, int withOriginDir) {
        if (codeExecutionInstruction != null) {
            return uploadDir != null
                    ? codeExecutionInstruction.replace("%s", uploadDir)
                    : codeExecutionInstruction;
        }
        if (visibleCount > 0 && withOriginDir == visibleCount) {
            return DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION;
        }
        if (uploadDir != null) {
            return DEFAULT_CODE_EXECUTION_INSTRUCTION.replace("%s", uploadDir);
        }
        return "";
    }

    /**
     * Sets whether code execution instructions are included in the skill system prompt.
     *
     * @param codeExecutionEnabled {@code true} to append code execution instructions
     */
    public void setCodeExecutionEnable(boolean codeExecutionEnabled) {
        this.codeExecutionEnabled = codeExecutionEnabled;
    }

    /**
     * Sets the upload directory whose absolute path replaces every {@code %s}
     * placeholder in the code execution instruction template.
     *
     * @param uploadDir the upload directory path, or {@code null} to disable path substitution
     */
    public void setUploadDir(Path uploadDir) {
        this.uploadDir = uploadDir != null ? uploadDir.toAbsolutePath().toString() : null;
    }

    /**
     * Sets a custom code execution instruction template.
     *
     * <p>Every {@code %s} placeholder in the template will be replaced with
     * the {@code uploadDir} absolute path. Pass {@code null} or blank to
     * fall back to {@link #DEFAULT_CODE_EXECUTION_INSTRUCTION}.
     *
     * @param codeExecutionInstruction the custom template, or {@code null}/blank for default
     */
    public void setCodeExecutionInstruction(String codeExecutionInstruction) {
        this.codeExecutionInstruction =
                codeExecutionInstruction == null || codeExecutionInstruction.isBlank()
                        ? null
                        : codeExecutionInstruction;
    }

    /**
     * Sets whether all metadata fields are exposed to the LLM.
     *
     * <p>When disabled, only {@code name}, {@code description}, and {@code skill-id}
     * are rendered into the skill prompt.
     *
     * @param exposeAllMetadata {@code true} to expose all metadata, {@code false} to expose only
     *                          the core fields
     */
    public void setExposeAllMetadata(boolean exposeAllMetadata) {
        this.exposeAllMetadata = exposeAllMetadata;
    }

    private void appendSkill(StringBuilder sb, AgentSkill skill) {
        sb.append("<skill>\n");
        for (Map.Entry<String, Object> entry : getPromptMetadata(skill).entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            appendXmlNode(sb, entry.getKey(), entry.getValue(), 1);
        }
        appendXmlNode(sb, "skill-id", skill.getSkillId(), 1);
        // Per-skill files-root: only emitted when code execution is enabled AND the skill
        // carries an originDir (filesystem-backed). Lets the LLM build absolute shell paths
        // without going through uploadSkillFiles or guessing layouts.
        if (codeExecutionEnabled) {
            skill.getOriginDir()
                    .ifPresent(
                            originDir -> appendXmlNode(sb, "files-root", originDir.toString(), 1));
        }
        sb.append("</skill>\n\n");
    }

    private Map<String, Object> getPromptMetadata(AgentSkill skill) {
        if (exposeAllMetadata) {
            return skill.getMetadata();
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skill.getName());
        metadata.put("description", skill.getDescription());
        return metadata;
    }

    private void appendXmlNode(StringBuilder sb, String key, Object value, int indentLevel) {
        if (value == null) {
            return;
        }

        String indent = INDENT.repeat(indentLevel);
        boolean validTagName = isValidXmlTagName(key);
        String openTag = validTagName ? "<" + key + ">" : "<entry key=\"" + escapeXml(key) + "\">";
        String closeTag = validTagName ? "</" + key + ">" : "</entry>";

        if (isScalarValue(value)) {
            sb.append(indent)
                    .append(openTag)
                    .append(escapeXml(String.valueOf(value)))
                    .append(closeTag)
                    .append("\n");
            return;
        }

        sb.append(indent).append(openTag).append("\n");
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                appendXmlNode(
                        sb, String.valueOf(entry.getKey()), entry.getValue(), indentLevel + 1);
            }
        } else if (value instanceof Collection<?> collectionValue) {
            for (Object item : collectionValue) {
                appendXmlNode(sb, "item", item, indentLevel + 1);
            }
        } else {
            sb.append(INDENT.repeat(indentLevel + 1))
                    .append(escapeXml(String.valueOf(value)))
                    .append("\n");
        }
        sb.append(indent).append(closeTag).append("\n");
    }

    private boolean isScalarValue(Object value) {
        return !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>);
    }

    private boolean isValidXmlTagName(String value) {
        return value != null && XML_TAG_NAME_PATTERN.matcher(value).matches();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
