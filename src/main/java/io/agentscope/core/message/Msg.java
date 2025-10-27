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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.beans.Transient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg {

    private final String id;

    private final String name;

    private final MsgRole role;

    private final List<ContentBlock> content;

    @JsonCreator
    private Msg(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") MsgRole role,
            @JsonProperty("content") List<ContentBlock> content) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content =
                content == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(content));
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

    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Check if this message has content blocks of the specified type (type-safe).
     * @param blockClass Block class to check for
     * @param <T> Content block type
     * @return true if at least one block of the type exists
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> boolean hasContentBlocks(Class<T> blockClass) {
        return content.stream().anyMatch(blockClass::isInstance);
    }

    /**
     * Get all content blocks of the specified type (type-safe).
     * @param blockClass Block class to filter for
     * @param <T> Content block type
     * @return List of matching blocks
     */
    @SuppressWarnings("unchecked")
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> List<T> getContentBlocks(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> (T) b)
                .collect(Collectors.toList());
    }

    /**
     * Get the first content block, or null if empty.
     * @return First content block or null
     */
    @Transient
    @JsonIgnore
    public ContentBlock getFirstContentBlock() {
        return content.isEmpty() ? null : content.get(0);
    }

    /**
     * Get the first content block of the specified type (type-safe).
     * @param blockClass Block class to search for
     * @param <T> Content block type
     * @return First matching block or null
     */
    @SuppressWarnings("unchecked")
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> T getFirstContentBlock(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> (T) b)
                .findFirst()
                .orElse(null);
    }

    public static class Builder {

        private String id;

        private String name;

        private MsgRole role;

        private List<ContentBlock> content;

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

        /**
         * Set content from a list of content blocks.
         * @param content List of content blocks
         * @return This builder
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * Set content from a single content block (convenience method).
         * The block will be wrapped in a list automatically.
         * @param block Single content block
         * @return This builder
         */
        public Builder content(ContentBlock block) {
            this.content = block == null ? List.of() : List.of(block);
            return this;
        }

        /**
         * Set content from varargs content blocks (convenience method).
         * @param blocks Content blocks
         * @return This builder
         */
        public Builder content(ContentBlock... blocks) {
            this.content = blocks == null ? List.of() : List.of(blocks);
            return this;
        }

        public Msg build() {
            return new Msg(id, name, role, content);
        }
    }
}
