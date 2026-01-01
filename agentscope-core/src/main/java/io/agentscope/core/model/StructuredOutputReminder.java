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
package io.agentscope.core.model;

/**
 * Configuration for how to ensure the model calls generate_response tool in structured output
 * mode.
 *
 * <p>This enum controls the mechanism used to enforce that the model calls the temporary
 * generate_response tool when generating structured output.
 */
public enum StructuredOutputReminder {
    /**
     * Use tool_choice API parameter to force the tool call when the model doesn't call it
     * voluntarily. This is the default and recommended option.
     *
     * <p>When this mode is used:
     * <ol>
     *   <li>First round: The model is free to call any tool (including business tools)
     *   <li>If the model doesn't call generate_response: The next round will force it via
     *       tool_choice parameter
     * </ol>
     *
     * <p>This approach allows the agent to complete multi-step tasks before generating the final
     * structured output.
     */
    TOOL_CHOICE,

    /**
     * Inject reminder prompt when model doesn't call the tool. Legacy approach requiring multiple
     * API calls and retries.
     *
     * <p>When this mode is used, if the model doesn't call the generate_response tool, a reminder
     * message will be injected into the conversation and the agent will retry the reasoning step.
     * This may require multiple iterations to successfully generate the structured output.
     */
    PROMPT
}
