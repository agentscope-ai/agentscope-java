/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Persistent directory backend. Publication uses an atomic no-replace hard link after fsync. */
public final class FileRuntimeObjectStore implements RuntimeObjectStore {
    private final Path root;
    private final String backendId;

    public FileRuntimeObjectStore(Path root) {
        this("filesystem", root);
    }

    public FileRuntimeObjectStore(String backendId, Path root) {
        if (backendId == null || !backendId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid backend identity");
        this.backendId = backendId;
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new ManagedTransportException("Cannot open object directory", e);
        }
    }

    @Override
    public String backendId() {
        return backendId;
    }

    private Path location(String scope, String kind, String key) {
        Path path = root.resolve(RuntimeObjectStore.objectKey(scope, kind, key));
        Path current = path.getParent();
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current))
                throw new ManagedStateException("runtime_object_invalid", "Symlink in object path");
            current = current.getParent();
        }
        if (Files.isSymbolicLink(path))
            throw new ManagedStateException("runtime_object_invalid", "Symlink object");
        return path;
    }

    @Override
    public Reference put(String scope, String kind, String key, byte[] content) {
        if (content.length < 1 || content.length > MAX_BYTES)
            throw new IllegalArgumentException("Object size exceeds limit");
        Path target = location(scope, kind, key);
        Path tmp = null;
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(content, read(scope, kind, key)))
                throw new ManagedStateException(
                        "runtime_object_conflict", "Immutable object cannot change");
            return new Reference(
                    backendId,
                    scope,
                    kind,
                    key,
                    RuntimeObjectStore.sha256(content),
                    content.length);
        }
        try {
            Files.createDirectories(target.getParent());
            // Persist new directory entries from the leaf to the stable configured root.
            for (Path dir = target.getParent();
                    dir != null && dir.startsWith(root);
                    dir = dir.getParent()) force(dir);
            tmp = Files.createTempFile(target.getParent(), ".stage-", ".tmp");
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.createLink(target, tmp);
            } catch (FileAlreadyExistsException existing) {
                if (!Arrays.equals(content, read(scope, kind, key)))
                    throw new ManagedStateException(
                            "runtime_object_conflict", "Immutable object cannot change");
            }
            force(target.getParent());
            return new Reference(
                    backendId,
                    scope,
                    kind,
                    key,
                    RuntimeObjectStore.sha256(content),
                    content.length);
        } catch (IOException e) {
            throw new ManagedTransportException("Cannot persist immutable object", e);
        } finally {
            if (tmp != null)
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    /* Unreferenced staging file. */
                }
        }
    }

    private static void force(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @Override
    public byte[] read(String scope, String kind, String key) {
        Path path = location(scope, kind, key);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(MAX_BYTES + 1);
            if (content.length < 1 || content.length > MAX_BYTES)
                throw new ManagedStateException("runtime_object_invalid", "Object size differs");
            return content;
        } catch (IOException e) {
            throw new ManagedTransportException("Object unavailable", e);
        }
    }

    @Override
    public boolean exists(String scope, String kind, String key) {
        return Files.isRegularFile(location(scope, kind, key), LinkOption.NOFOLLOW_LINKS);
    }
}
