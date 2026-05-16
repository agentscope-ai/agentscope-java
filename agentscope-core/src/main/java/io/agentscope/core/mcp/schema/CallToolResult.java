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
