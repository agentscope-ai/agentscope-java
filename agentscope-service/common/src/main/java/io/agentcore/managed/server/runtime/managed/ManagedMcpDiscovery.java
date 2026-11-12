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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only asynchronous discovery; independent queries never wait for a concurrency permit. */
public final class ManagedMcpDiscovery implements ManagedMcpCatalog.Discovery {
    private final BiFunction<String, McpServerConfig, Mono<McpClientWrapper>> clients;
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ConcurrentHashMap<String, Flight> flights = new ConcurrentHashMap<>();
    private final Map<String, Cached> completed = new LinkedHashMap<>(16, 0.75f, true);
    private final long ttlNanos;
    private final int cacheEntries;
    private final LongSupplier clock;

    private record Cached(List<ManagedMcpCatalog.Tool> tools, long expires) {}

    public ManagedMcpDiscovery() {
        this(Duration.ofSeconds(60), 4096);
    }

    public ManagedMcpDiscovery(Duration ttl, int cacheEntries) {
        this(ManagedMcpDiscovery::client, ttl, cacheEntries, System::nanoTime);
    }

    ManagedMcpDiscovery(BiFunction<String, McpServerConfig, Mono<McpClientWrapper>> clients) {
        this(clients, Duration.ofSeconds(60), 4096, System::nanoTime);
    }

    ManagedMcpDiscovery(
            BiFunction<String, McpServerConfig, Mono<McpClientWrapper>> clients,
            Duration ttl,
            int cacheEntries,
            LongSupplier clock) {
        if (ttl.isNegative() || cacheEntries < 1)
            throw new IllegalArgumentException(
                    "Discovery cache requires nonnegative TTL and positive size");
        this.clients = clients;
        this.ttlNanos = ttl.toNanos();
        this.cacheEntries = cacheEntries;
        this.clock = clock;
    }

    @Override
    public ManagedMcpCatalog.Description discover(
            ToolsConfig tools, SessionResolveResult resolved, BooleanSupplier cancelled) {
        if (Schedulers.isInNonBlockingThread())
            throw new IllegalStateException(
                    "Synchronous discovery requires a blocking Runtime thread");
        return discoverAsync("standalone", tools, resolved, cancelled).block();
    }

    @Override
    public Mono<ManagedMcpCatalog.Description> discoverAsync(
            String isolation,
            ToolsConfig tools,
            SessionResolveResult resolved,
            BooleanSupplier cancelled) {
        return Mono.defer(
                () -> {
                    // All servers subscribe concurrently. map/zip preserves configured descriptor
                    // order.
                    var requests = new ArrayList<Mono<ManagedMcpCatalog.Description>>();
                    for (var entry : tools.getMcpServers().entrySet()) {
                        String name = entry.getKey();
                        var config = entry.getValue();
                        requests.add(
                                Mono.defer(
                                                () -> {
                                                    validateTransport(resolved, config);
                                                    String key =
                                                            key(isolation, name, config, resolved);
                                                    var cached = cached(key);
                                                    return cached != null
                                                            ? Mono.just(cached)
                                                            : flights.computeIfAbsent(
                                                                            key,
                                                                            ignored ->
                                                                                    new Flight(
                                                                                            key,
                                                                                            name,
                                                                                            config))
                                                                    .result;
                                                })
                                        .map(
                                                found ->
                                                        new ManagedMcpCatalog.Description(
                                                                copy(found), List.of()))
                                        .onErrorResume(
                                                failure ->
                                                        config.isRequired()
                                                                ? Mono.error(
                                                                        new ManagedStateException(
                                                                                "mcp_connection_failed",
                                                                                "Required MCP"
                                                                                    + " server is"
                                                                                    + " unavailable:"
                                                                                    + " "
                                                                                        + name,
                                                                                failure))
                                                                : Mono.just(
                                                                        new ManagedMcpCatalog
                                                                                .Description(
                                                                                List.of(),
                                                                                List.of(name)))));
                    }
                    Mono<ManagedMcpCatalog.Description> query =
                            requests.isEmpty()
                                    ? Mono.just(
                                            new ManagedMcpCatalog.Description(List.of(), List.of()))
                                    : Mono.zip(
                                            requests,
                                            values -> {
                                                var descriptors =
                                                        new ArrayList<ManagedMcpCatalog.Tool>();
                                                var unavailable = new ArrayList<String>();
                                                for (var value : values) {
                                                    var description =
                                                            (ManagedMcpCatalog.Description) value;
                                                    descriptors.addAll(description.tools());
                                                    unavailable.addAll(description.unavailable());
                                                }
                                                return new ManagedMcpCatalog.Description(
                                                        List.copyOf(descriptors),
                                                        List.copyOf(unavailable));
                                            });
                    // Cancellation is per waiter: it must not cancel a shared query needed by
                    // another Turn.
                    // Schedule the next check only after the previous JDBC check completes.
                    // Fixed-rate ticks overflow under backpressure and must not fail discovery.
                    var cancellation =
                            Mono.fromCallable(cancelled::getAsBoolean)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .delaySubscription(Duration.ofSeconds(1))
                                    .repeat()
                                    .filter(Boolean::booleanValue)
                                    .next()
                                    .map(
                                            ignored ->
                                                    new ManagedMcpCatalog.Description(
                                                            List.of(), List.of()));
                    return Mono.fromCallable(cancelled::getAsBoolean)
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(
                                    stopped ->
                                            stopped
                                                    ? Mono.just(
                                                            new ManagedMcpCatalog.Description(
                                                                    List.of(), List.of()))
                                                    : Mono.firstWithSignal(query, cancellation));
                });
    }

