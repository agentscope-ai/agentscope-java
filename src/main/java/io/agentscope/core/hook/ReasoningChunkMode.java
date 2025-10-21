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
package io.agentscope.core.hook;

/**
 * Enum defining the mode for reasoning chunk callbacks in hooks.
 *
 * <p>This allows hooks to specify whether they want to receive incremental chunks (only new
 * content) or cumulative messages (all content accumulated so far) during streaming reasoning.
 */
public enum ReasoningChunkMode {
    /**
     * Incremental mode: Hook receives only the new content chunk generated in this streaming
     * event. This is useful for real-time display where you want to append only the new text.
     */
    INCREMENTAL,

    /**
     * Cumulative mode: Hook receives the complete accumulated message containing all content
     * generated so far. This is useful when you need the full context up to the current point.
     */
    CUMULATIVE
}
