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
