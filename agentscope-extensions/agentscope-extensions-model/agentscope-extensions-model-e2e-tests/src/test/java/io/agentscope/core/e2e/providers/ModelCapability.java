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
package io.agentscope.core.e2e.providers;

/**
 * Enumeration of model capabilities for E2E testing.
 *
 * <p>Used to categorize model providers by their supported features, enabling dynamic filtering of
 * providers for specific test scenarios.
 */
public enum ModelCapability {

    /** Basic conversation capability - all models support this. */
    BASIC,

    /** Tool calling (function calling) capability. */
    TOOL_CALLING,

    /** Image understanding capability. */
    IMAGE,

    /** Audio understanding capability. */
    AUDIO,

    /** Video understanding capability. */
    VIDEO,

    /** Thinking mode capability (extended reasoning). */
    THINKING,

    /** Thinking budget control capability. */
    THINKING_BUDGET,

    /** Multi-agent formatter support for MsgHub conversations. */
    MULTI_AGENT_FORMATTER
}