    private final class Flight {
        final Mono<List<ManagedMcpCatalog.Tool>> result;

        Flight(String key, String name, McpServerConfig config) {
            // refCount cancels the underlying transport only after the last waiter leaves.
            result =
                    load(name, config)
                            .doOnNext(
                                    tools -> {
                                        remember(key, tools);
                                        flights.remove(key, this);
                                    })
                            .doOnError(failure -> flights.remove(key, this))
                            .doFinally(signal -> flights.remove(key, this))
                            .flux()
                            .replay(1)
                            .refCount(1)
                            .next();
        }
    }

    private Mono<List<ManagedMcpCatalog.Tool>> load(String name, McpServerConfig config) {
        Duration initialization =
                config.getInitializationTimeout() == null
                        ? Duration.ofSeconds(30)
                        : config.getInitializationTimeout();
        Duration request =
                config.getTimeout() == null ? Duration.ofSeconds(120) : config.getTimeout();
        return Mono.usingWhen(
                Mono.defer(() -> clients.apply(name, config))
                        .timeout(initialization)
                        .switchIfEmpty(Mono.error(new IllegalStateException("Missing MCP client"))),
                connection ->
                        connection
                                .initialize()
                                .timeout(initialization)
                                .then(Mono.defer(connection::listTools).timeout(request))
                                .switchIfEmpty(
                                        Mono.error(
                                                new IllegalStateException(
                                                        "Missing MCP descriptors")))
                                .map(listed -> describe(name, config, listed)),
                connection ->
                        Mono.fromRunnable(connection::close)
                                .subscribeOn(Schedulers.boundedElastic()));
    }

    private List<ManagedMcpCatalog.Tool> describe(
            String name, McpServerConfig config, List<McpSchema.Tool> listed) {
        var queryTools = new HashSet<String>();
        for (var tool : listed) {
            var recovery = recovery(tool);
            if (recovery == null) continue;
            var query =
                    listed.stream()
                            .filter(candidate -> recovery.queryTool().equals(candidate.name()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "MCP recovery lookup tool is missing"));
            if (query.annotations() == null
                    || !Boolean.TRUE.equals(query.annotations().readOnlyHint()))
                throw new IllegalArgumentException("MCP recovery lookup must be read-only");
            queryTools.add(query.name());
        }
        return listed.stream()
                .filter(tool -> !queryTools.contains(tool.name()) && enabled(config, tool.name()))
                .map(tool -> descriptor(name, config, tool))
                .toList();
    }

    private String key(
            String isolation, String name, McpServerConfig config, SessionResolveResult resolved) {
        // Never use endpoint alone: credentials, filters, owner and environment affect visibility.
        var settings = new LinkedHashMap<String, Object>();
        settings.put("server", name);
        settings.put("transport", config.getTransport());
        settings.put("url", config.getUrl());
        settings.put("command", config.getCommand());
        settings.put("args", config.getArgs());
        settings.put("env", config.getEnv());
        settings.put("headers", config.getHeaders());
        settings.put("query", config.getQueryParams());
        settings.put("enabled", config.getEnableTools());
        settings.put("disabled", config.getDisableTools());
        settings.put("default", config.isDefaultToolsEnabled());
        settings.put("prefix", config.isPrefixToolNames());
        settings.put("initialization", config.getInitializationTimeout());
        settings.put("timeout", config.getTimeout());
        if (resolved != null) {
            settings.put("owner", resolved.session() == null ? null : resolved.session().ownerId());
            settings.put("environment", resolved.environment());
            settings.put("agent", resolved.agentSnapshot());
            // stdio processes may observe workspace-local state; do not share between Sessions.
            if ("stdio".equalsIgnoreCase(config.getTransport())) {
                settings.put(
                        "session", resolved.session() == null ? null : resolved.session().id());
                settings.put("workspace", resolved.workspacePath());
            }
        }
        try {
            return ManagedScope.fingerprint(isolation, mapper.writeValueAsString(settings));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid MCP discovery configuration", invalid);
        }
    }

