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

import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Immutable, session-scoped files stored in the configured shared BaseStore. */
@Service
public final class SessionFileService {
    private final BaseStore store;
    private final int maxBytes;

    public SessionFileService(
            BaseStore store, @Value("${builder.agent-api.files.max-bytes:16777216}") int maxBytes) {
        this.store = store;
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }

    private List<String> namespace(String session) {
        return List.of("runtime", "agent-api", "files", session);
    }

    public Map<String, Object> upload(
            String session, String key, String name, String mediaType, byte[] bytes) {
        if (key == null
                || key.isBlank()
                || key.length() > 256
                || name == null
                || name.isBlank()
                || name.length() > 512
                || name.contains("\r")
                || name.contains("\n"))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Valid file name and Idempotency-Key required");
        if (bytes.length > maxBytes)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            mediaType = MediaType.parseMediaType(mediaType).toString();
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media type");
        }
        String id =
                "file_"
                        + JournalSessionLog.hash(
                                (session + "\n" + key).getBytes(StandardCharsets.UTF_8));
        var record = new LinkedHashMap<String, Object>();
        record.put("file_id", id);
        record.put("name", name);
        record.put("media_type", mediaType);
        record.put("size", bytes.length);
        record.put("sha256", JournalSessionLog.hash(bytes));
        record.put("data", Base64.getEncoder().encodeToString(bytes));
        record.put("created_at", System.currentTimeMillis());
        var existing = store.get(namespace(session), id);
        if (existing == null && store.putIfVersion(namespace(session), id, record, 0))
            return metadata(session, record);
        existing = store.get(namespace(session), id);
        if (existing == null)
            throw new IllegalStateException("File store does not support atomic writes");
        for (String field : List.of("name", "media_type", "sha256"))
            if (!record.get(field).equals(existing.value().get(field)))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "File key reused with different content or metadata");
        return metadata(session, existing.value());
    }

    public Map<String, Object> get(String session, String id) {
        return metadata(session, raw(session, id));
    }

    public byte[] content(String session, String id) {
        return Base64.getDecoder().decode((String) raw(session, id).get("data"));
    }

    private Map<String, Object> raw(String session, String id) {
        var item = store.get(namespace(session), id);
        if (item == null)
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "File not found in this session");
        return item.value();
    }

    private Map<String, Object> metadata(String session, Map<String, Object> raw) {
        var result = new LinkedHashMap<>(raw);
        result.remove("data");
        result.put(
                "download_url",
                "/api/v1/agent-sessions/" + session + "/files/" + raw.get("file_id") + "/content");
        return result;
    }

    public Map<String, Object> list(String session, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
        var page = store.search(namespace(session), limit + 1, offset);
        return Map.of(
                "data",
                page.stream().limit(limit).map(item -> metadata(session, item.value())).toList(),
                "next_offset",
                offset + Math.min(limit, page.size()),
                "has_more",
                page.size() > limit);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveInput(String session, Map<String, Object> request) {
        if (!request.containsKey("input")) return request;
        var copy =
                new LinkedHashMap<String, Object>(
                        JsonUtils.getJsonCodec().convertValue(request, Map.class));
        var messages = new ArrayList<Map<String, Object>>();
        for (var rawMessage : (List<?>) request.get("input")) {
            var message = new LinkedHashMap<>((Map<String, Object>) rawMessage);
            var content = new ArrayList<Map<String, Object>>();
            for (var rawBlock : (List<?>) message.get("content")) {
                var block = (Map<String, Object>) rawBlock;
                if (!"file".equals(block.get("type"))) {
                    content.add(block);
                    continue;
                }
                var file = raw(session, String.valueOf(block.get("file_id")));
                String mime = (String) file.get("media_type");
                String type =
                        mime.startsWith("image/")
                                ? "image"
                                : mime.startsWith("audio/")
                                        ? "audio"
                                        : mime.startsWith("video/") ? "video" : "data";
                var resolved = new LinkedHashMap<String, Object>();
                resolved.put("type", type);
                resolved.put(
                        "source",
                        Map.of("type", "base64", "media_type", mime, "data", file.get("data")));
                if (type.equals("data")) {
                    resolved.put("id", file.get("file_id"));
                    resolved.put("name", file.get("name"));
                }
                content.add(resolved);
            }
            message.put("content", content);
            messages.add(message);
        }
        copy.put("input", messages);
        return copy;
    }
}
