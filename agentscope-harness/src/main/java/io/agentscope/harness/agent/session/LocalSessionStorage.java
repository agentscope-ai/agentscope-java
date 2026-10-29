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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.SessionLogException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Host-filesystem journal storage. POSIX hosts use OS locks, forced files, atomic renames and
 * directory fsync. Windows uses SQLite transactions because its Java filesystem provider cannot
 * open directories for fsync. Both backends acknowledge writes only after synchronous commit.
 */
public final class LocalSessionStorage implements AtomicSessionStorage {
    /** Suffix of the atomic-write temp file, and of the lock file guarding a target. */
    private static final String TEMP_SUFFIX = ".tmp";

    private static final String LOCK_SUFFIX = ".lock";
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final AtomicSessionStorage transactionalStorage;

    public LocalSessionStorage(Path root) {
        Path absolute = root.toAbsolutePath().normalize(), existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        try {
            this.root = existing.toRealPath().resolve(existing.relativize(absolute));
            this.transactionalStorage =
                    this.root.getFileSystem().getSeparator().equals("\\")
                            ? new SqliteSessionStorage(this.root)
                            : null;
        } catch (IOException error) {
            throw new SessionLogException("Cannot resolve journal root", error);
        }
    }

    private Path path(String key) {
        Path result = root.resolve(key).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("Journal path traversal");
        for (Path cursor = result;
                cursor != null && cursor.startsWith(root);
                cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor))
                throw new SessionLogException("Journal symlink paths are not supported");
        return result;
    }

    @Override
    public List<String> listPaths(String prefix) {
        Path directory = path(prefix);
        if (transactionalStorage != null) return transactionalStorage.listPaths(prefix);
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(file -> !Files.isSymbolicLink(file))
                    .filter(LocalSessionStorage::isCommittedObject)
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (UncheckedIOException raced) {
            // Files.walk reads each entry's attributes while it iterates, so an entry that a
            // concurrent writer renames away surfaces as an UncheckedIOException wrapping
            // NoSuchFileException rather than as a checked one. Discovery is a best-effort
            // snapshot; callers fall back to per-object reads when it comes back empty.
            if (raced.getCause() instanceof NoSuchFileException) return List.of();
            throw new SessionLogException("Journal discovery failed: " + directory, raced);
        } catch (IOException error) {
            throw new SessionLogException("Journal discovery failed: " + directory, error);
        }
    }

    /** Atomic-write temps and lock files live beside their targets but are not journal objects. */
    private static boolean isCommittedObject(Path file) {
        String name = file.getFileName().toString();
        return !name.endsWith(TEMP_SUFFIX) && !name.endsWith(LOCK_SUFFIX);
    }

    @Override
    public Value read(String key) {
        Path file = path(key);
        if (transactionalStorage != null) return transactionalStorage.read(key);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 8) throw new SessionLogException("Truncated journal object");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long version = buffer.getLong();
            if (version < 1) throw new SessionLogException("Invalid journal object version");
            byte[] payload = new byte[buffer.remaining()];
            buffer.get(payload);
            return new Value(version, payload);
        } catch (NoSuchFileException absent) {
            // Missing is null: the entry can be renamed away between a listing and this read.
            return null;
        } catch (IOException e) {
            throw new SessionLogException("Journal read failed: " + file, e);
        }
    }

    @Override
    public boolean compareAndSet(String key, long expected, byte[] bytes) {
        Path target = path(key);
        if (transactionalStorage != null)
            return transactionalStorage.compareAndSet(key, expected, bytes);
        Path lockPath = target.resolveSibling(target.getFileName() + LOCK_SUFFIX);
        ReentrantLock local = LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        local.lock();
        try {
            var missing = new ArrayList<Path>();
            for (Path parent = target.getParent();
                    parent != null && !Files.exists(parent);
                    parent = parent.getParent()) missing.add(parent);
            Files.createDirectories(target.getParent());
            for (Path directory : missing) {
                try (var created = FileChannel.open(directory, StandardOpenOption.READ)) {
                    created.force(true);
                }
                if (directory.getParent() != null)
                    try (var parent =
                            FileChannel.open(directory.getParent(), StandardOpenOption.READ)) {
                        parent.force(true);
                    }
            }
            try (var channel =
                            FileChannel.open(
                                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                var current = read(key);
                if ((current == null ? 0 : current.version()) != expected) return false;
                Path temp = Files.createTempFile(target.getParent(), "journal-", TEMP_SUFFIX);
                try {
                    try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        ByteBuffer buffer =
                                ByteBuffer.allocate(8 + bytes.length)
                                        .putLong(expected + 1)
                                        .put(bytes);
                        buffer.flip();
                        while (buffer.hasRemaining()) output.write(buffer);
                        output.force(true);
                    }
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    try (var directory =
                            FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                return true;
            }
        } catch (IOException e) {
            throw new SessionLogException("Journal CAS failed: " + target, e);
        } finally {
            local.unlock();
        }
    }
}
