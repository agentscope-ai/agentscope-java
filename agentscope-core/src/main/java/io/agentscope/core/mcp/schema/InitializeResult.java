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

/**
 * Server initialization response.
 *
 * Auto-generated from MCP JSON schema.
 */
public record InitializeResult(
        Map<String, Object> capabilities, String protocolVersion, Map<String, Object> serverInfo) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, Object> capabilities = null; // Required field
        private String protocolVersion = null; // Required field
        private Map<String, Object> serverInfo = null; // Required field

        public Builder Capabilities(Map<String, Object> value) {
            this.capabilities = value;
            return this;
        }

        public Builder ProtocolVersion(String value) {
            this.protocolVersion = value;
            return this;
        }

        public Builder ServerInfo(Map<String, Object> value) {
            this.serverInfo = value;
            return this;
        }

        public InitializeResult build() {
            return new InitializeResult(capabilities, protocolVersion, serverInfo);
        }
    }
}
