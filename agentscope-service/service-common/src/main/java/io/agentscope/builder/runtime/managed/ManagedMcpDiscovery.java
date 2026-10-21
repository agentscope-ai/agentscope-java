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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only MCP discovery on Runtime's bounded blocking threads; never invokes a server tool. */
public final class ManagedMcpDiscovery implements ManagedMcpCatalog.Discovery {
    private final BiFunction<String, McpServerConfig, Mono<McpClientWrapper>> clients;
    private final Semaphore capacity = new Semaphore(16);

    public ManagedMcpDiscovery() {
        this(ManagedMcpDiscovery::client);
    }

    ManagedMcpDiscovery(BiFunction<String, McpServerConfig, Mono<McpClientWrapper>> clients) {
        this.clients = clients;
    }

    @Override
    public ManagedMcpCatalog.Description discover(
            ToolsConfig tools, SessionResolveResult resolved, BooleanSupplier cancelled) {
        if (Schedulers.isInNonBlockingThread())
            throw new IllegalStateException("MCP discovery requires a blocking Runtime thread");
        // Capacity is bounded independently of active Harness slots. Waiting for a slot remains
        // cancellable and does not create another MQ task or an unbounded executor queue.
        try {
            while (!capacity.tryAcquire(200, TimeUnit.MILLISECONDS)) check(cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("MCP discovery interrupted");
        }
        try {
            var descriptors = new ArrayList<ManagedMcpCatalog.Tool>();
            var unavailable = new ArrayList<String>();
            for (var entry : tools.getMcpServers().entrySet()) {
                check(cancelled);
                var config = entry.getValue();
                McpClientWrapper connection = null;
                try {
                    validateTransport(resolved, config);
                    Duration initialization =
                            config.getInitializationTimeout() == null
                                    ? Duration.ofSeconds(30)
                                    : config.getInitializationTimeout();
                    Duration request =
                            config.getTimeout() == null
                                    ? Duration.ofSeconds(120)
                                    : config.getTimeout();
                    connection =
                            await(clients.apply(entry.getKey(), config), initialization, cancelled);
                    if (connection == null) throw new IllegalStateException("Missing MCP client");
                    await(connection.initialize(), initialization, cancelled);
                    var listed = await(connection.listTools(), request, cancelled);
                    if (listed == null) throw new IllegalStateException("Missing MCP descriptors");
                    var queryTools = new HashSet<String>();
                    for (var tool : listed) {
                        var recovery = recovery(tool);
                        if (recovery == null) continue;
                        var query =
                                listed.stream()
                                        .filter(
                                                candidate ->
                                                        recovery.queryTool()
                                                                .equals(candidate.name()))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "MCP recovery lookup tool is"
                                                                        + " missing"));
                        if (query.annotations() == null
                                || !Boolean.TRUE.equals(query.annotations().readOnlyHint()))
                            throw new IllegalArgumentException(
                                    "MCP recovery lookup must be read-only");
                        queryTools.add(query.name());
                    }
                    for (var tool : listed)
                        if (!queryTools.contains(tool.name()) && enabled(config, tool.name()))
                            descriptors.add(descriptor(entry.getKey(), config, tool));
                } catch (CancellationException interrupted) {
                    throw interrupted;
                } catch (RuntimeException failure) {
                    check(cancelled);
                    if (config.isRequired())
                        throw new ManagedStateException(
                                "mcp_connection_failed",
                                "Required MCP server is unavailable: " + entry.getKey());
                    unavailable.add(entry.getKey());
                } finally {
                    if (connection != null) connection.close();
                }
            }
            check(cancelled);
            return new ManagedMcpCatalog.Description(descriptors, unavailable);
        } catch (CancellationException cancelledDiscovery) {
            if (Thread.currentThread().isInterrupted() || !cancelled.getAsBoolean())
                throw cancelledDiscovery;
            return new ManagedMcpCatalog.Description(List.of(), List.of());
        } finally {
            capacity.release();
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new CancellationException("MCP discovery cancelled");
    }

    private static <T> T await(Mono<T> operation, Duration timeout, BooleanSupplier cancelled) {
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("MCP timeout must be positive");
        var future = operation.toFuture();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                check(cancelled);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("MCP discovery timed out");
                try {
                    return future.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException pending) {
                    // Recheck cancellation without issuing MCP calls on timer or I/O threads.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("MCP discovery interrupted");
        } catch (ExecutionException failed) {
            throw new IllegalStateException("MCP discovery failed", failed.getCause());
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
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
        if (config.getHeaders() != null) builder.headers(config.getHeaders());
        if (config.getQueryParams() != null) builder.queryParams(config.getQueryParams());
        if (config.getTimeout() != null) builder.timeout(config.getTimeout());
        if (config.getInitializationTimeout() != null)
            builder.initializationTimeout(config.getInitializationTimeout());
        return builder.buildAsync();
    }
}
