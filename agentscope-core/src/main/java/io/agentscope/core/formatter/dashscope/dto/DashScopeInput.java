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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DashScope API input container DTO.
 *
 * <p>This class wraps the messages array for DashScope API requests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeInput {

    /** The list of messages in the conversation. */
    @JsonProperty("messages")
    private List<DashScopeMessage> messages;

    public DashScopeInput() {}

    public DashScopeInput(List<DashScopeMessage> messages) {
        this.messages = messages;
    }

    public List<DashScopeMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<DashScopeMessage> messages) {
        this.messages = messages;
    }

    public static DashScopeInput of(List<DashScopeMessage> messages) {
        return new DashScopeInput(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<DashScopeMessage> messages;

        public Builder messages(List<DashScopeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public DashScopeInput build() {
            return new DashScopeInput(messages);
        }
    }
}
