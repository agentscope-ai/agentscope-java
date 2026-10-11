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
package io.agentscope.builder.web.managed;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Public input contract. Control messages and tool-result injection use their dedicated APIs. */
public record AgentSessionInput(String message, List<Message> input) {
    public record Message(String role, List<Map<String, Object>> content) {}

    public Map<String, Object> normalized() {
        if ((message != null) == (input != null))
            throw invalid("Supply exactly one of message or input");
        if (message != null) {
            if (message.isBlank()) throw invalid("message must not be blank");
            return Map.of("message", message);
        }
        if (input.isEmpty() || input.size() > 100) throw invalid("input requires 1..100 messages");
        var result = new ArrayList<Map<String, Object>>();
        for (var message : input) {
            if (message == null
                    || !"user".equals(message.role())
                    || message.content() == null
                    || message.content().isEmpty())
                throw invalid("Input messages require role=user and nonempty content");
            var content = new ArrayList<Map<String, Object>>();
            for (var block : message.content()) {
                if (block == null
                        || !Set.of("text", "image", "audio", "video", "data", "file")
                                .contains(String.valueOf(block.get("type"))))
                    throw invalid("Unsupported input content type");
                var copy = new LinkedHashMap<>(block);
                if ("file".equals(copy.get("type"))) {
                    if (!(copy.get("file_id") instanceof String id) || id.isBlank())
                        throw invalid("file requires file_id");
                } else {
                    try {
                        JsonUtils.getJsonCodec().convertValue(copy, ContentBlock.class);
                    } catch (RuntimeException error) {
                        throw invalid("Invalid content block: " + copy.get("type"));
                    }
                }
                content.add(copy);
            }
            result.add(Map.of("role", "user", "content", content));
        }
        return Map.of("input", result);
    }

    @SuppressWarnings("unchecked")
    public static List<Msg> messages(Map<String, Object> input, String commandId) {
        if (input.get("message") instanceof String text)
            return List.of(
                    UserMessage.builder().id("command_" + commandId).textContent(text).build());
        var messages = new ArrayList<Msg>();
        for (var raw : (List<?>) input.get("input")) {
            var message = (Map<String, Object>) raw;
            var blocks = new ArrayList<ContentBlock>();
            for (var block : (List<?>) message.get("content"))
                blocks.add(JsonUtils.getJsonCodec().convertValue(block, ContentBlock.class));
            messages.add(
                    UserMessage.builder()
                            .id("command_" + commandId + "_" + messages.size())
                            .content(blocks)
                            .build());
        }
        return List.copyOf(messages);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
