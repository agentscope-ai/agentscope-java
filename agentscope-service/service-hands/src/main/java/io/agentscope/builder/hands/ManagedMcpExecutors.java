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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedDeliverySuspended;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedMcpBatch;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog.Recovery;
import io.agentscope.builder.runtime.managed.ManagedMcpDiscovery;
import io.agentscope.builder.runtime.managed.ManagedRuntimeLog;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolResult;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedToolTarget;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Worker executes MCP calls; legacy discovery tasks are retained only to drain prior deployments. */
public final class ManagedMcpExecutors implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedMcpExecutors.class);
    private final ManagedHandsStore store;
    private final ManagedToolResultPublisher results;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final BiFunction<String, McpServerConfig, McpClientWrapper> factory;
    private final ConcurrentHashMap<String, ConnectionEntry> clients = new ConcurrentHashMap<>();
    private final Semaphore clientCapacity;
    private final ConcurrentHashMap<String, CompletableFuture<ConnectionEntry>> openings =
            new ConcurrentHashMap<>();

    private static final class ConnectionEntry {
        final McpClientWrapper client;
        int users;
        boolean closed;
        volatile long lastUsed = System.nanoTime();

        ConnectionEntry(McpClientWrapper client) {
            this.client = client;
        }

        synchronized boolean acquire() {
            if (closed) return false;
            users++;
            lastUsed = System.nanoTime();
            return true;
        }

        synchronized void release() {
            users--;
            lastUsed = System.nanoTime();
        }

        synchronized boolean retire() {
            if (closed || users != 0) return false;
            closed = true;
            return true;
        }
    }

    private record Connection(ConnectionEntry entry) implements AutoCloseable {
        McpClientWrapper client() {
            return entry.client;
        }

        public void close() {
            entry.release();
        }
    }

    private final ConcurrentHashMap<String, CompletableFuture<ManagedToolExecutor.Outcome>> calls =
            new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128));

    public ManagedMcpExecutors(
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto) {
        this(store, results, mapper, crypto, ManagedMcpExecutors::connect, 1024);
    }

    public ManagedMcpExecutors(
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto,
            int capacity) {
        this(store, results, mapper, crypto, ManagedMcpExecutors::connect, capacity);
    }

    ManagedMcpExecutors(
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto,
            BiFunction<String, McpServerConfig, McpClientWrapper> factory) {
        this(store, results, mapper, crypto, factory, 1024);
    }

    ManagedMcpExecutors(
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto,
            BiFunction<String, McpServerConfig, McpClientWrapper> factory,
            int capacity) {
        if (capacity < 1 || capacity > 4096)
            throw new IllegalArgumentException("MCP cache capacity must be 1..4096");
        this.clientCapacity = new Semaphore(capacity);
        this.store = store;
        this.results = results;
        this.mapper = mapper;
        this.crypto = crypto;
        this.factory = factory;
    }

    private ManagedToolTarget target(ManagedScope scope, String task) {
        try {
            return mapper.readValue(
                    crypto.decrypt(store.configuration(scope, task)), ManagedToolTarget.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid sealed MCP target", invalid);
        }
    }

    public ManagedToolExecutor executor(ManagedScope scope, String id) {
        if (ManagedMcpBatch.OPERATION.equals(store.tool(scope, id).orElseThrow().name()))
            return batchExecutor();
        return new ManagedToolExecutor() {
            public boolean idempotentStart() {
                var task = store.tool(scope, id).orElseThrow();
                return task.name().equals("mcp.describe") || recovery(task) != null;
            }

            public String start(ManagedScope owner, ToolTask task) {
                String key = owner.messageGroup() + ":" + task.executionId();
                var promise = new CompletableFuture<Outcome>();
                if (calls.putIfAbsent(key, promise) != null) return task.executionId();
                try {
                    workers.execute(
                            () -> {
                                try {
                                    perform(owner, task)
                                            .whenComplete(
                                                    (outcome, failure) -> {
                                                        if (failure != null) {
                                                            promise.complete(
                                                                    new Outcome(
                                                                            State.UNKNOWN, null));
                                                            ManagedRuntimeLog.tool(
                                                                    log,
                                                                    "hands.mcp.response.unknown",
                                                                    owner,
                                                                    task,
                                                                    "failure_type",
                                                                    failure.getClass()
                                                                            .getSimpleName());
                                                            return;
                                                        }
                                                        // Complete only the local promise on the
                                                        // transport thread.
                                                        // JDBC capture always runs on the bounded
                                                        // blocking pool.
                                                        promise.complete(outcome);
                                                        try {
                                                            workers.execute(
                                                                    () ->
                                                                            capture(
                                                                                    owner, task,
                                                                                    key, promise,
                                                                                    outcome));
                                                        } catch (
                                                                RejectedExecutionException
                                                                        stopped) {
                                                            // The execution owner can still persist
                                                            // the completed promise.
                                                            log.warn(
                                                                    "Confirmed MCP outcome awaits"
                                                                        + " owner capture: task={}",
                                                                    task.id());
                                                        }
                                                    });
                                } catch (Exception unknown) {
                                    promise.complete(new Outcome(State.UNKNOWN, null));
                                }
                            });
                } catch (RejectedExecutionException full) {
                    calls.remove(key, promise);
                    store.unstarted(owner, task);
                    throw new ManagedDeliverySuspended(Duration.ofMillis(500));
                }
                return task.executionId();
            }

            public Outcome query(ManagedScope owner, ToolTask task) {
                var result = store.stagedToolResult(owner, task.id());
                if (result.isPresent()) {
                    calls.remove(owner.messageGroup() + ":" + task.executionId());
                    return new Outcome(
                            State.valueOf(result.get().status().toUpperCase(Locale.ROOT)),
                            result.get().result());
                }
                if (task.name().equals("mcp.describe") && store.cancelRequested(owner, task))
                    return new Outcome(
                            State.CANCELLED,
                            ManagedToolResult.cancelledPayload(task.name(), task.arguments()));
                var promise = calls.get(owner.messageGroup() + ":" + task.executionId());
                var outcome =
                        promise == null
                                ? new Outcome(State.UNKNOWN, null)
                                : promise.getNow(new Outcome(State.RUNNING, null));
                if (task.name().equals("mcp.call")
                        && outcome.state() == State.UNKNOWN
                        && recovery(task) != null) {
                    if (promise != null)
                        calls.remove(owner.messageGroup() + ":" + task.executionId(), promise);
                    outcome = recover(owner, task);
                }
                if (task.name().equals("mcp.describe") && outcome.state() == State.UNKNOWN) {
                    if (promise != null)
                        calls.remove(owner.messageGroup() + ":" + task.executionId(), promise);
                    return new Outcome(State.NOT_FOUND, null);
                }
                if (outcome.terminal()) {
                    results.capture(confirmed(owner, task, outcome));
                    if (promise != null)
                        calls.remove(owner.messageGroup() + ":" + task.executionId(), promise);
                } else if (outcome.state() == State.UNKNOWN && promise != null)
                    calls.remove(owner.messageGroup() + ":" + task.executionId(), promise);
                return outcome;
            }

            public Outcome cancel(ManagedScope owner, ToolTask task) {
                // Closing a transport is not proof that the external side effect has stopped.
                return query(owner, task);
            }
        };
    }

    private ManagedToolExecutor batchExecutor() {
        return new ManagedToolExecutor() {
            private final Set<String> observed = new HashSet<>();
            private final Set<String> queried = new HashSet<>();

            public boolean idempotentStart() {
                return true;
            }

            public String start(ManagedScope scope, ToolTask parent) {
                store.batchCalls(scope, parent);
                return parent.executionId();
            }

            public Outcome query(ManagedScope scope, ToolTask parent) {
                var completed = new ArrayList<ManagedMcpBatch.CallResult>();
                boolean cancelled = false;
                for (var original : store.batchCalls(scope, parent)) {
                    if (original.result() != null) {
                        if (observed.add(original.id()))
                            ManagedRuntimeLog.tool(
                                    log,
                                    Objects.equals(original.workerToken(), parent.workerToken())
                                            ? "hands.mcp.batch.call.completed"
                                            : "hands.mcp.batch.call.result.reused",
                                    scope,
                                    original,
                                    "batch_id",
                                    parent.id(),
                                    "status",
                                    original.status(),
                                    "mcp_execution_id",
                                    externalExecutionId(scope, original));
                        completed.add(
                                new ManagedMcpBatch.CallResult(
                                        original.toolCallId(),
                                        original.status(),
                                        original.result()));
                        cancelled |= "cancelled".equals(original.status());
                        continue;
                    }
                    var claim = store.claimBatchCall(scope, parent, original);
                    var task = claim.task();
                    if (!claim.firstLaunch()
                            && !Objects.equals(original.workerToken(), parent.workerToken())
                            && queried.add(task.id()))
                        ManagedRuntimeLog.tool(
                                log,
                                "hands.mcp.batch.call.querying_existing",
                                scope,
                                task,
                                "batch_id",
                                parent.id(),
                                "mcp_execution_id",
                                externalExecutionId(scope, task));
                    boolean stopping = store.cancelRequested(scope, parent);
                    var childExecutor = executor(scope, task.id());
                    Outcome outcome;
                    if (claim.firstLaunch() && stopping) {
                        outcome =
                                new Outcome(
                                        State.CANCELLED,
                                        ManagedToolResult.cancelledPayload(
                                                "mcp.call", task.arguments()));
                    } else {
                        if (claim.firstLaunch()) {
                            String handle = childExecutor.start(scope, task);
                            task =
                                    store.recordToolHandle(
                                            scope, task.id(), parent.workerToken(), handle);
                            ManagedRuntimeLog.tool(
                                    log,
                                    "hands.mcp.batch.call.started",
                                    scope,
                                    task,
                                    "batch_id",
                                    parent.id());
                        }
                        outcome =
                                stopping
                                        ? childExecutor.cancel(scope, task)
                                        : childExecutor.query(scope, task);
                        if (outcome.state() == State.NOT_FOUND
                                && childExecutor.idempotentStart()
                                && !stopping) {
                            childExecutor.start(scope, task);
                            return new Outcome(State.RUNNING, null);
                        }
                    }
                    if (!outcome.terminal())
                        return new Outcome(
                                outcome.state() == State.NOT_FOUND
                                        ? State.UNKNOWN
                                        : outcome.state(),
                                null);
                    var value =
                            new ManagedToolResult(
                                    scope,
                                    task.id(),
                                    task.executionId(),
                                    outcome.state().name().toLowerCase(Locale.ROOT),
                                    outcome.result());
                    results.capture(value);
                    observed.add(task.id());
                    completed.add(
                            new ManagedMcpBatch.CallResult(
                                    task.toolCallId(), value.status(), value.result()));
                    cancelled |= "cancelled".equals(value.status());
                    ManagedRuntimeLog.tool(
                            log,
                            "hands.mcp.batch.call.completed",
                            scope,
                            task,
                            "batch_id",
                            parent.id(),
                            "status",
                            value.status());
                }
                try {
                    return new Outcome(
                            cancelled ? State.CANCELLED : State.SUCCEEDED,
                            mapper.writeValueAsString(new ManagedMcpBatch.Result(completed)));
                } catch (Exception invalid) {
                    throw new IllegalStateException("Cannot encode MCP batch results", invalid);
                }
            }

            public Outcome cancel(ManagedScope scope, ToolTask parent) {
                return query(scope, parent);
            }
        };
    }

    private void capture(
            ManagedScope scope,
            ToolTask task,
            String key,
            CompletableFuture<ManagedToolExecutor.Outcome> promise,
            ManagedToolExecutor.Outcome outcome) {
        try {
            results.capture(confirmed(scope, task, outcome));
            calls.remove(key, promise);
        } catch (RuntimeException unavailable) {
            log.warn("Confirmed MCP outcome awaits database capture: task={}", task.id());
        }
    }

    private CompletionStage<ManagedToolExecutor.Outcome> perform(ManagedScope scope, ToolTask task)
            throws Exception {
        var target = target(scope, task.id());
        if (target.tools() == null || target.tools().getMcpServers() == null)
            throw new IllegalArgumentException("No admitted MCP configuration");
        if ("mcp.describe".equals(task.name())) {
            var tools = new ArrayList<ManagedMcpCatalog.Tool>();
            var unavailable = new ArrayList<String>();
            for (var entry : target.tools().getMcpServers().entrySet()) {
                try {
                    validateTransport(target, entry.getValue());
                    try (var connection = connection(scope, entry.getKey(), entry.getValue())) {
                        var listed = connection.client().listTools().block();
                        var queryTools = new HashSet<String>();
                        for (var tool : listed) {
                            var recovery = advertisedRecovery(tool);
                            if (recovery != null) {
                                var query =
                                        listed.stream()
                                                .filter(t -> recovery.queryTool().equals(t.name()))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalArgumentException(
                                                                        "MCP recovery lookup tool"
                                                                                + " is missing"));
                                if (query.annotations() == null
                                        || !Boolean.TRUE.equals(query.annotations().readOnlyHint()))
                                    throw new IllegalArgumentException(
                                            "MCP recovery lookup must be read-only");
                                queryTools.add(query.name());
                            }
                        }
                        for (var tool : listed)
                            if (!queryTools.contains(tool.name())
                                    && enabled(entry.getValue(), tool.name()))
                                tools.add(descriptor(entry.getKey(), entry.getValue(), tool));
                    }
                } catch (RuntimeException failure) {
                    invalidate(scope, entry.getKey(), entry.getValue());
                    if (entry.getValue().isRequired()) throw failure;
                    unavailable.add(entry.getKey());
                }
            }
            return CompletableFuture.completedFuture(
                    new ManagedToolExecutor.Outcome(
                            ManagedToolExecutor.State.SUCCEEDED,
                            mapper.writeValueAsString(
                                    new ManagedMcpCatalog.Description(tools, unavailable))));
        }
        var arguments = mapper.readTree(task.arguments());
        String server = arguments.path("server").asText(), name = arguments.path("tool").asText();
        var configuration = target.tools().getMcpServers().get(server);
        if (configuration == null || !enabled(configuration, name))
            return CompletableFuture.completedFuture(rejected("MCP tool is not admitted"));
        Connection connection;
        try {
            validateTransport(target, configuration);
            connection = connection(scope, server, configuration);
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(
                    rejected("MCP connection could not be initialized before invocation"));
        }
        try {
            return invoke(scope, task, arguments, configuration, connection)
                    .whenComplete(
                            (result, failure) -> {
                                connection.close();
                                if (failure != null) {
                                    try {
                                        // Client shutdown may block; never retire on the HTTP
                                        // response thread. Idle eviction repairs a rejected hint.
                                        workers.execute(
                                                () -> invalidate(scope, server, configuration));
                                    } catch (RejectedExecutionException stopped) {
                                        log.debug(
                                                "MCP connection retirement deferred: server={}",
                                                server);
                                    }
                                }
                            });
        } catch (Exception | Error failure) {
            connection.close();
            invalidate(scope, server, configuration);
            throw failure;
        }
    }

    private CompletionStage<ManagedToolExecutor.Outcome> invoke(
            ManagedScope scope,
            ToolTask task,
            JsonNode arguments,
            McpServerConfig configuration,
            Connection connection)
            throws Exception {
        String server = arguments.path("server").asText(), name = arguments.path("tool").asText();
        var client = connection.client();
        var tool = client.getCachedTool(name);
        if (tool == null)
            tool =
                    client.listTools().block().stream()
                            .filter(value -> name.equals(value.name()))
                            .findFirst()
                            .orElse(null);
        if (tool == null)
            return CompletableFuture.completedFuture(rejected("MCP tool definition is missing"));
        String expected = arguments.path("descriptor_hash").asText();
        if (!expected.equals(
                ManagedMcpCatalog.descriptorHash(descriptor(server, configuration, tool), mapper)))
            return CompletableFuture.completedFuture(
                    rejected("MCP tool definition changed since admission"));
        @SuppressWarnings("unchecked")
        Map<String, Object> input = mapper.convertValue(arguments.path("arguments"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = mapper.convertValue(arguments.path("meta"), Map.class);
        var recovery = recovery(task);
        if (!Objects.equals(recovery, advertisedRecovery(tool)))
            return CompletableFuture.completedFuture(
                    rejected("MCP execution recovery contract differs from admission"));
        if (recovery != null) {
            if (!client.isPropagateMeta())
                return CompletableFuture.completedFuture(
                        rejected("MCP execution recovery requires request metadata propagation"));
            meta = meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(meta);
            meta.put(
                    ManagedMcpCatalog.RECOVERY_META,
                    Map.of("version", 1, "execution_id", externalExecutionId(scope, task)));
        }
        return client.callTool(name, input, client.isPropagateMeta() ? meta : null)
                .switchIfEmpty(
                        Mono.error(new ManagedTransportException("MCP outcome is unconfirmed")))
                .flatMap(
                        result ->
                                Mono.fromCallable(
                                        () ->
                                                new ManagedToolExecutor.Outcome(
                                                        Boolean.TRUE.equals(result.isError())
                                                                ? ManagedToolExecutor.State.FAILED
                                                                : ManagedToolExecutor.State
                                                                        .SUCCEEDED,
                                                        resultJson(result))))
                .toFuture();
    }

    private Recovery recovery(ToolTask task) {
        try {
            var value = mapper.readTree(task.arguments()).get("recovery");
            return value == null || value.isNull()
                    ? null
                    : mapper.treeToValue(value, Recovery.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid admitted MCP recovery contract", invalid);
        }
    }

    private static String externalExecutionId(ManagedScope scope, ToolTask task) {
        return "mcp_exec_" + ManagedScope.fingerprint(scope.messageGroup(), task.executionId());
    }

    private Recovery advertisedRecovery(McpSchema.Tool tool) {
        return ManagedMcpDiscovery.recovery(tool);
    }

    private String resultJson(McpSchema.CallToolResult result) throws Exception {
        // PostgreSQL JSONB and distinct server replicas may return object keys in a different
        // order.
        return mapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(result);
    }

    private ManagedToolExecutor.Outcome recover(ManagedScope scope, ToolTask task) {
        try {
            var target = target(scope, task.id());
            var arguments = mapper.readTree(task.arguments());
            String server = arguments.path("server").asText();
            var configuration = target.tools().getMcpServers().get(server);
            if (configuration == null)
                return new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.UNKNOWN, null);
            validateTransport(target, configuration);
            try (var connection = connection(scope, server, configuration)) {
                String execution = externalExecutionId(scope, task);
                var queryTool =
                        connection.client().listTools().block().stream()
                                .filter(tool -> recovery(task).queryTool().equals(tool.name()))
                                .findFirst()
                                .orElse(null);
                if (queryTool == null
                        || queryTool.annotations() == null
                        || !Boolean.TRUE.equals(queryTool.annotations().readOnlyHint()))
                    return new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.UNKNOWN, null);
                var result =
                        connection
                                .client()
                                .callTool(
                                        recovery(task).queryTool(),
                                        Map.of("execution_id", execution),
                                        null)
                                .block();
                if (result == null
                        || Boolean.TRUE.equals(result.isError())
                        || result.structuredContent() == null)
                    return new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.UNKNOWN, null);
                var response = mapper.valueToTree(result.structuredContent());
                if (response.path("version").asInt() != 1
                        || !execution.equals(response.path("execution_id").asText()))
                    return new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.UNKNOWN, null);
                var state =
                        ManagedToolExecutor.State.valueOf(
                                response.path("state").asText().toUpperCase(Locale.ROOT));
                ManagedRuntimeLog.tool(
                        log,
                        "hands.mcp.execution.queried",
                        scope,
                        task,
                        "external_state",
                        state.name());
                if (state == ManagedToolExecutor.State.SUCCEEDED
                        || state == ManagedToolExecutor.State.FAILED
                        || state == ManagedToolExecutor.State.CANCELLED) {
                    var original =
                            mapper.treeToValue(
                                    response.path("result"), McpSchema.CallToolResult.class);
                    if (original == null
                            || original.content() == null
                            || (state == ManagedToolExecutor.State.SUCCEEDED
                                    && Boolean.TRUE.equals(original.isError()))
                            || (state == ManagedToolExecutor.State.FAILED
                                    && !Boolean.TRUE.equals(original.isError())))
                        return new ManagedToolExecutor.Outcome(
                                ManagedToolExecutor.State.UNKNOWN, null);
                    return new ManagedToolExecutor.Outcome(state, resultJson(original));
                }
                return new ManagedToolExecutor.Outcome(state, null);
            }
        } catch (Exception unavailable) {
            // A failed/malformed lookup is never evidence that the original operation did not run.
            return new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.UNKNOWN, null);
        }
    }

    private ManagedToolResult confirmed(
            ManagedScope scope, ToolTask task, ManagedToolExecutor.Outcome outcome) {
        return new ManagedToolResult(
                scope,
                task.id(),
                task.executionId(),
                outcome.state().name().toLowerCase(Locale.ROOT),
                outcome.result());
    }

    private ManagedToolExecutor.Outcome rejected(String reason) throws Exception {
        return new ManagedToolExecutor.Outcome(
                ManagedToolExecutor.State.FAILED,
                mapper.writeValueAsString(
                        Map.of(
                                "content",
                                List.of(Map.of("type", "text", "text", reason)),
                                "isError",
                                true)));
    }

    private ManagedMcpCatalog.Tool descriptor(
            String server, McpServerConfig config, McpSchema.Tool tool) {
        return ManagedMcpDiscovery.descriptor(server, config, tool);
    }

    private static boolean enabled(McpServerConfig config, String name) {
        return ManagedMcpDiscovery.enabled(config, name);
    }

    private Connection connection(ManagedScope scope, String name, McpServerConfig config) {
        try {
            String key =
                    scope.messageGroup()
                            + ":"
                            + name
                            + ":"
                            + ManagedScope.fingerprint(mapper.writeValueAsString(config));
            while (true) {
                var entry = clients.get(key);
                if (entry == null) {
                    var opening = new CompletableFuture<ConnectionEntry>();
                    var prior = openings.putIfAbsent(key, opening);
                    if (prior != null) {
                        prior.join();
                        continue;
                    }
                    boolean reserved = false;
                    try {
                        if (!clientCapacity.tryAcquire()) {
                            clients.entrySet().stream()
                                    .sorted(
                                            Comparator.comparingLong(
                                                    value -> value.getValue().lastUsed))
                                    .filter(value -> value.getValue().retire())
                                    .findFirst()
                                    .ifPresent(
                                            value -> {
                                                clients.remove(value.getKey(), value.getValue());
                                                try {
                                                    value.getValue().client.close();
                                                } finally {
                                                    clientCapacity.release();
                                                }
                                            });
                            if (!clientCapacity.tryAcquire())
                                throw new ManagedTransportException("MCP connection cache is busy");
                        }
                        reserved = true;
                        entry = new ConnectionEntry(factory.apply(name, config));
                        clients.put(key, entry);
                        opening.complete(entry);
                    } catch (RuntimeException | Error failure) {
                        if (reserved) clientCapacity.release();
                        opening.completeExceptionally(failure);
                        throw failure;
                    } finally {
                        openings.remove(key, opening);
                    }
                }
                if (entry.acquire()) return new Connection(entry);
                clients.remove(key, entry);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid MCP connection", invalid);
        }
    }

    private void invalidate(ManagedScope scope, String name, McpServerConfig configuration) {
        try {
            String key =
                    scope.messageGroup()
                            + ":"
                            + name
                            + ":"
                            + ManagedScope.fingerprint(mapper.writeValueAsString(configuration));
            var entry = clients.get(key);
            if (entry != null && entry.retire()) {
                clients.remove(key, entry);
                try {
                    entry.client.close();
                } finally {
                    clientCapacity.release();
                }
            }
        } catch (Exception closeFailure) {
            log.debug("MCP connection retirement pending: server={}", name);
        }
    }

    private void validateTransport(ManagedToolTarget target, McpServerConfig configuration) {
        ManagedMcpDiscovery.validateTransport(target.resolved(), configuration);
    }

    private static McpClientWrapper connect(String name, McpServerConfig config) {
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
        var client = builder.buildAsync().block();
        try {
            client.initialize().block();
            return client;
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
        clients.values().forEach(entry -> entry.client.close());
        clients.clear();
    }
}
