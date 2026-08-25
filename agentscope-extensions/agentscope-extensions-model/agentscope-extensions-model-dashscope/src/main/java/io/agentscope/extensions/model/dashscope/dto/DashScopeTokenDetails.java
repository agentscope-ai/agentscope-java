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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Fine-grained DashScope input token usage details. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeTokenDetails {

    @JsonProperty("cached_tokens")
    private Integer cachedTokens;

    @JsonProperty("cache_creation_input_tokens")
    private Integer cacheCreationInputTokens;

    @JsonProperty("cache_creation")
    private CacheCreationDetails cacheCreation;

    public Integer getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(Integer cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public Integer getCacheCreationInputTokens() {
        if (cacheCreationInputTokens != null) {
            return cacheCreationInputTokens;
        }
        return cacheCreation != null ? cacheCreation.getInputTokens() : null;
    }

    public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public CacheCreationDetails getCacheCreation() {
        return cacheCreation;
    }

    public void setCacheCreation(CacheCreationDetails cacheCreation) {
        this.cacheCreation = cacheCreation;
    }

    /** Current nested DashScope explicit-cache creation details. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CacheCreationDetails {

        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;

        @JsonProperty("ephemeral_5m_input_tokens")
        private Integer ephemeral5mInputTokens;

        public Integer getCacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) {
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }

        public Integer getEphemeral5mInputTokens() {
            return ephemeral5mInputTokens;
        }

        public void setEphemeral5mInputTokens(Integer ephemeral5mInputTokens) {
            this.ephemeral5mInputTokens = ephemeral5mInputTokens;
        }

        Integer getInputTokens() {
            return cacheCreationInputTokens != null
                    ? cacheCreationInputTokens
                    : ephemeral5mInputTokens;
        }
    }
}
