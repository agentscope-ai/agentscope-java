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

package io.agentscope.core.mcp.schema;

import java.util.Map;
import java.util.Optional;

/**
 * Tool definition as described in the MCP specification.
 *
 * <p>This record represents the wire-format definition of a tool (also called a resource or
 * function). To avoid confusion with {@link io.agentscope.core.mcp.tool.Tool} (the execution
 * SPI), this schema type is named ToolDefinition.
 */
public record ToolDefinition(
        Optional<String> description, Map<String, Object> inputSchema, String name) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> description = Optional.empty(); // Optional field
        private Map<String, Object> inputSchema = null; // Required field
        private String name = null; // Required field

        public Builder Description(String value) {
            this.description = Optional.of(value);
            return this;
        }

        public Builder InputSchema(Map<String, Object> value) {
            this.inputSchema = value;
            return this;
        }

        public Builder Name(String value) {
            this.name = value;
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(description, inputSchema, name);
        }
    }
}
