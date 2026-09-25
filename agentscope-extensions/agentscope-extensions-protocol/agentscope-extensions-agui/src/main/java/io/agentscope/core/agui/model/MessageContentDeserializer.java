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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * Custom Jackson deserializer for {@link MessageContent}.
 *
 * <p>Handles the AG-UI protocol's union type where {@code content} can be either:
 * <ul>
 *   <li>A JSON string &rarr; {@link MessageContent.Text}</li>
 *   <li>A JSON array of objects &rarr; {@link MessageContent.Blocks}</li>
 *   <li>A JSON null &rarr; {@code null}</li>
 * </ul>
 *
 * @author shanhongyu
 * @since 2026-08-01
 */
public class MessageContentDeserializer extends JsonDeserializer<MessageContent> {

    private static final TypeReference<List<InputContent>> INPUT_CONTENT_LIST =
            new TypeReference<>() {};

    @Override
    public MessageContent deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonToken token = p.currentToken();

        if (token == JsonToken.VALUE_STRING) {
            return new MessageContent.Text(p.getValueAsString());
        }

        if (token == JsonToken.START_ARRAY) {
            List<InputContent> parts = p.readValueAs(INPUT_CONTENT_LIST);
            return new MessageContent.Blocks(parts);
        }

        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        throw ctxt.wrongTokenException(
                p,
                MessageContent.class,
                token,
                "Expected STRING or START_ARRAY for 'content' field");
    }
}
