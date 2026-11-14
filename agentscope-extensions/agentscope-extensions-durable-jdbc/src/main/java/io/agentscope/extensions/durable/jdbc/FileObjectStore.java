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
package io.agentscope.extensions.durable.jdbc;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.store.ObjectStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Immutable local content with durable file data and atomic publication. */
public final class FileObjectStore implements ObjectStore {
    private final Path root;

    public FileObjectStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public String id() {
        return "files";
    }

    public Reference put(String scope, byte[] bytes) {
        var ref = ObjectStore.reference(id(), scope, bytes);
        var destination = root.resolve(ref.key());
        Path temporary = destination.resolveSibling("stage-" + UUID.randomUUID());
        try {
            Files.createDirectories(destination.getParent());
            try (var channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException existing) {
                ObjectStore.verify(id(), ref, read(ref));
            }
            try (var directory =
                    FileChannel.open(destination.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            return ref;
        } catch (IOException failure) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Immutable object publication failed",
                    failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                /* Unreferenced staging can be removed by maintenance. */
            }
        }
    }

    public byte[] read(Reference ref) {
        if (!id().equals(ref.store()))
            throw new DurableException(DurableException.Code.INVALID, "Object store differs");
        try (var input = Files.newInputStream(root.resolve(ref.key()))) {
            return ObjectStore.verify(id(), ref, input.readNBytes(MAX_BYTES + 1));
        } catch (IOException failure) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Immutable object unavailable", failure);
        }
    }
}
