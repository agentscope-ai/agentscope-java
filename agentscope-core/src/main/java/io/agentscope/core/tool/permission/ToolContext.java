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
package io.agentscope.core.tool.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read-cache backing for file-touching tools (Read / Write / Edit).
 *
 * <p>Maintains an ordered LRU list of {@link ReadCacheEntry} bounded by both entry count and
 * total bytes. Cache reads are validated against the file's current mtime; stale entries are
 * evicted on access. All filesystem interaction runs on {@link Schedulers#boundedElastic()} so
 * Reactor's non-blocking schedulers are never blocked.
 */
public final class ToolContext {

    private final int maxCacheFiles;
    private final double maxCacheBytes;
    private final List<ReadCacheEntry> readFileCache = new ArrayList<>();
    private final List<String> activatedGroups;

    private ToolContext(Builder builder) {
        if (builder.maxCacheFiles <= 1) {
            throw new IllegalArgumentException("maxCacheFiles must be > 1");
        }
        if (builder.maxCacheBytes <= 10000) {
            throw new IllegalArgumentException("maxCacheBytes must be > 10000");
        }
        this.maxCacheFiles = builder.maxCacheFiles;
        this.maxCacheBytes = builder.maxCacheBytes;
        this.activatedGroups = new ArrayList<>(builder.activatedGroups);
    }

    public int getMaxCacheFiles() {
        return maxCacheFiles;
    }

    public double getMaxCacheBytes() {
        return maxCacheBytes;
    }

    /** Snapshot of the cache list at call time. Modifications do not affect the live cache. */
    public List<ReadCacheEntry> getReadFileCache() {
        synchronized (readFileCache) {
            return List.copyOf(readFileCache);
        }
    }

    public List<String> getActivatedGroups() {
        return List.copyOf(activatedGroups);
    }

    /**
     * Look up the cached entry for {@code filePath}. Evicts and returns empty if the file's mtime
     * has changed or the file no longer exists.
     */
    public Mono<Optional<ReadCacheEntry>> getCache(String filePath) {
        return Mono.fromCallable(
                        () -> {
                            synchronized (readFileCache) {
                                Iterator<ReadCacheEntry> iterator = readFileCache.iterator();
                                while (iterator.hasNext()) {
                                    ReadCacheEntry entry = iterator.next();
                                    if (!entry.filePath().equals(filePath)) {
                                        continue;
                                    }
                                    Double current = currentMtime(filePath);
                                    if (current != null && current == entry.updatedAt()) {
                                        return Optional.of(entry);
                                    }
                                    iterator.remove();
                                    return Optional.<ReadCacheEntry>empty();
                                }
                                return Optional.<ReadCacheEntry>empty();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Insert {@code lines} for {@code filePath} into the cache, evicting older entries to satisfy
     * both {@code maxCacheFiles} and {@code maxCacheBytes}. If the file's mtime is unavailable the
     * insert is silently skipped.
     */
    public Mono<Void> cacheFile(String filePath, List<String> lines) {
        return Mono.<Void>fromCallable(
                        () -> {
                            Double updatedAt = currentMtime(filePath);
                            if (updatedAt == null) {
                                return null;
                            }
                            double newEntryBytes = approximateKilobytes(lines);
                            synchronized (readFileCache) {
                                readFileCache.removeIf(entry -> entry.filePath().equals(filePath));
                                while (readFileCache.size() >= maxCacheFiles) {
                                    readFileCache.remove(0);
                                }
                                double currentBytes =
                                        readFileCache.stream()
                                                .mapToDouble(ReadCacheEntry::bytes)
                                                .sum();
                                while (!readFileCache.isEmpty()
                                        && currentBytes + newEntryBytes > maxCacheBytes) {
                                    currentBytes -= readFileCache.remove(0).bytes();
                                }
                                readFileCache.add(
                                        new ReadCacheEntry(
                                                lines, updatedAt, newEntryBytes, filePath));
                            }
                            return null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private static Double currentMtime(String filePath) {
        try {
            return Files.getLastModifiedTime(Path.of(filePath)).toMillis() / 1000.0;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static double approximateKilobytes(List<String> lines) {
        long bytes = 0L;
        for (String line : lines) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes / 1024.0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxCacheFiles = 100;
        private double maxCacheBytes = 25_000;
        private final List<String> activatedGroups = new ArrayList<>();

        private Builder() {}

        public Builder maxCacheFiles(int maxCacheFiles) {
            this.maxCacheFiles = maxCacheFiles;
            return this;
        }

        public Builder maxCacheBytes(double maxCacheBytes) {
            this.maxCacheBytes = maxCacheBytes;
            return this;
        }

        public Builder addActivatedGroup(String groupName) {
            this.activatedGroups.add(groupName);
            return this;
        }

        public ToolContext build() {
            return new ToolContext(this);
        }
    }
}
