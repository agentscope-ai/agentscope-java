package io.agentscope.core.mcp.schema;

import java.util.Map;
import java.util.Optional;

/**
 * Parameters for a `tools/call` request.
 *
 * Auto-generated from MCP JSON schema.
 */
public record CallToolRequestParams(Optional<Map<String, Object>> arguments, String name) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<Map<String, Object>> arguments = Optional.empty(); // Optional field
        private String name = null; // Required field

        public Builder Arguments(Map<String, Object> value) {
            this.arguments = Optional.of(value);
            return this;
        }

        public Builder Name(String value) {
            this.name = value;
            return this;
        }

        public CallToolRequestParams build() {
            return new CallToolRequestParams(arguments, name);
        }
    }
}
