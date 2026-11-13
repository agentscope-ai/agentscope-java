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
package io.agentscope.core.state;

import io.agentscope.core.util.JsonUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JSON file-based {@link AgentStateStore} implementation.
 *
 * <p>On-disk layout:
 *
 * <pre>
 * &lt;root&gt;/
 *   __anon__/                       ← anonymous (userId == null) sessions
 *     &lt;safe(sessionId)&gt;/
 *       agent_state.json
 *       memory_messages.jsonl
 *       memory_messages.hash
 *   &lt;safe(userId)&gt;/                ← per-user sessions
 *     &lt;safe(sessionId)&gt;/
 *       agent_state.json
 *       ...
 * </pre>
 *
 * <p>{@code safe(...)} keeps the literal string when it matches
 * {@code ^[a-zA-Z0-9_\-.]+$}; otherwise it is Base64-URL encoded (no padding) so the
 * filesystem accepts it.
 *
 * <p>Features: atomic file operations, UTF-8 encoding, graceful handling of missing
 * sessions, hash-based append-or-rewrite for list state.
 *
 * <p>Concurrency: operations on a session share a JVM-wide lock for that session directory.
 * The root is resolved to its real path, so stores opened through root aliases share locks. The
 * locks do not span processes: a session must be owned by one process at a time. File locks are
 * advisory and unreliable on network file systems such as NFS, so deployments that share sessions
 * across processes should use a database-backed {@link AgentStateStore} instead.
 */
public class JsonFileAgentStateStore implements AgentStateStore {

    /** Sentinel directory for callers that pass {@code userId == null}. */
    private static final String ANON_USER = "__anon__";

    /** Pattern for file-system safe characters: alphanumeric, underscore, hyphen, dot. */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    // Keep locks after session deletion so queued operations and later saves use the same lock.
    private static final ConcurrentHashMap<Path, ReentrantLock> SESSION_LOCKS =
            new ConcurrentHashMap<>();

    private final Path rootDirectory;

    /**
     * Create a {@code JsonFileAgentStateStore} with the default root:
     * {@code ~/.agentscope/state}.
     */
    public JsonFileAgentStateStore() {
        this(Paths.get(System.getProperty("user.home"), ".agentscope", "state"));
    }

