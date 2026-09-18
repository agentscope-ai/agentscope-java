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
package io.agentscope.spring.boot.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ollama provider specific settings.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: ollama
 *   ollama:
 *     enabled: true
 *     model-name: llama3
 *     # base-url: http://localhost:11434 # optional
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.ollama")
public class OllamaProperties {

    /**
     * Whether Ollama model auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * Ollama model name, for example {@code llama3}.
     */
    private String modelName = "llama3";

    /**
     * Optional base URL for the Ollama server.
     */
    private String baseUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}
