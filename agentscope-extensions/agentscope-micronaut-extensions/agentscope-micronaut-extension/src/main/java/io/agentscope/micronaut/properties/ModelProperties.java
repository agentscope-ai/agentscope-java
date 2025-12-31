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
 * Generic model selection properties that allow choosing which provider to use.
 *
 * <p>Example for selecting a provider:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: openai # or dashscope, gemini, anthropic
 * }</pre>
 */
@ConfigurationProperties("agentscope.model")
public class ModelProperties {

    /**
     * Selected model provider. Valid values: {@code dashscope}, {@code openai}, {@code gemini},
     * {@code anthropic}.
     *
     * <p>Defaults to {@code dashscope} if omitted.
     */
    private String provider;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
