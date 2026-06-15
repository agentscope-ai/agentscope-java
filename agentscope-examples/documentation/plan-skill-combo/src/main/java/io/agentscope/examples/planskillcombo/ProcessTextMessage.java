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
 * Intermediate reasoning text emitted during the ReAct loop — the model's explanation of what it
 * plans to do before calling tools. Distinguished from {@link TextMessage} which represents the
 * final response.
 *
 * <pre>{@code
 * {"type":"processText","content":"我需要调用 query_metrics 工具来查看 CPU 使用率..."}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ProcessTextMessage extends StreamMessage {

    private final String content;

    @JsonCreator
    public ProcessTextMessage(@JsonProperty("content") String content) {
        this.content = content != null ? content : "";
    }

    public String getContent() {
        return content;
    }
}