    private synchronized List<ManagedMcpCatalog.Tool> cached(String key) {
        var value = completed.get(key);
        if (value == null) return null;
        if (value.expires() - clock.getAsLong() <= 0) {
            completed.remove(key);
            return null;
        }
        return value.tools();
    }

    private synchronized void remember(String key, List<ManagedMcpCatalog.Tool> tools) {
        if (ttlNanos == 0) return;
        completed.put(key, new Cached(copy(tools), clock.getAsLong() + ttlNanos));
        while (completed.size() > cacheEntries)
            completed.remove(completed.keySet().iterator().next());
    }

    private List<ManagedMcpCatalog.Tool> copy(List<ManagedMcpCatalog.Tool> tools) {
        return mapper.convertValue(
                tools,
                mapper.getTypeFactory()
                        .constructCollectionType(List.class, ManagedMcpCatalog.Tool.class));
    }

    public static ManagedMcpCatalog.Tool descriptor(
            String server, McpServerConfig config, McpSchema.Tool tool) {
        return new ManagedMcpCatalog.Tool(
                server,
                (config.isPrefixToolNames() ? server + "__" : "") + tool.name(),
                tool.name(),
                tool.description() == null ? "" : tool.description(),
                McpTool.convertMcpSchemaToParameters(tool.inputSchema(), Set.of()),
                tool.outputSchema(),
                tool.annotations() != null
                        && Boolean.TRUE.equals(tool.annotations().readOnlyHint()),
                recovery(tool));
    }

    public static ManagedMcpCatalog.Recovery recovery(McpSchema.Tool tool) {
        if (tool.meta() == null || !tool.meta().containsKey(ManagedMcpCatalog.RECOVERY_META))
            return null;
        var recovery =
                new ObjectMapper()
                        .convertValue(
                                tool.meta().get(ManagedMcpCatalog.RECOVERY_META),
                                ManagedMcpCatalog.Recovery.class);
        if (recovery == null || recovery.queryTool().equals(tool.name()))
            throw new IllegalArgumentException("Invalid MCP execution recovery lookup");
        return recovery;
    }

    public static boolean enabled(McpServerConfig config, String name) {
        if (config.getDisableTools() != null && config.getDisableTools().contains(name))
            return false;
        return config.getEnableTools() != null && !config.getEnableTools().isEmpty()
                ? config.getEnableTools().contains(name)
                : config.isDefaultToolsEnabled();
    }

    public static void validateTransport(SessionResolveResult resolved, McpServerConfig config) {
        if ("stdio".equalsIgnoreCase(config.getTransport())
                && (resolved == null
                        || resolved.environment() == null
                        || !"local".equals(resolved.environment().type())))
            throw new IllegalArgumentException(
                    "stdio MCP requires an explicit local environment; expose hosted MCP over"
                            + " HTTP");
    }

    private static Mono<McpClientWrapper> client(String name, McpServerConfig config) {
        var builder = McpClientBuilder.create(name);
        String transport = config.getTransport();
        if ("http".equalsIgnoreCase(transport)
                || "streamable-http".equalsIgnoreCase(transport)
                || "streamablehttp".equalsIgnoreCase(transport))
            builder.streamableHttpTransport(config.getUrl());
        else if ("sse".equalsIgnoreCase(transport)) builder.sseTransport(config.getUrl());
        else if ("stdio".equalsIgnoreCase(transport))
            builder.stdioTransport(
                    config.getCommand(),
                    config.getArgs() == null ? List.of() : config.getArgs(),
                    config.getEnv() == null ? Map.of() : config.getEnv());
        else throw new IllegalArgumentException("Unsupported MCP transport");
        ManagedMcpHttpTransport.configure(builder);
        if (config.getHeaders() != null) builder.headers(config.getHeaders());
        if (config.getQueryParams() != null) builder.queryParams(config.getQueryParams());
        if (config.getTimeout() != null) builder.timeout(config.getTimeout());
        if (config.getInitializationTimeout() != null)
            builder.initializationTimeout(config.getInitializationTimeout());
        return builder.buildAsync();
    }
}
