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
package io.agentscope.examples.planskillcombo;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed base class for stream message data, mirroring {@code ContentBlock}'s {@code
 *
 * @JsonTypeInfo} + {@code @JsonSubTypes} polymorphic design.
 *
 * <p>The {@code type} discriminator is managed by Jackson annotations on this base class:
 *
 * <ul>
 *   <li>{@code "thinking"} — {@link ThinkingMessage}
 *   <li>{@code "toolCall"} — {@link ToolCallMessage}
 *   <li>{@code "toolResult"} — {@link ToolResultMessage}
 *   <li>{@code "processText"} — {@link ProcessTextMessage} (intermediate reasoning text)
 *   <li>{@code "text"} — {@link TextMessage} (final response)
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ThinkingMessage.class, name = "thinking"),
    @JsonSubTypes.Type(value = ToolCallMessage.class, name = "toolCall"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "toolResult"),
    @JsonSubTypes.Type(value = ProcessTextMessage.class, name = "processText"),
    @JsonSubTypes.Type(value = TextMessage.class, name = "text")
})
public sealed class StreamMessage
        permits ThinkingMessage,
                ToolCallMessage,
                ToolResultMessage,
                ProcessTextMessage,
                TextMessage {}
