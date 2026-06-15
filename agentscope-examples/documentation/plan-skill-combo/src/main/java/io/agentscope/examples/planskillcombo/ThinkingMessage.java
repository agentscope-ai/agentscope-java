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
package io.agentscope.examples.planskillcombo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking/reasoning content extracted from {@code ThinkingBlock}. The {@code id} comes from the
 * containing {@code Msg#getId()}, linking thinking to tool calls in the same reasoning round.
 *
 * <pre>{@code
 * {"type":"thinking","id":"msg_1","thinking":"我需要先诊断 CPU 告警的原因..."}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ThinkingMessage extends StreamMessage {

    private final String thinking;

    @JsonCreator
    public ThinkingMessage(@JsonProperty("thinking") String thinking) {
        this.thinking = thinking != null ? thinking : "";
    }

    public String getThinking() {
        return thinking;
    }
}
