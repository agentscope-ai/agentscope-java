package io.agentscope.core.mcp.schema;

import java.util.Optional;

/**
 * Common parameters for paginated requests.
 *
 * Auto-generated from MCP JSON schema.
 */
public record PaginatedRequestParams(Optional<String> cursor) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> cursor = Optional.empty(); // Optional field

        public Builder Cursor(String value) {
            this.cursor = Optional.of(value);
            return this;
        }

        public PaginatedRequestParams build() {
            return new PaginatedRequestParams(cursor);
        }
    }
}
