/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.model.ollama;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.List;

/**
 * Defines the configuration contract for the "thinking" capability (Chain of Thought) in AgentScope's Ollama integration.
 * This interface allows users to control how the underlying model exposes its internal reasoning process.
 * <p>
 * The configuration supports two distinct strategies:
 * <ul>
 *   <li>{@link ThinkBoolean}: A simple toggle to enable or disable thinking (suitable for models like DeepSeek R1).</li>
 *   <li>{@link ThinkLevel}: A granular control to set the depth of reasoning (e.g., "low", "medium", "high" for models like GPT-OSS).</li>
 * </ul>
 *
 *
 *
 */
@JsonSerialize(using = ThinkOption.ThinkOptionSerializer.class)
@JsonDeserialize(using = ThinkOption.ThinkOptionDeserializer.class)
public sealed interface ThinkOption {

    /**
     * Transforms the current option into a format compatible with the Ollama JSON API.
     *
     * @return A {@link Boolean} for toggle-based options, or a {@link String} for level-based options.
     */
    Object toJsonValue();

    /**
     * Custom serializer implementation for {@link ThinkOption}.
     * Responsible for converting the option object into the appropriate JSON primitive (boolean or string).
     */
    class ThinkOptionSerializer extends JsonSerializer<ThinkOption> {

        @Override
        public void serialize(ThinkOption value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeObject(value.toJsonValue());
            }
        }
    }

    /**
     * Custom deserializer implementation for {@link ThinkOption}.
     * Detects the JSON token type (boolean or string) and instantiates the corresponding {@link ThinkOption} implementation.
     */
    class ThinkOptionDeserializer extends JsonDeserializer<ThinkOption> {

        @Override
        public ThinkOption deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_TRUE) {
                return ThinkBoolean.ENABLED;
            } else if (token == JsonToken.VALUE_FALSE) {
                return ThinkBoolean.DISABLED;
            } else if (token == JsonToken.VALUE_STRING) {
                return new ThinkLevel(p.getValueAsString());
            } else if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            throw new IOException(
                    "Unable to deserialize ThinkOption. Encountered unexpected token type: "
                            + token);
        }
    }

    /**
     * Implementation of {@link ThinkOption} representing a binary state (Enabled/Disabled).
     * Ideal for models that do not support granular thinking levels.
     *
     * @param enabled The state of the thinking capability.
     */
    record ThinkBoolean(boolean enabled) implements ThinkOption {

        /**
         * Constant representing the enabled state for thinking.
         */
        public static final ThinkBoolean ENABLED = new ThinkBoolean(true);

        /**
         * Constant representing the disabled state for thinking.
         */
        public static final ThinkBoolean DISABLED = new ThinkBoolean(false);

        @Override
        public Object toJsonValue() {
            return this.enabled;
        }
    }

    /**
     * Implementation of {@link ThinkOption} representing a specific level of reasoning effort.
     * Designed for models that allow tuning the "effort" or "depth" of the chain of thought.
     *
     * @param level The desired level of thinking (must be one of: "low", "medium", "high").
     */
    record ThinkLevel(String level) implements ThinkOption {

        private static final List<String> VALID_LEVELS = List.of("low", "medium", "high");

        /**
         * Predefined constant for low-effort thinking.
         */
        public static final ThinkLevel LOW = new ThinkLevel("low");

        /**
         * Predefined constant for medium-effort thinking.
         */
        public static final ThinkLevel MEDIUM = new ThinkLevel("medium");

        /**
         * Predefined constant for high-effort thinking.
         */
        public static final ThinkLevel HIGH = new ThinkLevel("high");

        /**
         * Constructs a new ThinkLevel with validation.
         *
         * @param level The thinking level string.
         * @throws IllegalArgumentException if the provided level is not within the valid set.
         */
        public ThinkLevel {
            if (level != null && !VALID_LEVELS.contains(level)) {
                throw new IllegalArgumentException(
                        "Invalid thinking level provided: '"
                                + level
                                + "'. Supported levels are: "
                                + VALID_LEVELS);
            }
        }

        @Override
        public Object toJsonValue() {
            return this.level;
        }
    }
}
