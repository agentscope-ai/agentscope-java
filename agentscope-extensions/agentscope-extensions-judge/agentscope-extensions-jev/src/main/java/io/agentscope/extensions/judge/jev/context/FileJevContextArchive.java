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

package io.agentscope.extensions.judge.jev.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Local archive with scoped names, content verification and atomic publication. */
public final class FileJevContextArchive implements JevContextArchive {
    private final Path root;
    private final int maxBytes;

    public FileJevContextArchive(Path root, int maxBytes) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (maxBytes < 1)
            throw new IllegalArgumentException("positive archive byte limit required");
        this.maxBytes = maxBytes;
    }

    @Override
    public Mono<String> save(Scope scope, List<Msg> messages) {
        return Mono.defer(
                () -> {
                    // Serialize before asynchronous IO, so no mutable message object is retained by
                    // storage.
                    byte[] data =
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "version",
                                                    1,
                                                    "scope",
                                                    scope,
                                                    "messages",
                                                    List.copyOf(messages)))
                                    .getBytes(StandardCharsets.UTF_8);
                    if (data.length > maxBytes)
                        return Mono.error(new IllegalArgumentException("archive limit"));
                    return Mono.fromCallable(
                                    () -> {
                                        Path directory = directory(scope);
                                        String ref = digest(data);
                                        Path target = directory.resolve(ref + ".json");
                                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                                            verify(target, ref);
                                            return ref;
                                        }
                                        Path temporary =
                                                Files.createTempFile(
                                                        directory, ".pending-", ".json");
                                        try {
                                            privateFile(temporary);
                                            Files.write(temporary, data);
                                            Files.move(
                                                    temporary,
                                                    target,
                                                    StandardCopyOption.ATOMIC_MOVE);
                                            return ref;
                                        } finally {
                                            Files.deleteIfExists(temporary);
                                        }
                                    })
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    public Mono<List<Msg>> restore(Scope scope, String reference) {
        return Mono.fromCallable(
                        () -> {
                            if (reference == null || !reference.matches("[a-f0-9]{64}"))
                                throw new IllegalArgumentException("invalid archive reference");
                            Path file = directory(scope).resolve(reference + ".json");
                            byte[] data = verify(file, reference);
                            var json =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(data);
                            var codec = JsonUtils.getJsonCodec();
                            if (json.path("version").asInt() != 1
                                    || !scope.equals(
                                            codec.fromJson(
                                                    json.path("scope").toString(), Scope.class)))
                                throw new IllegalArgumentException(
                                        "archive scope or version mismatch");
                            java.util.ArrayList<Msg> messages = new java.util.ArrayList<>();
                            for (var item : json.path("messages"))
                                messages.add(codec.fromJson(item.toString(), Msg.class));
                            return List.copyOf(messages);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Path directory(Scope scope) throws Exception {
        Objects.requireNonNull(scope);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root))
            throw new IllegalArgumentException("archive root is a symlink");
        Path directory =
                root.resolve(
                        digest(
                                JsonUtils.getJsonCodec()
                                        .toJson(scope)
                                        .getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory))
            throw new IllegalArgumentException("archive scope is a symlink");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private byte[] verify(Path file, String reference) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxBytes)
            throw new IllegalArgumentException("missing, unsafe or oversized archive");
        byte[] data = Files.readAllBytes(file);
        if (!digest(data).equals(reference))
            throw new IllegalArgumentException("archive checksum mismatch");
        return data;
    }

    private static void privateFile(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
