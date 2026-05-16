package io.agentscope.core.mcp.schema;

import java.util.Map;
import java.util.Optional;

/**
 * Definition for a tool the client can call.
 *
 * Auto-generated from MCP JSON schema.
 */
public record Tool(Optional<String> description, Map<String, Object> inputSchema, String name) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> description = Optional.empty(); // Optional field
        private Map<String, Object> inputSchema = null; // Required field
        private String name = null; // Required field

        public Builder Description(String value) {
            this.description = Optional.of(value);
            return this;
        }

        public Builder InputSchema(Map<String, Object> value) {
            this.inputSchema = value;
            return this;
        }

        public Builder Name(String value) {
            this.name = value;
            return this;
        }

        public Tool build() {
            return new Tool(description, inputSchema, name);
        }
    }
}