    /**
     * Create a {@code JsonFileAgentStateStore} rooted at the given directory.
     *
     * @param rootDirectory root directory under which user / session sub-directories live
     */
    public JsonFileAgentStateStore(Path rootDirectory) {
        try {
            Files.createDirectories(rootDirectory);
            this.rootDirectory = rootDirectory.toRealPath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize root directory: " + rootDirectory, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        Path sessionDir = getSessionDir(userId, sessionId);
        Path file = getStatePath(sessionDir, key);
        String json = JsonUtils.getJsonCodec().toPrettyJson(value);
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            ensureDirectoryExists(file.getParent());
            atomicWriteString(file, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        Path sessionDir = getSessionDir(userId, sessionId);
        Path file = getListPath(sessionDir, key);
        Path hashFile = getHashPath(sessionDir, key);
        String currentHash = ListHashUtil.computeHash(values);

        // The whole read-hash-count + append/rewrite sequence must run under the session lock:
        // two concurrent list saves would otherwise append the same tail twice, or interleave
        // an append with a rewrite.
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            ensureDirectoryExists(file.getParent());
            String storedHash = readHashFile(hashFile);
            long existingCount = countLines(file);
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
            if (needsFullRewrite) {
                rewriteEntireList(file, values);
            } else if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                appendToList(file, newItems);
            }
            // else: no change, skip writing
            writeHashFile(hashFile, currentHash);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    private void rewriteEntireList(Path file, List<? extends State> values) throws IOException {
        Path tmp = uniqueTmpSibling(file);
        try {
            try (BufferedWriter writer =
                    newUtf8ReplacingWriter(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                for (State item : values) {
                    writer.write(JsonUtils.getJsonCodec().toJson(item));
                    writer.newLine();
                }
            }
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // toJson can throw a RuntimeException mid-write; the finally keeps unique tmp
            // names (never reclaimed by a later write) from piling up. No-op after a
            // successful move.
            deleteQuietly(tmp);
        }
    }

    private void appendToList(Path file, List<? extends State> items) throws IOException {
        // Serialize before touching the file and undo a write that fails part-way, so a failed
        // save never leaves part of its tail behind.
        StringBuilder lines = new StringBuilder();
        for (State item : items) {
            lines.append(JsonUtils.getJsonCodec().toJson(item)).append(System.lineSeparator());
        }
        ByteBuffer bytes = utf8ReplacingEncoder().encode(CharBuffer.wrap(lines));
        try (FileChannel channel =
                FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            long originalSize = channel.size();
            try {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            } catch (IOException e) {
                try {
                    channel.truncate(originalSize);
                } catch (IOException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        Path sessionDir = getSessionDir(userId, sessionId);
        Path file = getStatePath(sessionDir, key);
        // Readers take the session lock too: on Windows an open read handle blocks another
        // thread's replace of the same file (AccessDeniedException), so reads and writes must
        // be mutually exclusive.
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
            } catch (IOException e) {
                throw new RuntimeException("Failed to load state: " + key, e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        Path sessionDir = getSessionDir(userId, sessionId);
        Path file = getListPath(sessionDir, key);
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            try {
                List<T> result = new ArrayList<>();
                try (BufferedReader reader =
                        Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            result.add(JsonUtils.getJsonCodec().fromJson(line, itemType));
                        }
                    }
                }
                return result;
            } catch (IOException e) {
                throw new RuntimeException("Failed to load list: " + key, e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        Path dir = getSessionDir(userId, sessionId);
        ReentrantLock lock = lockFor(dir);
        lock.lock();
        try {
            return Files.exists(dir);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        deleteSession(getSessionDir(userId, sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        Path sessionDir = getSessionDir(userId, sessionId);
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            Files.deleteIfExists(getStatePath(sessionDir, key));
            Files.deleteIfExists(getListPath(sessionDir, key));
            Files.deleteIfExists(getHashPath(sessionDir, key));
            if (Files.exists(sessionDir)) {
                boolean empty;
                try (Stream<Path> files = Files.list(sessionDir)) {
                    empty = files.findAny().isEmpty();
                }
                if (empty) {
                    Files.delete(sessionDir);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete state: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        // Only reads directory entries, never a session's files, so it takes no session lock;
        // a session created or deleted concurrently may or may not be listed.
        Path userDir = rootDirectory.resolve(safeSegment(normalizeUser(userId)));
        if (!Files.isDirectory(userDir)) {
            return Set.of();
        }
        try (Stream<Path> dirs = Files.list(userDir)) {
            Set<String> result = new HashSet<>();
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                result.add(decodeSegment(d.getFileName().toString()));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /** Returns the real root directory (for diagnostics). */
    public Path getRootDirectory() {
        return rootDirectory;
    }

    /**
     * Clear every session under every user. Returns the number of session directories removed.
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                if (!Files.exists(rootDirectory)) {
                                    return 0;
                                }
                                int deletedCount = 0;
                                try (Stream<Path> userDirs = Files.list(rootDirectory)) {
                                    for (Path userDir :
                                            (Iterable<Path>)
                                                    userDirs.filter(Files::isDirectory)::iterator) {
                                        try (Stream<Path> sessionDirs = Files.list(userDir)) {
                                            for (Path sessionDir :
                                                    (Iterable<Path>)
                                                            sessionDirs.filter(Files::isDirectory)
                                                                    ::iterator) {
                                                if (deleteSession(sessionDir)) {
                                                    deletedCount++;
                                                }
                                            }
                                        }
                                    }
                                }
                                return deletedCount;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Returns the directory under which all state files for {@code (userId, sessionId)} live.
     * Subclasses can override this if they need a different layout.
     */
    protected Path getSessionDir(String userId, String sessionId) {
        return rootDirectory
                .resolve(safeSegment(normalizeUser(userId)))
                .resolve(safeSegment(requireSessionId(sessionId)));
    }

    private static Path getStatePath(Path sessionDir, String key) {
        return sessionDir.resolve(key + ".json");
    }

    private static Path getListPath(Path sessionDir, String key) {
        return sessionDir.resolve(key + ".jsonl");
    }

    private static Path getHashPath(Path sessionDir, String key) {
        return sessionDir.resolve(key + ".hash");
    }

    private String readHashFile(Path hashFile) {
        if (!Files.exists(hashFile)) {
            return null;
        }
        try {
            return Files.readString(hashFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void writeHashFile(Path hashFile, String hash) throws IOException {
        atomicWriteString(hashFile, hash);
    }

    private static void atomicWriteString(Path file, String content) throws IOException {
        Path tmp = uniqueTmpSibling(file);
        try {
            try (BufferedWriter writer =
                    newUtf8ReplacingWriter(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                writer.write(content);
            }
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static ReentrantLock lockFor(Path sessionDir) {
        return SESSION_LOCKS.computeIfAbsent(
                sessionDir.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
    }

    // Unique tmp names are never overwritten by a later write, so a failed one must be
    // removed explicitly or it stays on disk forever.
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort cleanup while propagating the original failure
        }
    }

    // A fixed ".tmp" sibling races when two threads persist the same state concurrently: one
    // thread's move consumes the file the other is still writing, surfacing as
    // NoSuchFileException and potentially persisting interleaved (corrupt) content.
    private static Path uniqueTmpSibling(Path file) {
        return file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
    }

    private static BufferedWriter newUtf8ReplacingWriter(Path file, StandardOpenOption... options)
            throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(file, options), utf8ReplacingEncoder()));
    }

    private static CharsetEncoder utf8ReplacingEncoder() {
        return StandardCharsets.UTF_8
                .newEncoder()
                .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD})
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private long countLines(Path file) {
        if (!Files.exists(file)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }

    private boolean deleteSession(Path sessionDir) {
        ReentrantLock lock = lockFor(sessionDir);
        lock.lock();
        try {
            if (!Files.exists(sessionDir)) {
                return false;
            }
            deleteDirectory(sessionDir);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void deleteDirectory(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to delete: " + path, e);
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete directory: " + dir, e);
        }
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private static String safeSegment(String value) {
        if (SAFE_FILENAME_PATTERN.matcher(value).matches()) {
            return value;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSegment(String segment) {
        if (SAFE_FILENAME_PATTERN.matcher(segment).matches()) {
            return segment;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
