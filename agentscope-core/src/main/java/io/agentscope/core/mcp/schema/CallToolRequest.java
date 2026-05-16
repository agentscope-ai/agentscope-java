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

/**
 * Used by the client to invoke a tool provided by the server.
 *
 * Auto-generated from MCP JSON schema.
 */
public record CallToolRequest(Object id, String jsonrpc, String method, Object params) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Object id = null; // Required field
        private String jsonrpc = null; // Required field
        private String method = null; // Required field
        private Object params = null; // Required field

        public Builder Id(Object value) {
            this.id = value;
            return this;
        }

        public Builder Jsonrpc(String value) {
            this.jsonrpc = value;
            return this;
        }

        public Builder Method(String value) {
            this.method = value;
            return this;
        }

        public Builder Params(Object value) {
            this.params = value;
            return this;
        }

        public CallToolRequest build() {
            return new CallToolRequest(id, jsonrpc, method, params);
        }
    }
}
