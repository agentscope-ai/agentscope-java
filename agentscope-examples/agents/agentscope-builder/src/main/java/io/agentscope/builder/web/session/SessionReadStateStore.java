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
package io.agentscope.builder.web.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.BuilderBootstrap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-user, per-session "last read at" tracker, used by the Threads inbox to derive an unread flag.
 *
 * <p>A session is considered <em>unread</em> when its {@code lastActivityMs} is greater than the
 * stored last-read timestamp. Marking-as-read updates the stored timestamp to {@link
 * System#currentTimeMillis()} (or to a caller-supplied value).
 *
 * <p>State is persisted as a flat JSON map at {@code .agentscope/session-read-state.json} so that
 * read state survives restarts. Writes go through an atomic temp-file rename.
 */
@Component
public class SessionReadStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionReadStateStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path stateFile;
    private final Map<String, Long> lastReadAt = new ConcurrentHashMap<>();

    public SessionReadStateStore(BuilderBootstrap builderBootstrap) {
        this.stateFile =
                builderBootstrap.cwd().resolve(".agentscope").resolve("session-read-state.json");
        load();
    }

    /** Marks the (user, session) pair as read at {@code System.currentTimeMillis()}. */
    public long markRead(String userId, String sessionKey) {
        return markRead(userId, sessionKey, System.currentTimeMillis());
    }

    /** Marks the (user, session) pair as read at a specific epoch-ms timestamp. */
    public long markRead(String userId, String sessionKey, long readAtMs) {
        String k = key(userId, sessionKey);
        lastReadAt.put(k, readAtMs);
        flush();
        return readAtMs;
    }

    /** Returns the last-read timestamp for the (user, session) pair, or {@code 0L} if never. */
    public long lastReadAt(String userId, String sessionKey) {
        Long v = lastReadAt.get(key(userId, sessionKey));
        return v != null ? v : 0L;
    }

    /**
     * Whether the session is unread for this user — i.e. its {@code lastActivityMs} is strictly
     * greater than the stored last-read timestamp. Sessions that have never been read are treated
     * as unread.
     */
    public boolean isUnread(String userId, String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(userId, sessionKey);
    }

    private static String key(String userId, String sessionKey) {
        return (userId != null ? userId : "__anon__") + sessionKey;
    }

    private synchronized void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            Map<String, Long> raw =
                    MAPPER.readValue(json, new TypeReference<Map<String, Long>>() {});
            if (raw != null) {
                lastReadAt.putAll(raw);
            }
        } catch (IOException e) {
            log.warn("Failed to load session read-state from {}: {}", stateFile, e.getMessage());
        }
    }

    private synchronized void flush() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            // Use a stable iteration order for diff-friendly writes.
            Map<String, Long> ordered = new LinkedHashMap<>(lastReadAt);
            Files.writeString(tmp, MAPPER.writeValueAsString(ordered), StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist session read-state to {}: {}", stateFile, e.getMessage());
        }
    }
}
