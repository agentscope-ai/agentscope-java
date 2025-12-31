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

package io.agentscope.core.model;

/**
 * Represents the tool choice behavior for LLM model calls.
 *
 * <p>This sealed interface defines how the model should handle tool calling:
 * <ul>
 *   <li>{@link Auto} - Let the model decide whether and which tools to call (default)</li>
 *   <li>{@link None} - Prevent the model from calling any tools</li>
 *   <li>{@link Required} - Force the model to call at least one tool (model chooses which)</li>
 *   <li>{@link Specific} - Force the model to call a specific tool by name</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Force model to call a specific tool
 * GenerateOptions options = GenerateOptions.builder()
 *     .toolChoice(new ToolChoice.Specific("generate_response"))
 *     .build();
 *
 * // Let model decide (default)
 * GenerateOptions options = GenerateOptions.builder()
 *     .toolChoice(new ToolChoice.Auto())
 *     .build();
 * }</pre>
 *
 * @see GenerateOptions
 */
public sealed interface ToolChoice
        permits ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, ToolChoice.Specific {

    /**
     * Auto mode: Let the model decide whether and which tools to call.
     * This is the default behavior when toolChoice is not specified.
     */
    record Auto() implements ToolChoice {}

    /**
     * None mode: Prevent the model from calling any tools.
     * The model will respond with text only.
     */
    record None() implements ToolChoice {}

    /**
     * Required mode: Force the model to call at least one tool.
     * The model chooses which tool(s) to call based on the context.
     */
    record Required() implements ToolChoice {}

    /**
     * Specific mode: Force the model to call a specific tool by name.
     *
     * @param toolName The name of the tool that must be called
     */
    record Specific(String toolName) implements ToolChoice {
        /**
         * Creates a Specific tool choice.
         *
         * @param toolName The name of the tool that must be called (must not be null or empty)
         * @throws IllegalArgumentException if toolName is null or empty
         */
        public Specific {
            if (toolName == null || toolName.trim().isEmpty()) {
                throw new IllegalArgumentException("Tool name must not be null or empty");
            }
        }
    }
}
