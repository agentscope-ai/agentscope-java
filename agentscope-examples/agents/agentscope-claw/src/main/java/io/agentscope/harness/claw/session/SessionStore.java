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
package io.agentscope.harness.claw.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable session registry backed by a JSON file ({@code sessions.json}). Mirrors OpenClaw's
 * {@code sessions.json} store that tracks session metadata across restarts.
 *
 * <p>Thread-safe: uses a read-write lock so concurrent reads are non-blocking and writes are
 * serialized. File writes are atomic (write to temp, then rename).
 *
 * <p>The store file contains a JSON object keyed by {@code sessionKey}, where each value is a
 * {@link StoredEntry} capturing the subset of {@link SessionEntry} fields that need to survive
 * restarts.
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    /**
     * JSON-serializable subset of {@link SessionEntry} for disk persistence. Uses
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} for forward compatibility when new
     * fields are added.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId,
            String gateKey,
            String userId) {

        public static StoredEntry from(SessionEntry e) {
            return new StoredEntry(
                    e.sessionKey(),
                    e.agentId(),
                    e.sessionId(),
                    e.label(),
                    e.kind().getValue(),
                    e.spawnedBy(),
                    e.spawnDepth(),
                    e.createdAtMs(),
                    e.lastActivityMs(),
                    e.sessionFilePath(),
                    e.spawnRunId(),
                    e.gateKey(),
                    e.userId());
        }

        public SessionEntry toSessionEntry() {
            SessionKind sk = "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            return new SessionEntry(
                    sessionKey,
                    agentId,
                    sessionId,
                    label,
                    sk,
                    spawnedBy,
                    spawnDepth,
                    createdAtMs,
                    lastActivityMs,
                    sessionFilePath,
                    spawnRunId,
                    gateKey,
                    userId);
        }
    }

    public SessionStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Loads all entries from the store file into memory. Call once on startup. If the file does not
     * exist or is empty, the store starts empty.
     */
    public void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);
            }
            log.info("Loaded {} session entries from {}", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn("Failed to load session store from {}: {}", storeFile, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Persists a single session entry (upsert). */
    public void save(SessionEntry entry) {
        lock.writeLock().lock();
        try {
            entries.put(entry.sessionKey(), StoredEntry.from(entry));
            flushToDisk();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Updates only the {@code lastActivityMs} for the given key without a full entry replace. */
    public void touch(String sessionKey, long lastActivityMs) {
        lock.writeLock().lock();
        try {
            StoredEntry existing = entries.get(sessionKey);
            if (existing == null) {
                return;
            }
            entries.put(
                    sessionKey,
                    new StoredEntry(
                            existing.sessionKey(),
                            existing.agentId(),
                            existing.sessionId(),
                            existing.label(),
                            existing.kind(),
                            existing.spawnedBy(),
                            existing.spawnDepth(),
                            existing.createdAtMs(),
                            lastActivityMs,
                            existing.sessionFilePath(),
                            existing.spawnRunId(),
                            existing.gateKey(),
                            existing.userId()));
            flushToDisk();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes a session entry by key. */
    public void remove(String sessionKey) {
        lock.writeLock().lock();
        try {
            if (entries.remove(sessionKey) != null) {
                flushToDisk();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns a snapshot of all stored entries. */
    public Collection<StoredEntry> listAll() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns a single entry by key, if present. */
    public Optional<StoredEntry> get(String sessionKey) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(sessionKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The path to the backing store file. */
    public Path getStoreFile() {
        return storeFile;
    }

    private void flushToDisk() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(
                    tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    tmp,
                    storeFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to flush session store to {}: {}", storeFile, e.getMessage());
        }
    }
}
