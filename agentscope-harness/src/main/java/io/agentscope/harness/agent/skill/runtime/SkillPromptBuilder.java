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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders the harness {@code <available_skills>} system-prompt block from a {@link SkillCatalog}.
 *
 * <p>Differences from the legacy {@code AgentSkillPromptProvider}:
 *
 * <ul>
 *   <li>Each {@code <skill>} optionally carries a {@code <files-root>} child giving the
 *       absolute path to that skill's files. The middleware decides whether to populate this
 *       based on shell availability and skill source.
 *   <li>The {@code <code_execution>} section is emitted only when at least one entry in the
 *       catalog has a non-null {@code filesRoot} (i.e. shell is available and at least one
 *       skill's files are reachable). The new instruction tells the LLM to use each skill's
 *       {@code <files-root>} rather than a single hardcoded root.
 *   <li>Resource fallback for non-SKILL.md paths is implemented by
 *       {@link SkillLoadTool}; the prompt does not need to mention it.
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class SkillPromptBuilder {

    private static final String INDENT = "  ";
    private static final Pattern XML_TAG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    public static final String DEFAULT_HEADER =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded with the same tool and other paths

            Example:
            1. User asks to analyze data -> find a matching skill below (e.g. <skill-id>data-analysis_workspace</skill-id>)
            2. Load it: load_skill_through_path(skillId="data-analysis_workspace", path="SKILL.md")
            3. Follow the instructions returned by the skill

            Metadata is rendered as XML under each <skill> element:
            - scalar metadata becomes a simple child element
            - nested maps become nested XML elements
            - lists become repeated <item> elements
            - <skill-id> is always appended for tool loading
            - <files-root>, when present, gives the absolute path for shell-executing this skill's scripts
            </usage>

            <available_skills>

            """;

    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
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

    private final String header;
    private final String codeExecutionInstruction;
    private final boolean exposeAllMetadata;

    public SkillPromptBuilder() {
        this(null, null, true);
    }

    public SkillPromptBuilder(
            String header, String codeExecutionInstruction, boolean exposeAllMetadata) {
        this.header = (header == null || header.isBlank()) ? DEFAULT_HEADER : header;
        this.codeExecutionInstruction =
                (codeExecutionInstruction == null || codeExecutionInstruction.isBlank())
                        ? DEFAULT_CODE_EXECUTION_INSTRUCTION
                        : codeExecutionInstruction;
        this.exposeAllMetadata = exposeAllMetadata;
    }

    /**
     * Render the prompt block. Returns an empty string when no skills pass the filter, so the
     * caller can no-op concatenation.
     *
     * @param catalog the per-call snapshot (non-null)
     * @param filter  visibility filter applied per skillId (non-null; use {@link SkillFilter#all()})
     * @return prompt text, or empty string when nothing is visible
     */
    public String render(SkillCatalog catalog, SkillFilter filter) {
        if (catalog == null || catalog.isEmpty()) {
            return "";
        }
        SkillFilter effective = filter != null ? filter : SkillFilter.all();

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        boolean anyWithFilesRoot = false;

        for (HarnessSkillEntry entry : catalog.all()) {
            String id = entry.skill().getSkillId();
            if (!effective.isAllowed(id)) {
                continue;
            }
            if (!any) {
                sb.append(header);
                any = true;
            }
            appendSkill(sb, entry);
            if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
                anyWithFilesRoot = true;
            }
        }

        if (!any) {
            return "";
        }
        sb.append("</available_skills>");

        // Only emit the code-execution section when at least one visible skill is shell-reachable.
        // No filesRoot anywhere => no shell tool registered (or all skills are unreachable),
        // so the instruction would mislead the LLM.
        if (anyWithFilesRoot) {
            sb.append(codeExecutionInstruction);
        }

        return sb.toString();
    }

    /** Convenience overload used by the middleware when no extra filter is active. */
    public String render(SkillCatalog catalog) {
        return render(catalog, SkillFilter.all());
    }

    private void appendSkill(StringBuilder sb, HarnessSkillEntry entry) {
        AgentSkill skill = entry.skill();
        sb.append("<skill>\n");
        for (Map.Entry<String, Object> e : metadataView(skill).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            appendXmlNode(sb, e.getKey(), e.getValue(), 1);
        }
        appendXmlNode(sb, "skill-id", skill.getSkillId(), 1);
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            appendXmlNode(sb, "files-root", entry.filesRoot(), 1);
        }
        sb.append("</skill>\n\n");
    }

    private Map<String, Object> metadataView(AgentSkill skill) {
        if (exposeAllMetadata) {
            return skill.getMetadata();
        }
        java.util.LinkedHashMap<String, Object> trimmed = new java.util.LinkedHashMap<>();
        trimmed.put("name", skill.getName());
        trimmed.put("description", skill.getDescription());
        return trimmed;
    }

    private void appendXmlNode(StringBuilder sb, String key, Object value, int indentLevel) {
        if (value == null) {
            return;
        }
        String indent = INDENT.repeat(indentLevel);
        boolean validTag = isValidXmlTagName(key);
        String openTag = validTag ? "<" + key + ">" : "<entry key=\"" + escapeXml(key) + "\">";
        String closeTag = validTag ? "</" + key + ">" : "</entry>";

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
            for (Map.Entry<?, ?> e : mapValue.entrySet()) {
                appendXmlNode(sb, String.valueOf(e.getKey()), e.getValue(), indentLevel + 1);
            }
        } else if (value instanceof Collection<?> collValue) {
            for (Object item : collValue) {
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
        return value != null && XML_TAG_NAME.matcher(value).matches();
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
