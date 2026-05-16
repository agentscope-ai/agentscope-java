package io.agentscope.core.mcp.schema;

import java.util.Map;

/**
 * Parameters for an `initialize` request.
 *
 * Auto-generated from MCP JSON schema.
 */
public record InitializeRequestParams(
        Map<String, Object> capabilities, Map<String, Object> clientInfo, String protocolVersion) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, Object> capabilities = null; // Required field
        private Map<String, Object> clientInfo = null; // Required field
        private String protocolVersion = null; // Required field

        public Builder Capabilities(Map<String, Object> value) {
            this.capabilities = value;
            return this;
        }

        public Builder ClientInfo(Map<String, Object> value) {
            this.clientInfo = value;
            return this;
        }

        public Builder ProtocolVersion(String value) {
            this.protocolVersion = value;
            return this;
        }

        public InitializeRequestParams build() {
            return new InitializeRequestParams(capabilities, clientInfo, protocolVersion);
        }
    }
}
