/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.llm.interfacesweb.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Server-sent event payload for Anthropic Messages streaming compatibility. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicStreamEvent {

    private String type;
    private AnthropicMessagesResponse message;
    private Integer index;
    private Map<String, Object> contentBlock;
    private Map<String, Object> delta;
    private AnthropicUsage usage;

    @JsonProperty("stop_reason")
    private String stopReason;

    public AnthropicStreamEvent() {}

    public AnthropicStreamEvent(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public AnthropicMessagesResponse getMessage() {
        return message;
    }

    public void setMessage(AnthropicMessagesResponse message) {
        this.message = message;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public Map<String, Object> getContentBlock() {
        return contentBlock;
    }

    public void setContentBlock(Map<String, Object> contentBlock) {
        this.contentBlock = contentBlock;
    }

    public Map<String, Object> getDelta() {
        return delta;
    }

    public void setDelta(Map<String, Object> delta) {
        this.delta = delta;
    }

    public AnthropicUsage getUsage() {
        return usage;
    }

    public void setUsage(AnthropicUsage usage) {
        this.usage = usage;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }
}
