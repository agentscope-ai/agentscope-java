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
package io.agentscope.spring.boot.openaidecisions;

import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAI Decisions settings. */
@ConfigurationProperties(prefix = "agentscope.openai-decisions")
public class OpenAIDecisionsProperties {

    /** Whether the OpenAI Decisions client auto-configuration is enabled. */
    private boolean enabled = true;

    /** OpenAI API key; falls back to OPENAI_API_KEY when unset. */
    private String apiKey;

    /** OpenAI API base URL, including /v1. */
    private String baseUrl = OpenAIDecisionsClient.DEFAULT_BASE_URL;

    /** OpenAI Decisions model name. */
    private String model = OpenAIDecisionsClient.DEFAULT_MODEL;

    /** Request timeout. */
    private Duration timeout = OpenAIDecisionsClient.DEFAULT_TIMEOUT;

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
