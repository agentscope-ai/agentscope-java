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
package io.agentscope.core.tool.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Abstract wrapper for MCP (Model Context Protocol) clients.
 * This class manages the lifecycle of MCP client connections and provides
 * a unified interface for both asynchronous and synchronous client implementations.
 *
 * <p>The wrapper handles:
 * <ul>
 *   <li>Client initialization and connection management</li>
 *   <li>Tool discovery and caching</li>
 *   <li>Tool invocation through the MCP protocol</li>
 *   <li>Resource cleanup on close</li>
 * </ul>
 *
 * @see McpAsyncClientWrapper
 * @see McpSyncClientWrapper
 */
public abstract class McpClientWrapper implements AutoCloseable {

    /**
     * Default upper bound for the graceful close attempt performed by {@link #close()}.
     *
     * <p>Graceful close has no timeout of its own in the MCP SDK, so it must be bounded here:
     * when the MCP server stops responding, an unbounded graceful close would block the caller
     * forever instead of returning. See {@link #closeWithTimeout}.
     */
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger closeLogger = LoggerFactory.getLogger(McpClientWrapper.class);

    /** Unique identifier for this MCP client */
    protected final String name;

    /** Cache of tools available from this MCP server */
    protected final Map<String, McpSchema.Tool> cachedTools;

    /** Flag indicating whether the client has been initialized */
    protected volatile boolean initialized = false;

    /**
     * Constructs a new MCP client wrapper.
     *
     * @param name unique identifier for this client
     */
    protected McpClientWrapper(String name) {
        this.name = name;
        this.cachedTools = new ConcurrentHashMap<>();
    }

    /**
     * Gets the unique name of this MCP client.
     *
     * @return the client name
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if this client has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Initializes the MCP client connection and caches available tools.
     * This method must be called before any tool operations.
     *
     * @return a Mono that completes when initialization is finished
     */
    public abstract Mono<Void> initialize();

    /**
     * Lists all tools available from this MCP server.
     *
     * @return a Mono emitting the list of available tools
     */
    public abstract Mono<List<McpSchema.Tool>> listTools();

    /**
     * Invokes a tool on the MCP server.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments);

    /**
     * Invokes a tool on the MCP server.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @param meta the metadata to pass to the tool
     * @return a Mono emitting the tool call result
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta);

    /**
     * Gets a cached tool definition by name.
     *
     * @param toolName the name of the tool
     * @return the tool definition, or null if not found
     */
    public McpSchema.Tool getCachedTool(String toolName) {
        return cachedTools.get(toolName);
    }

    /**
     * Closes this MCP client and releases all resources.
     * This method is idempotent and can be called multiple times safely.
     *
     * <p>Implementations must not block the caller indefinitely: the graceful close attempt is
     * bounded by {@link #DEFAULT_CLOSE_TIMEOUT} (or a configured value), see
     * {@link #closeWithTimeout}.
     */
    @Override
    public abstract void close();

    /**
     * Runs a graceful-close attempt under a bounded wait, falling back to a forceful close.
     *
     * <p>This exists because the MCP SDK's graceful close has no timeout of its own: when the
     * server stops responding, the graceful-close {@link Mono} never terminates and an unbounded
     * wait would hang the calling thread forever. The bounded wait guarantees that {@code close()}
     * returns, and the forceful close guarantees that the transport is released either way.
     *
     * <p>Behaviour on each path:
     * <ul>
     *   <li><b>Graceful close completes</b> - nothing else is done.
     *   <li><b>Graceful close signals an error</b> - reported as a failure (not as a timeout) and
     *       the forceful close is used.
     *   <li><b>Graceful close does not finish within {@code closeTimeout}</b> - the subscription is
     *       cancelled explicitly so the pending close chain is not left dangling, then the forceful
     *       close is used.
     * </ul>
     *
     * <p>The forceful close is assumed to release the transport without waiting on the peer (the
     * MCP SDK {@code close()} is synchronous and performs no network round-trip); without that
     * assumption the bound would only cover the graceful attempt, not resource release.
     *
     * @param gracefulClose lazy graceful-close attempt; work must start on subscription, not on
     *     construction, so that a blocking implementation cannot run on the calling thread
     * @param forceClose forceful close, used on every path except a fully successful graceful close
     * @param closeTimeout upper bound for the graceful attempt; must be positive
     */
    protected void closeWithTimeout(
            Mono<Void> gracefulClose, Runnable forceClose, Duration closeTimeout) {
        AtomicBoolean gracefulSucceeded = new AtomicBoolean(false);
        AtomicReference<Throwable> gracefulError = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Disposable subscription =
                gracefulClose
                        .doOnSuccess(v -> gracefulSucceeded.set(true))
                        .doOnError(gracefulError::set)
                        .doFinally(signal -> finished.countDown())
                        // Errors are handled below; keep Reactor from also dropping them to the
                        // log.
                        .subscribe(v -> {}, e -> {});

        boolean completed = awaitCompletion(finished, closeTimeout);

        if (!completed) {
            subscription.dispose();
            closeLogger.warn(
                    "Graceful close of MCP client '{}' did not complete within {}, cancelled it and"
                            + " fell back to forceful close",
                    name,
                    closeTimeout);
        } else if (gracefulError.get() != null) {
            closeLogger.warn(
                    "Graceful close of MCP client '{}' failed, falling back to forceful close: {}",
                    name,
                    gracefulError.get().getMessage());
        }

        if (!gracefulSucceeded.get()) {
            forceClose.run();
        }
    }

    private static boolean awaitCompletion(CountDownLatch finished, Duration closeTimeout) {
        try {
            return finished.await(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
