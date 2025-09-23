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

public class AudioBlock extends ContentBlock {

    private final Source source;

    public AudioBlock(Source source) {
        this.source = source;
    }

    public Source getSource() {
        return source;
    }

    @Override
    public ContentBlockType getType() {
        return ContentBlockType.AUDIO;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Source source;

        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        public AudioBlock build() {
            return new AudioBlock(source);
        }
    }
}
