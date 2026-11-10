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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Immutable, session-scoped files stored in the configured shared BaseStore. */
@Service
public final class SessionFileService {
    /**
     * Application subtypes whose bytes are text and therefore safe to inline into the model input.
     *
     * <p>{@code text/*} is covered by the type, and the {@code +json} / {@code +xml} structured
     * suffixes are covered separately, so only the plain application subtypes are listed here.
     */
    private static final Set<String> TEXT_APPLICATION_SUBTYPES =
            Set.of(
                    "json",
                    "xml",
                    "yaml",
                    "x-yaml",
                    "yml",
                    "csv",
                    "toml",
                    "markdown",
                    "ndjson",
                    "x-ndjson",
                    "sql",
                    "javascript",
                    "x-sh");

    /**
     * Largest amount of a text file that is inlined into the model input.
     *
     * <p>A 16 MB file (the upload limit) is far more than any context window can take, so the
     * content is cut here and the cut is stated in the text rather than being silent.
     */
    private static final int TEXT_INLINE_MAX_BYTES = 262_144;

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
                // Text files are inlined as text: handing them over as an opaque data block means
                // the model only ever sees a "[Media - unsupported MIME type: ...]" placeholder,
                // which breaks "upload material, ask the agent about it" for every text-ish file.
                String text = inlineText(file, mime);
                if (text != null) {
                    content.add(Map.of("type", "text", "text", text));
                    continue;
                }
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

    /**
     * Inline a text-like file as text, or return {@code null} to fall back to an opaque data block.
     *
     * <p>The decision is made from the media type alone: {@code text/*}, the plain application text
     * subtypes and the {@code +json} / {@code +xml} structured suffixes are inlined; everything else
     * (PDF, Word, archives, images, audio, video) stays a data block for the model layer to route.
     *
     * <p>Content is cut at {@link #TEXT_INLINE_MAX_BYTES} and the cut is stated in the text, so a
     * truncated file cannot be mistaken for a complete one.
     *
     * @param file the stored record, including its base64 payload
     * @param mediaType the media type recorded at upload time
     * @return the text to inline, or {@code null} when the file is not text or is mislabelled binary
     */
    private static String inlineText(Map<String, Object> file, String mediaType) {
        if (!isTextLike(mediaType)) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode((String) file.get("data"));
        int length = Math.min(bytes.length, TEXT_INLINE_MAX_BYTES);
        String text = new String(bytes, 0, length, charsetOf(mediaType));
        // A text media type does not prove the bytes are text: fall back rather than pushing
        // control characters into the conversation.
        if (text.indexOf('\u0000') >= 0) {
            return null;
        }
        return bytes.length <= TEXT_INLINE_MAX_BYTES
                ? text
                : text
                        + "\n\n[truncated: "
                        + TEXT_INLINE_MAX_BYTES
                        + " of "
                        + bytes.length
                        + " bytes]";
    }

    /**
     * Decide whether a media type describes text that can be inlined.
     *
     * @param mediaType the media type recorded at upload time
     * @return true when the content can be inlined as text
     */
    private static boolean isTextLike(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(mediaType);
        } catch (RuntimeException error) {
            return false;
        }
        if ("text".equalsIgnoreCase(parsed.getType())) {
            return true;
        }
        if (!"application".equalsIgnoreCase(parsed.getType())) {
            return false;
        }
        String subtype = parsed.getSubtype().toLowerCase(Locale.ROOT);
        return TEXT_APPLICATION_SUBTYPES.contains(subtype)
                || subtype.endsWith("+json")
                || subtype.endsWith("+xml");
    }

    /**
     * Resolve the charset a text media type declares, defaulting to UTF-8.
     *
     * @param mediaType the media type recorded at upload time
     * @return the declared charset, or UTF-8 when none is declared or it is unknown
     */
    private static Charset charsetOf(String mediaType) {
        try {
            Charset charset = MediaType.parseMediaType(mediaType).getCharset();
            return charset == null ? StandardCharsets.UTF_8 : charset;
        } catch (RuntimeException error) {
            return StandardCharsets.UTF_8;
        }
    }
}
