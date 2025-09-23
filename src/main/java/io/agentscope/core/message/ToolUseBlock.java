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
package io.agentscope.core.message;

import java.util.Map;

public class ToolUseBlock extends ContentBlock {

    private final ContentBlockType type = ContentBlockType.TOOL_USE;
    private final String id;
    private final String name;
    private final Map<String, Object> input;
    private final String content; // Raw content for streaming tool calls

    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        this.id = id;
        this.name = name;
        this.input = input;
        this.content = null;
    }

    public ToolUseBlock(String id, String name, Map<String, Object> input, String content) {
        this.id = id;
        this.name = name;
        this.input = input;
        this.content = content;
    }

    @Override
    public ContentBlockType getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public String getContent() {
        return content;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private Map<String, Object> input;
        private String content;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public ToolUseBlock build() {
            if (content != null) {
                return new ToolUseBlock(id, name, input, content);
            }
            return new ToolUseBlock(id, name, input);
        }
    }
}
