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
