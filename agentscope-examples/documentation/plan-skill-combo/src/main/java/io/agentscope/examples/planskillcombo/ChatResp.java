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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

/**
 * Top-level chat response POJO. Wraps a {@link StreamMessage} with the originating {@code Msg}'s
 * metadata.
 *
 * <pre>{@code
 * {"id":"msg_456","role":"ASSISTANT","name":"运维助手","data":{"type":"text","content":"根据诊断结果..."}}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final MsgRole role;
    private final String name;
    private final StreamMessage data;

    public ChatResp(Msg msg, StreamMessage data) {
        this.id = msg.getId();
        this.role = msg.getRole();
        this.name = msg.getName();
        this.data = data;
    }

    /** Convenience constructor for cases without a {@link Msg} (e.g. error responses). */
    public ChatResp(String id, StreamMessage data) {
        this.id = id;
        this.role = null;
        this.name = null;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public MsgRole getRole() {
        return role;
    }

    public String getName() {
        return name;
    }

    public StreamMessage getData() {
        return data;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"id\":\"" + id + "\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public String toString() {
        return toJson();
    }
}
