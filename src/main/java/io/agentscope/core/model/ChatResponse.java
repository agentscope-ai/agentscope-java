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
package io.agentscope.core.model;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;

public class ChatResponse {

    private final String id;
    private final List<ContentBlock> content;
    private final ChatUsage usage;
    private final Map<String, Object> metadata;

    public ChatResponse(
            String id, List<ContentBlock> content, ChatUsage usage, Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.usage = usage;
        this.metadata = metadata;
    }

    public String getId() {
        return id;
    }

    public List<ContentBlock> getContent() {
        return content;
    }

    public ChatUsage getUsage() {
        return usage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private List<ContentBlock> content;
        private ChatUsage usage;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(id, content, usage, metadata);
        }
    }
}
