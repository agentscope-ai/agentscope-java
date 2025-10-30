/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.agent;

/**
 * Strategy for generating structured output.
 *
 * <p>This enum defines how agents should generate structured output when requested.
 * Different models support different approaches, and agents can either automatically
 * detect the best strategy or use a specific one.
 */
public enum StructuredOutputStrategy {
    /**
     * Automatically choose based on model capabilities.
     * - If model supports native structured output (e.g., OpenAI): Use NATIVE
     * - If model supports tool calling: Use TOOL_BASED
     * - Otherwise: Throw exception
     */
    AUTO,

    /**
     * Force using tool-based approach (generate_response with extended schema).
     * Works with all models that support tool calling.
     * This is more universal and allows consistent behavior across different models.
     */
    TOOL_BASED,

    /**
     * Force using model's native structured output API (e.g., OpenAI response_format).
     * May fail if model doesn't support it.
     * This approach may offer better performance or accuracy with models that have
     * native support.
     */
    NATIVE
}
