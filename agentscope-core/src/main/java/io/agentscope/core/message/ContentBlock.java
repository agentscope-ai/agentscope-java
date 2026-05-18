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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.state.State;

/**
 * Base sealed class for all content blocks in messages.
 *
 * <p>Content blocks represent different types of content that can be included in a message,
 * such as text, images, audio, video, or thinking content. This sealed hierarchy ensures
 * type safety and enables exhaustive pattern matching.
 *
 * <p><b>Supported Content Types:</b>
 * <ul>
 *   <li>{@link TextBlock} - Plain text content
 *   <li>{@link ThinkingBlock} - Agent reasoning/thinking content
 *   <li>{@link ImageBlock} - Image content (URL or Base64)
 *   <li>{@link AudioBlock} - Audio content (URL or Base64)
 *   <li>{@link VideoBlock} - Video content (URL or Base64)
 *   <li>{@link ToolUseBlock} - Tool execution requests
 *   <li>{@link ToolResultBlock} - Tool execution results
 * </ul>
 *
 * <p>Uses Jackson annotations for polymorphic JSON serialization with the "type" discriminator
 * field. The sealed modifier restricts subclasses to the specified permits list, enabling
 * compile-time exhaustiveness checking in pattern matching.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public sealed class ContentBlock implements State
        permits TextBlock,
                ImageBlock,
                AudioBlock,
                VideoBlock,
                ThinkingBlock,
                ToolUseBlock,
                ToolResultBlock {}
