package io.agentscope.core.mcp.schema;

/**
 * Text provided to or from an LLM.
 *
 * Auto-generated from MCP JSON schema.
 */
public record TextContent(String text, String type) {

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text = null; // Required field
        private String type = null; // Required field

        public Builder Text(String value) {
            this.text = value;
            return this;
        }

        public Builder Type(String value) {
            this.type = value;
            return this;
        }

        public TextContent build() {
            return new TextContent(text, type);
        }
    }
}
