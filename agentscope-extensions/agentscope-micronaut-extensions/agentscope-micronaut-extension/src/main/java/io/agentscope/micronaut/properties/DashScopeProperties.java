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
 * DashScope provider specific configuration properties.
 *
 * <p>Example configuration (default provider when {@code agentscope.model.provider} is omitted):
 *
 * <pre>{@code
 * agentscope:
 *   dashscope:
 *     enabled: true
 *     api-key: ${DASHSCOPE_API_KEY}
 *     model-name: qwen-plus
 *     stream: true
 *     enable-thinking: true
 * }</pre>
 */
@ConfigurationProperties("agentscope.dashscope")
public class DashScopeProperties {

    /**
     * Whether DashScope model auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * DashScope API key.
     */
    private String apiKey;

    /**
     * DashScope model name, for example {@code qwen-plus}.
     */
    private String modelName = "qwen-plus";

    /**
     * Whether streaming responses are enabled.
     */
    private boolean stream = true;

    /**
     * Whether to enable thinking mode for compatible Qwen models.
     */
    private Boolean enableThinking;

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

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }
}
