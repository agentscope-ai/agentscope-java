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
package io.agentscope.extensions.aistio.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A page of Level-3 full history, served by {@code GET /agentscope/sessions/{id}/messages}.
 *
 * <p>Unlike the Level-4 context, this includes messages already compacted away, so operators can
 * audit what the agent originally saw.
 */
public record MessagePage(
        String sessionId, List<MessageItem> messages, int offset, int limit, int total) {

    /** Slices {@code all} into a page and reports the untruncated total. */
    public static MessagePage of(String sessionId, List<MessageItem> all, int offset, int limit) {
        int from = Math.max(0, offset);
        int to = Math.min(all.size(), limit <= 0 ? all.size() : from + limit);
        List<MessageItem> slice =
                from >= all.size() ? List.of() : List.copyOf(all.subList(from, to));
        return new MessagePage(sessionId, slice, offset, limit, all.size());
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("total", total);
        List<Object> items = new ArrayList<>(messages.size());
        for (MessageItem m : messages) {
            items.add(m.toJsonMap());
        }
        out.put("messages", items);
        return out;
    }

    /** One full-content history entry. */
    public record MessageItem(
            int seq,
            String role,
            String content,
            String toolName,
            Map<String, Object> toolInput,
            String toolOutput,
            long occurredAt) {

        public static MessageItem of(int seq, String role, String content) {
            return new MessageItem(seq, role, content, "", null, "", 0L);
        }

        public Map<String, Object> toJsonMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", seq);
            out.put("role", role == null ? "" : role);
            out.put("content", content == null ? "" : content);
            if (toolName != null && !toolName.isEmpty()) {
                out.put("toolName", toolName);
            }
            if (toolInput != null && !toolInput.isEmpty()) {
                out.put("toolInput", toolInput);
            }
            if (toolOutput != null && !toolOutput.isEmpty()) {
                out.put("toolOutput", toolOutput);
            }
            if (occurredAt > 0) {
                out.put("occurredAt", Instant.ofEpochMilli(occurredAt).toString());
            }
            return out;
        }
    }
}
