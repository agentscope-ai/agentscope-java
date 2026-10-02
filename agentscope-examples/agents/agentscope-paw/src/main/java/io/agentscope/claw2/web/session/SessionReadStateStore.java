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
package io.agentscope.claw2.web.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
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
 * Per-session "last read at" tracker, used by the Threads inbox to derive an unread flag.
 *
 * <p>A session is considered <em>unread</em> when its {@code lastActivityMs} is greater than the
 * stored last-read timestamp. Marking-as-read updates the stored timestamp to
 * {@link System#currentTimeMillis()} (or to a caller-supplied value).
 *
 * <p>Persisted as a flat JSON map at {@code ${clawHome}/session-read-state.json} so read state
 * survives restarts. Writes go through an atomic temp-file rename.
 */
@Component
public class SessionReadStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionReadStateStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path stateFile;
    private final Map<String, Long> lastReadAt = new ConcurrentHashMap<>();

    public SessionReadStateStore(ClawBootstrap bootstrap) {
        this.stateFile = bootstrap.clawHome().resolve("session-read-state.json");
        load();
    }

    /** Marks {@code sessionKey} as read at {@code System.currentTimeMillis()}. */
    public long markRead(String sessionKey) {
        return markRead(sessionKey, System.currentTimeMillis());
    }

    /** Marks {@code sessionKey} as read at a specific epoch-ms timestamp. */
    public long markRead(String sessionKey, long readAtMs) {
        lastReadAt.put(sessionKey, readAtMs);
        flush();
        return readAtMs;
    }

    /** Returns the last-read timestamp for {@code sessionKey}, or {@code 0L} if never read. */
    public long lastReadAt(String sessionKey) {
        Long v = lastReadAt.get(sessionKey);
        return v != null ? v : 0L;
    }

    /**
     * Whether the session is unread — i.e. its {@code lastActivityMs} is strictly greater than
     * the stored last-read timestamp. Sessions that have never been read are treated as unread.
     */
    public boolean isUnread(String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(sessionKey);
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
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Map<String, Long> ordered = new LinkedHashMap<>(lastReadAt);
            Files.writeString(tmp, MAPPER.writeValueAsString(ordered), StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist session read-state to {}: {}", stateFile, e.getMessage());
        }
    }
}
