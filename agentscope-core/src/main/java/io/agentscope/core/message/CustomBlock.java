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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a custom extension block specifically designed to trigger AG-UI Custom events.
 *
 * <p>This block allows for the transmission of arbitrary JSON-serializable payloads
 * to the frontend. It is commonly used for real-time progress pushes, workflow
 * state changes, and any other customized interactive behaviors within the AG-UI protocol.
 *
 * <p>The name identifies the custom event type, and the value can be a Map, String,
 * Number, or any JSON-serializable object containing the event payload.
 */
public final class CustomBlock extends ContentBlock {

    private final String name;
    private final Object value;

    /**
     * Creates a new custom block for JSON deserialization.
     *
     * @param name The name of the custom event
     * @param value The arbitrary payload value associated with the event
     */
    @JsonCreator
    public CustomBlock(@JsonProperty("name") String name, @JsonProperty("value") Object value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Gets the name of this custom block.
     *
     * @return The event name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the payload value of this custom block.
     *
     * @return The payload object
     */
    public Object getValue() {
        return value;
    }

    /**
     * Creates a new builder for constructing CustomBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing CustomBlock instances.
     */
    public static class Builder {

        private String name;
        private Object value;

        /**
         * Sets the name for the custom block event.
         *
         * @param name The custom event name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the arbitrary payload value for the block.
         *
         * @param value payload object
         * @return This builder for chaining
         */
        public Builder value(Object value) {
            this.value = value;
            return this;
        }

        /**
         * Builds a new CustomBlock with the configured properties.
         *
         * @return A new CustomBlock instance
         */
        public CustomBlock build() {
            return new CustomBlock(name, value);
        }
    }
}
