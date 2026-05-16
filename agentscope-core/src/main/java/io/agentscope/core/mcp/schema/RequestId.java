package io.agentscope.core.mcp.schema;

/**
 * A uniquely identifying ID for a request in JSON-RPC.
 *
 * Auto-generated from MCP JSON schema.
 */
public record RequestId() {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        public RequestId build() {
            return new RequestId();
        }
    }
}
