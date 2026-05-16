package io.agentscope.core.mcp.schema;

import java.util.List;
import java.util.Optional;

/**
 * The server's response to a tools/list request from the client.
 *
 * Auto-generated from MCP JSON schema.
 */
public record ListToolsResult(Optional<String> nextCursor, List<Object> tools) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> nextCursor = Optional.empty(); // Optional field
        private List<Object> tools = null; // Required field

        public Builder NextCursor(String value) {
            this.nextCursor = Optional.of(value);
            return this;
        }

        public Builder Tools(List<Object> value) {
            this.tools = value;
            return this;
        }

        public ListToolsResult build() {
            return new ListToolsResult(nextCursor, tools);
        }
    }
}
