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
package io.agentscope.builder.runtime.session;

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
 * <h2>Purpose</h2>
 * 只存储<b>会话元数据</b>（注册表），不存储对话内容本身。对话内容（transcript）由
 * {@code MemoryFlushHook → SessionTree} 写入独立的 {@code .jsonl / .log.jsonl} 文件。
 * sessions.json 中的 {@code sessionFilePath} 字段是连接元数据与内容的<b>指针</b>。
 *
 * <h2>为什么需要独立的 sessions.json？</h2>
 * RemoteFilesystem 的文件系统可以列出 agents/{agentId}/sessions/ 下有哪些文件，但无法原生表达：
 * <ul>
 *   <li>label（用户自定义名称）
 *   <li>createdAtMs（创建时间，与文件修改时间不同）
 *   <li>kind（MAIN vs SUBAGENT）
 *   <li>gateKey（消息路由 key）
 *   <li>spawnedBy / spawnDepth（父子会话调用链）
 * </ul>
 * 这些元数据是 builder 层的概念，必须由独立的注册表维护。
 *
 * <h2>数据模型 vs 存储实现</h2>
 * StoredEntry 的数据结构对 RemoteFilesystem 完全适用 —— sessionFilePath 是虚拟引用字符串，
 * 不依赖本地路径。当前实现是本地 JSON 文件，可替换为 BaseStore（Redis/JDBC）后端实现。
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
     * JSON-serializable subset of {@link SessionEntry} for disk persistence.
     *
     * <h2>字段分两类</h2>
     * <b>文件系统可推导（冗余但方便缓存）：</b>
     * agentId、sessionId、userId、lastActivityMs（≈文件修改时间）
     *
     * <b>文件系统无法表达（必须由注册表维护）：</b>
     * label（用户自定义名称，纯展示）、createdAtMs（创建≠修改）、
     * kind（MAIN/SUBAGENT，文件层级区分不了）、gateKey（消息路由 key）、
     * spawnedBy/spawnDepth（父子调用链）、sessionKey（跨 agent 唯一标识）
     *
     * <b>sessionFilePath</b>：指向 transcript 文件的虚拟引用（通过
     * WorkspaceManager 解析），不依赖本地路径。当使用 RemoteFilesystem
     * 时它代表 BaseStore 的 namespace key，而非实际磁盘路径。
     *
     * <p>Uses {@code @JsonIgnoreProperties(ignoreUnknown = true)} for forward compatibility
     * when new fields are added.
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

    /**
     * 仅更新 lastActivityMs，避免完整 replace 带来的序列化开销。
     * 每次对话结束后调用，是整个注册表最频繁的写操作。
     */
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

    /**
     * 原子写入：先写临时文件，再 rename 覆盖原文件。即使写入中途崩溃，
     * 原文件不受影响（要么完全成功，要么完全不生效）。
     *
     * <p>注意：此方法直接操作本地文件系统（java.nio.file），不走
     * AbstractFilesystem 抽象。如果使用 RemoteFilesystem，需要将整个
     * SessionStore 替换为 BaseStore 后端实现。
     */
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
