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

package io.agentscope.extensions.durable.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Bounded, process-local MCP connections. Hold a Lease for the entire operation.
 * Resolved credentials are used only to connect and fingerprint a configuration in memory.
 * The caller owns credential resolution and must scope identities by tenant and server.
 */
public final class McpConnections implements AutoCloseable {
    private final int capacity;
    private final long idleNanos;
    private final Function<JsonNode, McpClientWrapper> factory;
    private final Map<String, Entry> current = new HashMap<>();
    private final Set<Entry> entries = new HashSet<>();
    private final ScheduledExecutorService maintenance;
    private boolean closed;

    private static final class Entry {
        final String identity;
        final String digest;
        final CompletableFuture<McpClientWrapper> client = new CompletableFuture<>();
        int users;
        long lastUsed = System.nanoTime();
        boolean retired;
        boolean closing;

        Entry(String identity, String digest) {
            this.identity = identity;
            this.digest = digest;
        }
    }

    public record Status(
            int connections, int borrowed, int initializing, int retiring, boolean closed) {}

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final McpClientWrapper client;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Entry entry, McpClientWrapper client) {
            this.entry = entry;
            this.client = client;
        }

        public McpClientWrapper client() {
            if (released.get()) throw new IllegalStateException("MCP lease released");
            return client;
        }

        public void close() {
            if (released.compareAndSet(false, true)) release(entry);
        }
    }

    public McpConnections() {
        this(256, Duration.ofMinutes(5));
    }

    public McpConnections(int capacity, Duration idleTimeout) {
        this(capacity, idleTimeout, McpConnections::connect);
    }

    /** The factory transfers ownership of each returned client to this pool. */
    public McpConnections(
            int capacity, Duration idleTimeout, Function<JsonNode, McpClientWrapper> factory) {
        if (capacity < 1 || capacity > 4096 || idleTimeout.isNegative() || idleTimeout.isZero())
            throw new IllegalArgumentException(
                    "MCP capacity must be 1..4096 and idle timeout positive");
        this.capacity = capacity;
        this.idleNanos = idleTimeout.toNanos();
        this.factory = Objects.requireNonNull(factory);
        maintenance =
                Executors.newSingleThreadScheduledExecutor(
                        action -> {
                            var thread = new Thread(action, "durable-mcp-connections");
                            thread.setDaemon(true);
                            return thread;
                        });
        long interval = Math.max(1, Math.min(60_000, idleTimeout.toMillis()));
        maintenance.scheduleWithFixedDelay(
                this::evictIdle, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** A configuration change retires the old connection only after its last caller releases it. */
    public Lease borrow(String identity, JsonNode configuration) {
        DurableJson.text(identity, "MCP connection identity");
        var frozen = DurableJson.copy(configuration);
        if (!frozen.isObject())
            throw new IllegalArgumentException("MCP configuration must be an object");
        String digest = DurableJson.hash(frozen);
        Entry selected;
        boolean create;
        while (true) {
            var unused = new ArrayList<Entry>();
            selected = null;
            create = false;
            synchronized (this) {
                if (closed) throw unavailable("MCP connections closed");
                var old = current.get(identity);
                if (old != null && !old.digest.equals(digest)) {
                    retire(old);
                    if (old.users == 0 && !old.closing) unused.add(old);
                    old = null;
                }
                if (old != null) {
                    old.users++;
                    selected = old;
                } else if (entries.size() < capacity) {
                    selected = new Entry(identity, digest);
                    selected.users = 1;
                    current.put(identity, selected);
                    entries.add(selected);
                    create = true;
                } else if (unused.isEmpty()) {
                    entries.stream()
                            .filter(entry -> entry.users == 0 && !entry.closing)
                            .min(Comparator.comparingLong(entry -> entry.lastUsed))
                            .ifPresent(
                                    entry -> {
                                        retire(entry);
                                        unused.add(entry);
                                    });
                }
            }
            unused.forEach(this::closeUnused);
            if (selected != null) break;
            if (unused.isEmpty()) throw unavailable("MCP connection capacity is occupied");
        }
        Entry entry = selected;
        if (create) {
            try {
                entry.client.complete(Objects.requireNonNull(factory.apply(frozen)));
            } catch (Throwable failure) {
                entry.client.completeExceptionally(failure);
                synchronized (this) {
                    retire(entry);
                }
            }
        }
        try {
            var client = entry.client.join();
            synchronized (this) {
                if (closed) throw unavailable("MCP connections closed during initialization");
            }
            return new Lease(entry, client);
        } catch (RuntimeException failure) {
            release(entry);
            if (failure instanceof CompletionException)
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "MCP connection initialization failed",
                        failure.getCause());
            throw failure;
        }
    }

    private void retire(Entry entry) {
        entry.retired = true;
        current.remove(entry.identity, entry);
    }

    private void release(Entry entry) {
        boolean close;
        synchronized (this) {
            entry.users--;
            entry.lastUsed = System.nanoTime();
            close = entry.retired && entry.users == 0;
        }
        if (close) closeUnused(entry);
    }

    private void closeUnused(Entry entry) {
        synchronized (this) {
            if (entry.users != 0 || !entry.retired || entry.closing) return;
            entry.closing = true;
        }
        try {
            if (!entry.client.isCompletedExceptionally()) {
                var client = entry.client.getNow(null);
                if (client != null) client.close();
            }
        } catch (RuntimeException failure) {
            System.getLogger(McpConnections.class.getName())
                    .log(System.Logger.Level.WARNING, "MCP connection close failed", failure);
        } finally {
            synchronized (this) {
                entries.remove(entry);
            }
        }
    }

    public void evictIdle() {
        var unused = new ArrayList<Entry>();
        synchronized (this) {
            long now = System.nanoTime();
            for (var entry : entries) {
                if (entry.users == 0 && !entry.closing && now - entry.lastUsed >= idleNanos) {
                    retire(entry);
                    unused.add(entry);
                }
            }
        }
        unused.forEach(this::closeUnused);
    }

    public synchronized Status status() {
        return new Status(
                entries.size(),
                entries.stream().mapToInt(entry -> entry.users).sum(),
                (int) entries.stream().filter(entry -> !entry.client.isDone()).count(),
                (int) entries.stream().filter(entry -> entry.retired).count(),
                closed);
    }

    /** Stops admission and idle maintenance; active leases retain their connection until released. */
    public void close() {
        var unused = new ArrayList<Entry>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (var entry : entries) {
                retire(entry);
                if (entry.users == 0) unused.add(entry);
            }
        }
        maintenance.shutdownNow();
        unused.forEach(this::closeUnused);
    }

    public static Duration timeout(JsonNode configuration) {
        return duration(configuration, "timeout", Duration.ofSeconds(60));
    }

    private static Duration duration(JsonNode configuration, String field, Duration fallback) {
        String value = configuration.path(field).asText("");
        var duration = value.isBlank() ? fallback : Duration.parse(value);
        if (duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("MCP timeouts must be positive");
        return duration;
    }

    private static McpClientWrapper connect(JsonNode configuration) {
        String url = configuration.required("url").asText();
        var uri = URI.create(url);
        if (!Set.of("https", "http").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("MCP connection requires an HTTP endpoint");
        var builder = McpClientBuilder.create(configuration.required("name").asText());
        String transport = configuration.path("transport").asText("streamable_http");
        if ("sse".equals(transport)) builder.sseTransport(url);
        else if ("streamable_http".equals(transport)) builder.streamableHttpTransport(url);
        else throw new IllegalArgumentException("Unknown MCP transport");
        configuration
                .path("headers")
                .fields()
                .forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
        configuration
                .path("queryParams")
                .fields()
                .forEachRemaining(e -> builder.queryParam(e.getKey(), e.getValue().asText()));
        var initialization =
                duration(configuration, "initializationTimeout", Duration.ofSeconds(30));
        builder.timeout(timeout(configuration))
                .initializationTimeout(initialization)
                .propagateMeta(configuration.path("propagateMeta").asBoolean(true));
        var client = builder.buildSync();
        try {
            client.initialize().block(initialization);
            return client;
        } catch (RuntimeException failure) {
            try {
                client.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static DurableException unavailable(String message) {
        return new DurableException(DurableException.Code.UNAVAILABLE, message);
    }
}
