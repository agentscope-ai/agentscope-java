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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * Custom Jackson serializer for {@link MessageContent}.
 *
 * <p>Serializes the union type transparently:
 * <ul>
 *   <li>{@link MessageContent.Text} &rarr; JSON string</li>
 *   <li>{@link MessageContent.Blocks} &rarr; JSON array of {@link InputContent}</li>
 * </ul>
 *
 * @author shanhongyu
 * @since 2026-08-01
 */
public class MessageContentSerializer extends JsonSerializer<MessageContent> {

    @Override
    public void serialize(MessageContent value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value instanceof MessageContent.Text text) {
            gen.writeString(text.value());
        } else if (value instanceof MessageContent.Blocks blocks) {
            gen.writeStartArray();
            for (InputContent part : blocks.parts()) {
                gen.writeObject(part);
            }
            gen.writeEndArray();
        }
    }
}
