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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A {@link Msg} with {@link MsgRole#TOOL} pinned at construction time.
 *
 * <p>Carries one or more {@link ToolResultBlock}s as its content. The
 * three-argument constructor takes the tool call id, tool name and a result
 * string, automatically wrapping the string in a {@link TextBlock} inside the
 * {@link ToolResultBlock}.
 *
 * <p>Also offers an independent {@link Builder} that lets callers accumulate
 * multiple tool results, attach metadata, or set timestamp/usage explicitly.
 */
public final class ToolResultMessage extends Msg {

    public ToolResultMessage(String toolCallId, String toolName, String resultText) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                List.of(
                        new ToolResultBlock(
                                toolCallId,
                                toolName,
                                null,
                                List.of(TextBlock.builder().text(resultText).build()))),
                null,
                currentTimestamp(),
                null);
    }

    public ToolResultMessage(ToolResultBlock... results) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                results == null ? List.of() : List.copyOf(Arrays.asList(results)),
                null,
                currentTimestamp(),
                null);
    }

    public ToolResultMessage(List<ToolResultBlock> results) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                results == null ? List.of() : List.copyOf(results),
                null,
                currentTimestamp(),
                null);
    }

    @JsonCreator
    private ToolResultMessage(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") MsgRole role,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, name, MsgRole.TOOL, content, metadata, timestamp, usage);
    }

    /**
     * Creates a builder for {@link ToolResultMessage} with a randomly generated
     * ID and the current timestamp pre-populated.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ToolResultMessage}. The role is fixed to
     * {@link MsgRole#TOOL} and {@link #role(MsgRole)} is unsupported.
     *
     * <p>{@link #result(String, String, String)} and {@link #result(ToolResultBlock)}
     * append to the accumulating result list; {@link #results(ToolResultBlock...)}
     * and {@link #results(List)} (and the inherited {@code content(...)} setters)
     * replace it wholesale.
     */
    public static final class Builder extends Msg.Builder {

        public Builder() {
            super.role = MsgRole.TOOL;
            super.content = new ArrayList<>();
        }

        /** Unsupported on {@link ToolResultMessage.Builder}: role is fixed to TOOL. */
        @Override
        public Builder role(MsgRole role) {
            throw new UnsupportedOperationException(
                    "ToolResultMessage role is fixed to TOOL; use Msg.builder() to set role.");
        }

        /**
         * Appends a {@link ToolResultBlock} built from a tool call id, tool name
         * and result text.
         */
        public Builder result(String toolCallId, String toolName, String resultText) {
            return result(
                    new ToolResultBlock(
                            toolCallId,
                            toolName,
                            null,
                            List.of(TextBlock.builder().text(resultText).build())));
        }

        /** Appends a single {@link ToolResultBlock}. */
        public Builder result(ToolResultBlock block) {
            if (block != null) {
                if (!(super.content instanceof ArrayList)) {
                    super.content = new ArrayList<>(super.content);
                }
                ((ArrayList<ContentBlock>) super.content).add(block);
            }
            return this;
        }

        /** Replaces all accumulated results with the given list. */
        public Builder results(List<ToolResultBlock> results) {
            super.content = results == null ? new ArrayList<>() : new ArrayList<>(results);
            return this;
        }

        /** Replaces all accumulated results with the given blocks. */
        public Builder results(ToolResultBlock... results) {
            super.content =
                    results == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(results));
            return this;
        }

        @Override
        public Builder id(String id) {
            super.id(id);
            return this;
        }

        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        @Override
        public Builder content(List<ContentBlock> content) {
            super.content(content);
            return this;
        }

        @Override
        public Builder content(ContentBlock block) {
            super.content(block);
            return this;
        }

        @Override
        public Builder content(ContentBlock... blocks) {
            super.content(blocks);
            return this;
        }

        @Override
        public Builder textContent(String text) {
            super.textContent(text);
            return this;
        }

        @Override
        public Builder metadata(Map<String, Object> metadata) {
            super.metadata(metadata);
            return this;
        }

        @Override
        public Builder timestamp(String timestamp) {
            super.timestamp(timestamp);
            return this;
        }

        @Override
        public Builder usage(ChatUsage usage) {
            super.usage(usage);
            return this;
        }

        @Override
        public Builder generateReason(GenerateReason reason) {
            super.generateReason(reason);
            return this;
        }

        @Override
        public ToolResultMessage build() {
            return new ToolResultMessage(
                    id, name, MsgRole.TOOL, List.copyOf(content), metadata, timestamp, usage);
        }
    }
}
