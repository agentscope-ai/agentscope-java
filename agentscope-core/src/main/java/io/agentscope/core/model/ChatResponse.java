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
package io.agentscope.core.model;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一模型响应对象，非流和流模式共用，屏蔽不同 LLM 厂商的差异。
 *
 * <p>非流模式（stream=false）：
 * <pre>{@code
 *   ChatResponse response = model.call(messages).block();
 *   // response.content 是完整的 ContentBlock 列表
 * }</pre>
 *
 * <p>流模式（stream=true）：
 * <pre>{@code
 *   model.stream(messages)
 *     .doOnNext(response -> { ... })  // 每个 SSE chunk(例如DashScopeResponse) 都是一个 ChatResponse
 *     .blockLast();
 * }</pre>
 * <p>流式中每个 chunk(DashScopeResponse) 是独立的 ChatResponse，content 只含该 chunk 的增量 Block，
 * 当前 chunk 没有的内容类型（如 reasoning_content、tool_calls）对应 Block 就不出现。
 * 上层（ReActAgent）负责累积各 chunk 的 Block，拼接成完整 Msg。
 *
 * <p>LLM API 响应 → ChatResponse 字段映射（以 DashScope 为例）：
 * <pre>{@code
 * API response
 * (例如DashScopeResponse)      ChatResponse
 * ─────────────────────────────────────────────────────
 * request_id                     ──→    id
 * output.choices[0].message {
 *   reasoning_content            ──→    content[0] = ThinkingBlock
 *   content                      ──→    content[1] = TextBlock
 *   tool_calls[]                 ──→    content[2..] = ToolUseBlock (每个)
 * }
 * output.choices[0].finish_reason ──→   finishReason
 * usage.input_tokens              ──→   usage.inputTokens
 * usage.output_tokens             ──→   usage.outputTokens
 * (now - startTime)               ──→   usage.time
 * }</pre>
 *
 * <p>finish_reason 取值：
 * <ul>
 *   <li>"stop" — 正常结束</li>
 *   <li>"length" — 达到 max_tokens 上限被截断</li>
 *   <li>"tool_calls" — 模型要求调用工具</li>
 *   <li>null — 流式中间块，尚未完成</li>
 * </ul>
 *
 * <p>content 中 Block 的组装顺序（DashScopeResponseParser 保证）：
 * ThinkingBlock → TextBlock → ToolUseBlock
 */
public class ChatResponse {

    private final String id;
    private final List<ContentBlock> content;
    private final ChatUsage usage;
    private final Map<String, Object> metadata;
    private final String finishReason;

    /**
     * Creates a new ChatResponse instance.
     *
     * @param id the unique identifier for this response
     * @param content the list of content blocks containing the response content
     * @param usage the token usage information for this response
     * @param metadata additional metadata from the model provider
     * @param finishReason the reason for
     */
    public ChatResponse(
            String id,
            List<ContentBlock> content,
            ChatUsage usage,
            Map<String, Object> metadata,
            String finishReason) {
        this.id = id;
        this.content = content;
        this.usage = usage;
        this.metadata = metadata;
        this.finishReason = finishReason;
    }

    /**
     * Gets the unique identifier for this response.
     *
     * @return the response identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the content blocks containing the response content.
     *
     * @return the list of content blocks
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Gets the token usage information for this response.
     *
     * @return the usage information
     */
    public ChatUsage getUsage() {
        return usage;
    }

    /**
     * Gets the metadata from the model provider.
     *
     * @return the metadata map
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets the reason the model stopped generating tokens.
     *
     * @return the finish reason
     * */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Creates a new instance of ChatResponse with the specified ID,
     * copying all other fields from this instance.
     *
     * @param newId the new identifier
     * @return a new ChatResponse instance
     */
    public ChatResponse withId(String newId) {
        return new ChatResponse(newId, this.content, this.usage, this.metadata, this.finishReason);
    }

    /**
     * Creates a new builder for ChatResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ChatResponse instances.
     */
    public static class Builder {
        private String id;
        private List<ContentBlock> content;
        private ChatUsage usage;
        private Map<String, Object> metadata;
        private String finishReason;

        /**
         * Sets the response identifier.
         *
         * @param id the unique identifier for this response
         * @return this builder instance
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the content blocks for the response.
         *
         * @param content the list of content blocks containing the response content
         * @return this builder instance
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the usage information for the response.
         *
         * @param usage the token usage information
         * @return this builder instance
         */
        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * Sets the metadata for the response.
         *
         * @param metadata additional metadata from the model provider
         * @return this builder instance
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the finish reason for the response.
         *
         * @param finishReason the finish reason sent by the model provider
         * @return this builder instance
         */
        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /**
         * Builds a new ChatResponse instance with the set values.
         * <p>
         * If no id was set (or is empty/blank), a random UUID will be generated
         * automatically. This ensures compatibility with LLM providers (like Ollama)
         * that don't return an id field in their API responses.
         *
         * @return a new ChatResponse instance
         */
        public ChatResponse build() {
            // Auto-generate id if not set, empty, or blank (for providers like Ollama)
            String responseId = this.id;
            if (responseId == null || responseId.trim().isEmpty()) {
                responseId = UUID.randomUUID().toString();
            }
            return new ChatResponse(responseId, content, usage, metadata, finishReason);
        }
    }
}
