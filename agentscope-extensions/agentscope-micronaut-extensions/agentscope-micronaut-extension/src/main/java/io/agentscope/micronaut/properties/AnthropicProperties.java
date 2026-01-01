/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut.properties;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Anthropic provider specific settings.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: anthropic
 *   anthropic:
 *     enabled: true
 *     api-key: ${ANTHROPIC_API_KEY}
 *     model-name: claude-sonnet-4.5
 *     stream: true
 * }</pre>
 */
@ConfigurationProperties("agentscope.anthropic")
public class AnthropicProperties {

    /**
     * Whether Anthropic model auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * Anthropic API key.
     */
    private String apiKey;

    /**
     * Anthropic model name, for example {@code claude-sonnet-4.5}.
     */
    private String modelName = "claude-sonnet-4.5";

    /**
     * Optional base URL for compatible Anthropic endpoints.
     */
    private String baseUrl;

    /**
     * Whether streaming responses are enabled.
     */
    private boolean stream = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }
}
