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
package io.agentscope.core.formatter.anthropic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Server-Sent Event from Anthropic's streaming API.
 * Event types include: message_start, content_block_start, content_block_delta,
 * content_block_stop, message_delta, message_stop, ping, error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicStreamEvent {

    @JsonProperty("type")
    private String type;

    // For message_start
    @JsonProperty("message")
    private AnthropicResponse message;

    // For content_block_start
    @JsonProperty("index")
    private Integer index;

    @JsonProperty("content_block")
    private AnthropicContent contentBlock;

    // For content_block_delta
    @JsonProperty("delta")
    private Delta delta;

    // For message_delta
    @JsonProperty("usage")
    private AnthropicUsage usage;

    public AnthropicStreamEvent() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public AnthropicResponse getMessage() {
        return message;
    }

    public void setMessage(AnthropicResponse message) {
        this.message = message;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public AnthropicContent getContentBlock() {
        return contentBlock;
    }

    public void setContentBlock(AnthropicContent contentBlock) {
        this.contentBlock = contentBlock;
    }

    public Delta getDelta() {
        return delta;
    }

    public void setDelta(Delta delta) {
        this.delta = delta;
    }

    public AnthropicUsage getUsage() {
        return usage;
    }

    public void setUsage(AnthropicUsage usage) {
        this.usage = usage;
    }

    /**
     * Represents a delta update in streaming events.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("stop_reason")
        private String stopReason;

        @JsonProperty("stop_sequence")
        private String stopSequence;

        @JsonProperty("partial_json")
        private String partialJson;

        public Delta() {}

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getStopReason() {
            return stopReason;
        }

        public void setStopReason(String stopReason) {
            this.stopReason = stopReason;
        }

        public String getStopSequence() {
            return stopSequence;
        }

        public void setStopSequence(String stopSequence) {
            this.stopSequence = stopSequence;
        }

        public String getPartialJson() {
            return partialJson;
        }

        public void setPartialJson(String partialJson) {
            this.partialJson = partialJson;
        }
    }
}
