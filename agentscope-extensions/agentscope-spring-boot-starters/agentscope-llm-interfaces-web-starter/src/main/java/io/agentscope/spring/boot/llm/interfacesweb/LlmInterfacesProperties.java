/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.llm.interfacesweb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the aggregated LLM interface endpoints. */
@ConfigurationProperties(prefix = "agentscope.llm-interfaces")
public class LlmInterfacesProperties {

    private boolean enabled = true;
    private String basePath = "/v1";
    private boolean ignoreUnknownFields = true;
    private boolean ignoreInvalidThinking = true;
    private Endpoint chat = new Endpoint(true);
    private Endpoint responses = new Endpoint(true);
    private Endpoint anthropic = new Endpoint(true);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public boolean isIgnoreUnknownFields() {
        return ignoreUnknownFields;
    }

    public void setIgnoreUnknownFields(boolean ignoreUnknownFields) {
        this.ignoreUnknownFields = ignoreUnknownFields;
    }

    public boolean isIgnoreInvalidThinking() {
        return ignoreInvalidThinking;
    }

    public void setIgnoreInvalidThinking(boolean ignoreInvalidThinking) {
        this.ignoreInvalidThinking = ignoreInvalidThinking;
    }

    public Endpoint getChat() {
        return chat;
    }

    public void setChat(Endpoint chat) {
        this.chat = chat;
    }

    public Endpoint getResponses() {
        return responses;
    }

    public void setResponses(Endpoint responses) {
        this.responses = responses;
    }

    public Endpoint getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Endpoint anthropic) {
        this.anthropic = anthropic;
    }

    public static class Endpoint {
        private boolean enabled;

        public Endpoint() {}

        public Endpoint(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
