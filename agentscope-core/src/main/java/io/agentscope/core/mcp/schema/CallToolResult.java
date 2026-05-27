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

import java.util.List;
import java.util.Optional;

/**
 * The server's response to a tool call.
 *
 * Auto-generated from MCP JSON schema.
 */
public record CallToolResult(List<Object> content, Optional<Boolean> isError) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Object> content = null; // Required field
        private Optional<Boolean> isError = Optional.empty(); // Optional field

        public Builder Content(List<Object> value) {
            this.content = value;
            return this;
        }

        public Builder IsError(Boolean value) {
            this.isError = Optional.of(value);
            return this;
        }

        public CallToolResult build() {
            return new CallToolResult(content, isError);
        }
    }
}
