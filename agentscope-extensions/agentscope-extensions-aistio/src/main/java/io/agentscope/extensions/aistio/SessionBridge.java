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
package io.agentscope.extensions.aistio;

import io.agentscope.aistio.proto.SessionEventMsg;
import io.agentscope.aistio.proto.SessionSnapshot;
import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.ContextTracker;
import io.agentscope.extensions.aistio.model.Inventory;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import io.agentscope.extensions.aistio.transport.ContractHttpServer;
import io.agentscope.extensions.aistio.transport.ContractProvider;
import io.agentscope.extensions.aistio.transport.GrpcTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * The reporting engine that sits between a framework adapter and the aistio control plane.
 *
 * <p>It owns everything the adapter should not care about: sequence numbering, the Level-2 event
 * buffer, incremental context tracking, Level-1 aggregation, debounced Level-4 pushes, inventory,
 * command dispatch from both channels, and the in-process contract server.
 *
 * <p><b>Bypass principle:</b> every reporting path swallows its own failures. Nothing here may
 * propagate into the agent's conversation path.
 */
public final class SessionBridge implements ContractProvider, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SessionBridge.class.getName());

    public static final String SDK_VERSION = "0.1.0";

    /** Contract level 3: discovery, sessions, and commands. Finer gating is by capability. */
    public static final int CONTRACT_LEVEL = 3;

    private static final long LEVEL1_INTERVAL_MS = 10_000L;
    private static final long EVENT_FLUSH_INTERVAL_MS = 5_000L;
    private static final int EVENT_BATCH_SIZE = 20;
    private static final int EVENT_BUFFER_MAX = 1_000;
    private static final long CONTEXT_PUSH_COOLDOWN_MS = 30_000L;
    private static final long INVENTORY_INTERVAL_MS = 30_000L;
    private static final Duration ADAPTER_CALL_TIMEOUT = Duration.ofSeconds(10);

    private final AistioConfig config;
    private final Object lock = new Object();

    private final Map<String, ContextTracker> trackers = new ConcurrentHashMap<>();
    private final Map<String, String> phases = new ConcurrentHashMap<>();
    private final Map<String, Integer> sequences = new ConcurrentHashMap<>();
    private final Map<String, Long> lastContextPush = new ConcurrentHashMap<>();
    private final List<SessionEvent> eventBuffer = new ArrayList<>();

    private FrameworkAdapter adapter;
    private GrpcTransport grpc;
    private ContractHttpServer http;
    private ScheduledExecutorService scheduler;
    private volatile boolean started;

    public SessionBridge(AistioConfig config) {
        this.config = config;
    }

    // ─── adapter mounting ───

    /** Mounts {@code adapter} onto {@code target}; must be called before {@link #start()}. */
    public SessionBridge attach(Object target, FrameworkAdapter adapter) {
        if (!adapter.canHandle(target)) {
            throw new IllegalArgumentException(
                    adapter.frameworkName() + " adapter cannot handle " + target.getClass());
        }
        this.adapter = adapter;
        adapter.attach(target, this::onEvent);
        adapter.onBridgeAttached(this);
        return this;
    }

    public FrameworkAdapter getAdapter() {
        return adapter;
    }

    public AistioConfig getConfig() {
        return config;
    }

    /** Actual contract HTTP port, which matters when the configured port was 0. */
    public int getContractPort() {
        return http != null ? http.getPort() : config.contractHttpPort();
    }

    // ─── lifecycle ───

    public synchronized SessionBridge start() {
        if (started) {
            return this;
        }
        started = true;

        if (config.startGrpc()) {
            grpc =
                    new GrpcTransport(
                            config.controlPlane(),
                            config.agentName(),
                            config.namespace(),
                            config.instanceId(),
                            frameworkName(),
                            SDK_VERSION,
                            capabilities(),
                            config.sessionAffinity());
            grpc.setSessionCommandHandler(
                    (sessionId, command, params) -> dispatchCommand(sessionId, command, params));
            grpc.start();
        }

        if (config.startHttp()) {
            try {
                http =
                        new ContractHttpServer(
                                config.contractHttpHost(), config.contractHttpPort(), this);
                http.start();
            } catch (IOException e) {
                throw new UncheckedIOException("aistio: contract HTTP server failed to bind", e);
            }
        }

        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "aistio-bridge");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                guarded(this::flushEvents),
                EVENT_FLUSH_INTERVAL_MS,
                EVENT_FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(
                guarded(this::reportLevel1),
                LEVEL1_INTERVAL_MS,
                LEVEL1_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        // Inventory goes out immediately once connected, then refreshes slowly.
        scheduler.scheduleWithFixedDelay(
                guarded(this::reportInventory), 0L, INVENTORY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (grpc != null) {
            grpc.close();
            grpc = null;
        }
        if (http != null) {
            http.close();
            http = null;
        }
        if (adapter != null) {
            try {
                adapter.detach();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "aistio: adapter detach failed", e);
            }
        }
    }

    // ─── capabilities ───

    public Set<String> capabilities() {
        Set<String> caps = new TreeSet<>(Set.of("session-reporting", "context-reporting"));
        if (config.enableEvents()) {
            caps.add("event-reporting");
        }
        if (adapter != null) {
            caps.addAll(adapter.capabilities());
        }
        return caps;
    }

    // ─── event ingest ───

    /** Adapter callback. Assigns a sequence number, updates the view, and triggers reports. */
    public void onEvent(SessionEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        boolean flushNeeded;
        boolean hashChanged;
        boolean compaction;
        synchronized (lock) {
            int seq = sequences.merge(sessionId, 1, Integer::sum);
            event.setSeq(seq);

            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            hashChanged = tracker.onEvent(event);

            if (SessionEvent.SESSION_START.equals(event.getEventType())) {
                phases.put(sessionId, "running");
            } else if (SessionEvent.SESSION_END.equals(event.getEventType())) {
                phases.put(sessionId, "completed");
            }

            if (config.enableEvents()) {
                eventBuffer.add(event);
                int overflow = eventBuffer.size() - EVENT_BUFFER_MAX;
                if (overflow > 0) {
                    // Bounded queue: drop the oldest Level-2 events so a long disconnect
                    // cannot grow the agent's heap without limit.
                    eventBuffer.subList(0, overflow).clear();
                }
                flushNeeded = eventBuffer.size() >= EVENT_BATCH_SIZE;
            } else {
                flushNeeded = false;
            }
            compaction = SessionEvent.COMPACTION.equals(event.getEventType());
        }

        if (flushNeeded) {
            flushEvents();
        }
        if (compaction) {
            pushContext(sessionId, true);
        } else if (hashChanged) {
            pushContext(sessionId, false);
        }
    }

    /** Lets the adapter seed the tracker with data the event stream does not carry. */
    public void describeSession(
            String sessionId,
            String systemPrompt,
            List<ContextSnapshot.ToolInfo> tools,
            int maxTokens) {
        synchronized (lock) {
            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            if (systemPrompt != null) {
                tracker.setSystemPrompt(systemPrompt);
            }
            if (tools != null) {
                tracker.setTools(tools);
            }
            if (maxTokens > 0) {
                tracker.setMaxTokens(maxTokens);
            }
        }
    }

    // ─── Level 2 ───

    private void flushEvents() {
        if (grpc == null) {
            return;
        }
        List<SessionEvent> batch;
        synchronized (lock) {
            if (eventBuffer.isEmpty()) {
                return;
            }
            batch = List.copyOf(eventBuffer);
            eventBuffer.clear();
        }
        List<SessionEventMsg> payload = new ArrayList<>(batch.size());
        for (SessionEvent e : batch) {
            payload.add(e.toProto());
        }
        grpc.reportEvents(payload);
    }

    // ─── Level 4 ───

    private void pushContext(String sessionId, boolean force) {
        if (grpc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastContextPush.get(sessionId);
        if (!force && last != null && now - last < CONTEXT_PUSH_COOLDOWN_MS) {
            return;
        }
        ContextTracker tracker = trackers.get(sessionId);
        if (tracker == null) {
            return;
        }
        lastContextPush.put(sessionId, now);
        ContextSnapshot snapshot;
        synchronized (lock) {
            snapshot = tracker.snapshot();
        }
        grpc.reportContext(snapshot.toProto());
    }

    // ─── Level 1 ───

    private void reportLevel1() {
        if (grpc == null) {
            return;
        }
        grpc.reportSessions(buildLevel1());
    }

    private List<SessionSnapshot> buildLevel1() {
        String framework = frameworkName();
        String version = frameworkVersion();
        List<SessionSnapshot> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, ContextTracker> entry : trackers.entrySet()) {
                ContextTracker tracker = entry.getValue();
                int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
                double pressure =
                        tracker.getMaxTokens() > 0
                                ? (double) totalTokens / tracker.getMaxTokens()
                                : 0.0;
                out.add(
                        SessionSnapshot.newBuilder()
                                .setSessionId(entry.getKey())
                                .setPhase(phases.getOrDefault(entry.getKey(), "running"))
                                .setMessageCount(tracker.getMessageCount())
                                .setPromptTokens(tracker.getTokensIn())
                                .setCompletionTokens(tracker.getTokensOut())
                                .setContextPressure(pressure)
                                .setFramework(framework)
                                .setFrameworkVersion(version)
                                .setContextHash(tracker.getContextHash())
                                .setIsCompacted(tracker.isCompacted())
                                .setEffectiveMessageCount(tracker.getEffectiveMessageCount())
                                .build());
            }
        }
        return out;
    }

    // ─── inventory ───

    private void reportInventory() {
        if (grpc == null || adapter == null) {
            return;
        }
        int active = (int) phases.values().stream().filter("running"::equals).count();
        List<Inventory.SubagentInfo> subagents = awaitOrDefault(adapter.listSubagents(), List.of());
        List<Inventory.WorkspaceInfo> workspaces =
                awaitOrDefault(adapter.listWorkspaces(), List.of());
        Inventory inventory =
                new Inventory(
                        subagents, workspaces, new Inventory.InstanceHealth(true, "", active));
        grpc.reportInventory(inventory.toProto());
    }

    // ─── command dispatch (ASDP push and HTTP both land here) ───

    private void dispatchCommand(String sessionId, String command, byte[] params) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.handleCommand(sessionId, command, params).block(ADAPTER_CALL_TIMEOUT);
    }

    // ─── ContractProvider ───

    @Override
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", config.agentName());
        out.put("runtime", frameworkName());
        out.put("version", frameworkVersion());
        out.put("sdkVersion", SDK_VERSION);
        out.put("contractLevel", CONTRACT_LEVEL);
        out.put("capabilities", List.copyOf(capabilities()));
        out.put("port", getContractPort());
        if (!config.sessionAffinity().isEmpty()) {
            out.put("sessionAffinity", config.sessionAffinity());
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> sessions() {
        String framework = frameworkName();
        String version = frameworkVersion();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, ContextTracker> entry : trackers.entrySet()) {
                ContextTracker tracker = entry.getValue();
                int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("id", entry.getKey());
                session.put("phase", phases.getOrDefault(entry.getKey(), "running"));
                session.put("messageCount", tracker.getMessageCount());
                session.put(
                        "tokenUsage",
                        Map.of(
                                "promptTokens",
                                tracker.getTokensIn(),
                                "completionTokens",
                                tracker.getTokensOut()));
                session.put(
                        "contextPressure",
                        tracker.getMaxTokens() > 0
                                ? (double) totalTokens / tracker.getMaxTokens()
                                : 0.0);
                session.put("framework", framework);
                if (!version.isEmpty()) {
                    session.put("frameworkVersion", version);
                }
                session.put("contextHash", tracker.getContextHash());
                if (tracker.isCompacted()) {
                    session.put("isCompacted", true);
                }
                session.put("effectiveMessageCount", tracker.getEffectiveMessageCount());
                out.add(session);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> sessionState(String sessionId) {
        ContextTracker tracker = requireTracker(sessionId);
        int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
        int maxTokens = tracker.getMaxTokens();
        Map<String, Object> pressure = new LinkedHashMap<>();
        pressure.put("usedTokens", totalTokens);
        pressure.put("maxTokens", maxTokens);
        pressure.put("ratio", maxTokens > 0 ? (double) totalTokens / maxTokens : 0.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("contextPressure", pressure);
        return out;
    }

    @Override
    public Map<String, Object> context(String sessionId) {
        // The adapter reads the framework's live state, which is authoritative; the tracker's
        // event-derived view is the fallback when that read fails.
        ContextSnapshot snapshot = null;
        if (adapter != null) {
            try {
                snapshot = adapter.extractContext(sessionId).block(ADAPTER_CALL_TIMEOUT);
            } catch (UnsupportedOperationException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "aistio: extractContext failed, falling back to tracker", e);
            }
        }
        if (snapshot == null) {
            snapshot = requireTracker(sessionId).snapshot();
        } else if (snapshot.getMessages().isEmpty() && !trackers.containsKey(sessionId)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        snapshot.refreshHash();
        return snapshot.toJsonMap();
    }

    @Override
    public Map<String, Object> messages(String sessionId, int offset, int limit) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        MessagePage page =
                adapter.listMessages(sessionId, offset, limit).block(ADAPTER_CALL_TIMEOUT);
        if (page == null || (page.total() == 0 && !trackers.containsKey(sessionId))) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return page.toJsonMap();
    }

    @Override
    public List<Map<String, Object>> subagents() {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Inventory.SubagentInfo> items = adapter.listSubagents().block(ADAPTER_CALL_TIMEOUT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inventory.SubagentInfo item :
                items == null ? List.<Inventory.SubagentInfo>of() : items) {
            out.add(item.toJsonMap());
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> workspaces() {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Inventory.WorkspaceInfo> items = adapter.listWorkspaces().block(ADAPTER_CALL_TIMEOUT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inventory.WorkspaceInfo item :
                items == null ? List.<Inventory.WorkspaceInfo>of() : items) {
            out.add(item.toJsonMap());
        }
        return out;
    }

    @Override
    public void compress(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_COMPRESS, null);
    }

    @Override
    public void terminate(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_TERMINATE, null);
    }

    // ─── helpers ───

    private ContextTracker requireTracker(String sessionId) {
        ContextTracker tracker = trackers.get(sessionId);
        if (tracker == null) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return tracker;
    }

    private String frameworkName() {
        return adapter == null ? "" : adapter.frameworkName();
    }

    private String frameworkVersion() {
        if (adapter == null) {
            return "";
        }
        try {
            String version = adapter.frameworkVersion();
            return version == null ? "" : version;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static <T> T awaitOrDefault(Mono<T> mono, T fallback) {
        try {
            T value = mono.block(ADAPTER_CALL_TIMEOUT);
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // A reporting failure must never kill the scheduler or reach the agent.
                LOG.log(Level.FINE, "aistio: scheduled report failed", e);
            }
        };
    }
}
