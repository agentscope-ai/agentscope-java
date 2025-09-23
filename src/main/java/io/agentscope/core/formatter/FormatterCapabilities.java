/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter;

import io.agentscope.core.message.ContentBlock;
import java.util.Set;

/**
 * Represents the capabilities of a formatter.
 *
 * This class describes what features and content types a specific
 * formatter implementation supports, following the Python agentscope pattern.
 */
public class FormatterCapabilities {

    private final boolean supportToolsApi;
    private final boolean supportMultiAgent;
    private final boolean supportVision;
    private final Set<Class<? extends ContentBlock>> supportedBlocks;
    private final String providerName;

    private FormatterCapabilities(Builder builder) {
        this.supportToolsApi = builder.supportToolsApi;
        this.supportMultiAgent = builder.supportMultiAgent;
        this.supportVision = builder.supportVision;
        this.supportedBlocks = Set.copyOf(builder.supportedBlocks);
        this.providerName = builder.providerName;
    }

    /**
     * Whether this formatter supports tool/function calling APIs.
     *
     * @return true if tool API is supported
     */
    public boolean supportsToolsApi() {
        return supportToolsApi;
    }

    /**
     * Whether this formatter supports multi-agent conversations.
     *
     * @return true if multi-agent is supported
     */
    public boolean supportsMultiAgent() {
        return supportMultiAgent;
    }

    /**
     * Whether this formatter supports vision (images, videos).
     *
     * @return true if vision is supported
     */
    public boolean supportsVision() {
        return supportVision;
    }

    /**
     * Get the set of supported content block types.
     *
     * @return Set of supported content block classes
     */
    public Set<Class<? extends ContentBlock>> getSupportedBlocks() {
        return supportedBlocks;
    }

    /**
     * Get the provider name (e.g., "OpenAI", "Anthropic").
     *
     * @return Provider name
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Check if a specific content block type is supported.
     *
     * @param blockType Content block type to check
     * @return true if supported
     */
    public boolean supportsBlockType(Class<? extends ContentBlock> blockType) {
        return supportedBlocks.contains(blockType);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean supportToolsApi = false;
        private boolean supportMultiAgent = false;
        private boolean supportVision = false;
        private Set<Class<? extends ContentBlock>> supportedBlocks = Set.of();
        private String providerName = "Unknown";

        public Builder supportToolsApi(boolean supportToolsApi) {
            this.supportToolsApi = supportToolsApi;
            return this;
        }

        public Builder supportMultiAgent(boolean supportMultiAgent) {
            this.supportMultiAgent = supportMultiAgent;
            return this;
        }

        public Builder supportVision(boolean supportVision) {
            this.supportVision = supportVision;
            return this;
        }

        public Builder supportedBlocks(Set<Class<? extends ContentBlock>> supportedBlocks) {
            this.supportedBlocks = supportedBlocks;
            return this;
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public FormatterCapabilities build() {
            return new FormatterCapabilities(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "FormatterCapabilities{provider='%s', tools=%s, multiAgent=%s, vision=%s,"
                        + " blocks=%d}",
                providerName,
                supportToolsApi,
                supportMultiAgent,
                supportVision,
                supportedBlocks.size());
    }
}
