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

import java.beans.Transient;
import java.util.UUID;

public class Msg {

    private final String id;

    private final String name;

    private final MsgRole role;

    private final ContentBlock content;

    private Msg(String id, String name, MsgRole role, ContentBlock content) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content = content;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MsgRole getRole() {
        return role;
    }

    public ContentBlock getContent() {
        return content;
    }

    /**
     * Check if this message has text content.
     * @return true if the message contains text content
     */
    @Transient
    public boolean hasTextContent() {
        return ContentBlockUtils.hasTextContent(content);
    }

    /**
     * Check if this message has media content.
     * @return true if the message contains media content
     */
    @Transient
    public boolean hasMediaContent() {
        return ContentBlockUtils.hasMediaContent(content);
    }

    /**
     * Get text content from this message.
     * @return text content or empty string if not available
     */
    @Transient
    public String getTextContent() {
        return ContentBlockUtils.extractTextContent(content);
    }

    /**
     * Get a text representation of this message's content.
     * @return text representation including media descriptions
     */
    @Transient
    public String getContentAsText() {
        return ContentBlockUtils.toTextRepresentation(content);
    }

    public static class Builder {

        private String id;

        private String name;

        private MsgRole role;

        private ContentBlock content;

        public Builder() {
            randomId();
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        private void randomId() {
            this.id = UUID.randomUUID().toString();
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder role(MsgRole role) {
            this.role = role;
            return this;
        }

        public Builder content(ContentBlock content) {
            this.content = content;
            return this;
        }

        // Convenience methods for common content types
        public Builder textContent(String text) {
            this.content = TextBlock.builder().text(text).build();
            return this;
        }

        public Builder imageContent(Source source) {
            this.content = ImageBlock.builder().source(source).build();
            return this;
        }

        public Builder audioContent(Source source) {
            this.content = AudioBlock.builder().source(source).build();
            return this;
        }

        public Builder videoContent(Source source) {
            this.content = VideoBlock.builder().source(source).build();
            return this;
        }

        public Builder thinkingContent(String thinking) {
            this.content = ThinkingBlock.builder().text(thinking).build();
            return this;
        }

        public Msg build() {
            return new Msg(id, name, role, content);
        }
    }
}
