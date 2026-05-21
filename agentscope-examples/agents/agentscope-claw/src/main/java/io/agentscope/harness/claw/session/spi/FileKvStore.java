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
package io.agentscope.harness.claw.session.spi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-filesystem {@link KvStore} that serialises each key to its own JSON file under {@code
 * baseDir / keyToRelativePath(key)}. Per-key in-memory cache + per-key mutex. Single-node only —
 * concurrent writers from multiple JVMs will race.
 *
 * <p>The {@link #keyToRelativePath} function decides directory layout: the existing
 * UserBindingStore uses {@code users/{userId}/bindings.json}; new callers are free to pick any
 * shape.
 */
public class FileKvStore<V> implements KvStore<V> {

    private static final Logger log = LoggerFactory.getLogger(FileKvStore.class);

    private final ObjectMapper mapper;
    private final Path baseDir;
    private final TypeReference<V> typeRef;
    private final Function<String, Path> keyToRelativePath;
    private final Supplier<List<String>> keysEnumerator;
    private final ConcurrentHashMap<String, V> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileKvStore(
            ObjectMapper mapper,
            Path baseDir,
            TypeReference<V> typeRef,
            Function<String, Path> keyToRelativePath) {
        this(mapper, baseDir, typeRef, keyToRelativePath, null);
    }

    /**
     * @param keysEnumerator optional supplier for {@link #keys()} when the caller needs on-disk
     *     enumeration. {@code null} = cache-only enumeration (fine when {@link #keys()} isn't used
     *     or when callers track keys themselves).
     */
    public FileKvStore(
            ObjectMapper mapper,
            Path baseDir,
            TypeReference<V> typeRef,
            Function<String, Path> keyToRelativePath,
            Supplier<List<String>> keysEnumerator) {
        this.mapper = Objects.requireNonNull(mapper);
        this.baseDir = Objects.requireNonNull(baseDir);
        this.typeRef = Objects.requireNonNull(typeRef);
        this.keyToRelativePath = Objects.requireNonNull(keyToRelativePath);
        this.keysEnumerator = keysEnumerator;
    }

    @Override
    public Optional<V> get(String key) {
        V cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        V loaded = loadFromDisk(key);
        if (loaded == null) return Optional.empty();
        cache.put(key, loaded);
        return Optional.of(loaded);
    }

    @Override
    public void put(String key, V value) {
        synchronized (lockFor(key)) {
            cache.put(key, value);
            writeToDisk(key, value);
        }
    }

    @Override
    public void remove(String key) {
        synchronized (lockFor(key)) {
            cache.remove(key);
            Path file = fileFor(key);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete " + file, e);
            }
        }
    }

    @Override
    public V mutate(String key, V emptyValue, Function<V, V> mutator) {
        synchronized (lockFor(key)) {
            V current = get(key).orElse(emptyValue);
            V next = mutator.apply(current);
            cache.put(key, next);
            writeToDisk(key, next);
            return next;
        }
    }

    @Override
    public List<String> keys() {
        if (keysEnumerator != null) return keysEnumerator.get();
        return new ArrayList<>(cache.keySet());
    }

    private V loadFromDisk(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(file.toFile(), typeRef);
        } catch (IOException e) {
            log.warn("Failed to load {} for key {}: {}", file, key, e.getMessage());
            return null;
        }
    }

    private void writeToDisk(String key, V value) {
        Path file = fileFor(key);
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + file, e);
        }
    }

    private Path fileFor(String key) {
        return baseDir.resolve(keyToRelativePath.apply(key));
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /** Exposes the configured base directory for callers that need to enumerate. */
    public Path baseDir() {
        return baseDir;
    }
}
