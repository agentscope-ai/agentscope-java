/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.message;

public class ToolResultBlock extends ContentBlock {

    private final String id;
    private final String name;
    private final ContentBlock output;

    public ToolResultBlock(String id, String name, ContentBlock output) {
        this.id = id;
        this.name = name;
        this.output = output;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ContentBlock getOutput() {
        return output;
    }

    @Override
    public ContentBlockType getType() {
        return ContentBlockType.TOOL_USE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private ContentBlock output;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder output(ContentBlock output) {
            this.output = output;
            return this;
        }

        public ToolResultBlock build() {
            return new ToolResultBlock(id, name, output);
        }
    }
}
