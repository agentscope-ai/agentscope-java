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
package io.agentscope.examples.advanced.hitl;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import java.util.List;
import java.util.Map;

/**
 * Built-in tool for Human-in-the-Loop (HITL) interactions.
 *
 * <p>When the LLM determines that user input is needed, it calls this tool. The tool always
 * throws {@link ToolSuspendException} to suspend agent execution. The tool's input parameters
 * (question, ui_type, options) are preserved in the {@code ToolUseBlock} and used by the
 * frontend to render the appropriate UI.
 *
 * <p>Supported UI types:
 * <ul>
 *   <li>{@code text} - Free-form text input (default)</li>
 *   <li>{@code select} - Single selection from predefined options</li>
 *   <li>{@code multi_select} - Multiple selection from predefined options</li>
 *   <li>{@code confirm} - Yes/No confirmation dialog</li>
 *   <li>{@code form} - Multi-field structured form</li>
 *   <li>{@code date} - Date picker</li>
 *   <li>{@code number} - Numeric input</li>
 * </ul>
 */
public class UserInteractionTool {

    /** Tool name constant, referenced by {@link HitlInteractionExample} for event routing. */
    public static final String TOOL_NAME = "ask_user";

    /**
     * Ask the user for clarification or additional information.
     *
     * <p>This method always throws {@link ToolSuspendException} to pause the agent.
     * The framework converts this into a pending {@code ToolResultBlock} with
     * {@code GenerateReason.TOOL_SUSPENDED}, allowing the frontend to extract the
     * tool's input parameters and render the appropriate UI component.
     *
     * @param question the question to ask the user
     * @param uiType UI component type (defaults to "text")
     * @param options options for select/multi_select
     * @param fields field definitions for form ui_type
     * @param defaultValue default value for the input field
     * @param allowOther if true, adds an "Other" option with free-text input (select/multi_select)
     * @return never returns normally
     * @throws ToolSuspendException always thrown to suspend agent execution
     */
    @Tool(
            name = TOOL_NAME,
            description =
                    "Ask the user for clarification or additional information when the request is"
                        + " ambiguous or missing required details. Choose the appropriate ui_type:"
                        + " 'text' for free-form input, 'select' for choosing one from a list"
                        + " (provide options), 'multi_select' for choosing multiple from a list,"
                        + " 'confirm' for yes/no questions, 'form' for collecting multiple fields"
                        + " at once (provide fields), 'date' for date selection, 'number' for"
                        + " numeric input.")
    public String askUser(
            @ToolParam(name = "question", description = "The question to ask the user")
                    String question,
            @ToolParam(
                            name = "ui_type",
                            description =
                                    "UI component type: text, select, multi_select, confirm, form,"
                                            + " date, number. Defaults to 'text'.",
                            required = false)
                    String uiType,
            @ToolParam(
                            name = "options",
                            description =
                                    "Options for select/multi_select. Simple string array,"
                                            + " e.g. [\"Beijing\", \"Shanghai\", \"Tokyo\"]",
                            required = false)
                    List<String> options,
            @ToolParam(
                            name = "fields",
                            description =
                                    "Field definitions for 'form' ui_type. Array of objects with"
                                        + " name, label, type (text/number/date/select/textarea),"
                                        + " placeholder, required, options, min, max, step.",
                            required = false)
                    List<Map<String, Object>> fields,
            @ToolParam(
                            name = "default_value",
                            description = "Default value for the input field (string only)",
                            required = false)
                    Object defaultValue,
            @ToolParam(
                            name = "allow_other",
                            description =
                                    "If true, adds an 'Other' option with a free-text input so"
                                            + " users can enter custom values not in the predefined"
                                            + " list. Use with select or multi_select.",
                            required = false)
                    Boolean allowOther) {
        String reason = question != null ? question : "Waiting for user input";
        throw new ToolSuspendException(reason);
    }
}
