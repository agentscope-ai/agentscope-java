package io.agentscope.core.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Options for streaming response. Only set this when you set `stream: true`.
 */
public class StreamOptions {

    /**
     * When true, stream obfuscation will be enabled. Stream obfuscation adds random characters to
     * an `obfuscation` field on streaming delta events to normalize payload sizes as a mitigation
     * to certain side-channel attacks. These obfuscation fields are included by default, but add a
     * small amount of overhead to the data stream. You can set `include_obfuscation` to false to
     * optimize for bandwidth if you trust the network links between your application and the OpenAI
     * API.
     */
    private Boolean includeObfuscation;

    /**
     * If set, an additional chunk will be streamed before the `data: [DONE]` message. The `usage`
     * field on this chunk shows the token usage statistics for the entire request, and the
     * `choices` field will always be an empty array.
     * <p>
     * All other chunks will also include a `usage` field, but with a null value. **NOTE:** If the
     * stream is interrupted, you may not receive the final usage chunk which contains the total
     * token usage for the request.
     */
    private Boolean includeUsage;

    /**
     * Additional properties.
     */
    private Map<String, Object> additionalProperties;

    public Boolean getIncludeObfuscation() {
        return includeObfuscation;
    }

    public Boolean getIncludeUsage() {
        return includeUsage;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Boolean includeObfuscation = Boolean.FALSE;

        private Boolean includeUsage = Boolean.TRUE;

        private Map<String, Object> additionalProperties = new HashMap<>();

        public Builder includeObfuscation(Boolean includeObfuscation) {
            this.includeObfuscation = includeObfuscation;
            return this;
        }

        public Builder includeUsage(Boolean includeUsage) {
            this.includeUsage = includeUsage;
            return this;
        }

        public Builder additionalProperty(Map<String, Object> additionalProperties) {
            this.additionalProperties.putAll(additionalProperties);
            return this;
        }

        public Builder additionalProperty(String key, Object value) {
            this.additionalProperties.put(key, value);
            return this;
        }

        public StreamOptions build() {
            StreamOptions options = new StreamOptions();
            options.includeObfuscation = this.includeObfuscation;
            options.includeUsage = this.includeUsage;
            options.additionalProperties = this.additionalProperties;
            return options;
        }
    }
}
