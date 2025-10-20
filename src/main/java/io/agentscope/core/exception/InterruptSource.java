/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.exception;

/**
 * Enumeration representing the source of an interruption.
 *
 * <p>This helps distinguish between different types of interruptions
 * for better error handling and logging.
 */
public enum InterruptSource {
    /**
     * Interruption initiated by the user (e.g., calling agent.interrupt()).
     */
    USER,

    /**
     * Interruption initiated by a tool (e.g., calling ToolInterrupter.interrupt()).
     */
    TOOL,

    /**
     * Interruption initiated by the system (e.g., timeout, resource limits).
     */
    SYSTEM
}
