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
package io.agentscope.spring.boot.openaiofficial;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI Official provider specific settings.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: openai-official
 *   openai-official:
 *     enabled: true
 *     api-key: ${OPENAI_API_KEY}
 *     model-name: gpt-4o
 *     # base-url: https://api.openai.com/v1 # optional
 *     stream: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.openai-official")
public class OpenAIOfficialProperties {

    /**
     * Whether OpenAI Official model auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * OpenAI API key. When unset, the model extension falls back to the
     * {@code OPENAI_API_KEY} environment variable.
     */
    private String apiKey;

    /**
     * OpenAI model name, for example {@code gpt-4o}.
     */
    private String modelName = "gpt-4o";

    /**
     * Optional OpenAI API base URL. When unset, the model extension falls back to the
     * {@code OPENAI_BASE_URL} environment variable and then the SDK default.
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
