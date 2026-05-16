package io.agentscope.core.mcp.schema;

import java.util.Optional;

/**
 * Sent from the client to request a list of tools the server has.
 *
 * Auto-generated from MCP JSON schema.
 */
public record ListToolsRequest(Object id, String jsonrpc, String method, Optional<Object> params) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Object id = null; // Required field
        private String jsonrpc = null; // Required field
        private String method = null; // Required field
        private Optional<Object> params = Optional.empty(); // Optional field

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
            this.params = Optional.of(value);
            return this;
        }

        public ListToolsRequest build() {
            return new ListToolsRequest(id, jsonrpc, method, params);
        }
    }
}
