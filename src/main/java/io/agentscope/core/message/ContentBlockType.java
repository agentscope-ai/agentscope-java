/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

/**
 * Enumeration of content block types supported in AgentScope messages.
 *
 * <p>This enum defines all the different types of content blocks that can be
 * included in messages, enabling rich multimodal communication between agents,
 * users, and tools.
 *
 * <p>Supported content types:
 * <ul>
 *   <li>{@link #TEXT} - Plain text content for standard textual communication</li>
 *   <li>{@link #IMAGE} - Image content from URLs or Base64 encoding</li>
 *   <li>{@link #AUDIO} - Audio content from URLs or Base64 encoding</li>
 *   <li>{@link #VIDEO} - Video content from URLs or Base64 encoding</li>
 *   <li>{@link #THINKING} - Internal reasoning/thinking process content</li>
 *   <li>{@link #TOOL_USE} - Tool invocation requests and results</li>
 * </ul>
 *
 * <p>Each content block type corresponds to a specific implementation class
 * that extends {@link ContentBlock}.
 */
public enum ContentBlockType {
    /**
     * Plain text content block type.
     *
     * @see TextBlock
     */
    TEXT,

    /**
     * Image content block type.
     *
     * @see ImageBlock
     */
    IMAGE,

    /**
     * Audio content block type.
     *
     * @see AudioBlock
     */
    AUDIO,

    /**
     * Video content block type.
     *
     * @see VideoBlock
     */
    VIDEO,

    /**
     * Thinking/reasoning content block type.
     *
     * @see ThinkingBlock
     */
    THINKING,

    /**
     * Tool use/request content block type.
     * Also used for tool results.
     *
     * @see ToolUseBlock
     * @see ToolResultBlock
     */
    TOOL_USE
}
