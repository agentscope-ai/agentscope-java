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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;

/**
 * Type-safe union representing the {@code content} field of an AG-UI message.
 *
 * <p>In the AG-UI protocol, message content can be either a plain string or an
 * array of structured {@link InputContent} parts. This sealed interface models
 * that union without resorting to {@code Object}, providing compile-time type
 * safety and exhaustive pattern matching.
 *
 * <p>JSON forms:
 * <ul>
 *   <li>{@link Text} &rarr; JSON string: {@code "Hello"}</li>
 *   <li>{@link Blocks} &rarr; JSON array: {@code [{"type":"text","text":"Hi"},{"type":"image",...}]}</li>
 * </ul>
 *
 * <p>Custom Jackson serialization/deserialization handles the non-object JSON
 * shape (scalar vs. array) via {@link MessageContentDeserializer} and
 * {@link MessageContentSerializer}.
 *
 * @see InputContent
 * @see AguiMessage
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonDeserialize(using = MessageContentDeserializer.class)
@JsonSerialize(using = MessageContentSerializer.class)
public sealed interface MessageContent permits MessageContent.Text, MessageContent.Blocks {

    /**
     * Plain text content, serialized as a JSON string.
     *
     * @param value the text content
     */
    record Text(String value) implements MessageContent {}

    /**
     * Structured content blocks, serialized as a JSON array of {@link InputContent}.
     *
     * @param parts the list of content parts; defensively copied to ensure immutability
     */
    record Blocks(List<InputContent> parts) implements MessageContent {

        /**
         * Compact constructor that creates an immutable copy of the parts list.
         *
         * @param parts the content parts
         */
        public Blocks {
            parts = List.copyOf(parts);
        }
    }
}
