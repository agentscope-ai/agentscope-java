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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.agentscope.extensions.aistio.transport.HttpSelfRegistration;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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

    /**
     * Phase vocabulary from the control-plane contract. Values outside it are counted into no
     * bucket at all, so the console would report zero active sessions.
     */
    private static final String PHASE_ACTIVE = "active";

    private static final String PHASE_IDLE = "idle";
    private static final String PHASE_COMPRESSING = "compressing";
    private static final String PHASE_ARCHIVED = "archived";
    private static final String PHASE_TERMINATED = "terminated";

    private static final long LEVEL1_INTERVAL_MS = 10_000L;
    private static final long EVENT_FLUSH_INTERVAL_MS = 5_000L;
    private static final int EVENT_BATCH_SIZE = 20;
    private static final int EVENT_BUFFER_MAX = 1_000;
    private static final long CONTEXT_PUSH_COOLDOWN_MS = 30_000L;
    private static final long INVENTORY_INTERVAL_MS = 30_000L;
    private static final Duration ADAPTER_CALL_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper TEAM_EVENT_MAPPER = new ObjectMapper();

    private final AistioConfig config;
    private final Object lock = new Object();

    private final Map<String, ContextTracker> trackers = new ConcurrentHashMap<>();
    private final Map<String, String> phases = new ConcurrentHashMap<>();

    /** Present only when busy is known (turn START/END or middleware mark). Never fake false. */
    private final Map<String, Boolean> busyFlags = new ConcurrentHashMap<>();

    /** Epoch millis of the first and most recent activity, reported as startedAt/lastActiveAt. */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    private final Map<String, Long> lastActiveAt = new ConcurrentHashMap<>();

    private final Map<String, Integer> sequences = new ConcurrentHashMap<>();
    private final Map<String, Long> lastContextPush = new ConcurrentHashMap<>();
    private final List<SessionEvent> eventBuffer = new ArrayList<>();

    private FrameworkAdapter adapter;
    private GrpcTransport grpc;
    private ContractHttpServer http;
    private HttpSelfRegistration httpRegister;
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
            grpc.setTeamEventHandler(this::onTeamEvent);
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

        if (config.startHttpRegister()) {
            String token = config.internalToken();
            if (token == null || token.isBlank()) {
                LOG.warning(
                        "aistio: HTTP self-register enabled but internalToken is blank; skipping"
                                + " (set BUILDER_INTERNAL_TOKEN / AistioConfig.internalToken)");
            } else {
                String baseUrl = resolvePublicBaseUrl();
                httpRegister =
                        new HttpSelfRegistration(
                                config.controlPlaneHttp(),
                                token,
                                config.agentName(),
                                config.namespace(),
                                config.instanceId(),
                                baseUrl,
                                frameworkName(),
                                frameworkName(),
                                CONTRACT_LEVEL,
                                List.copyOf(capabilities()),
                                15_000L);
                httpRegister.start();
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

    private String resolvePublicBaseUrl() {
        if (config.publicBaseUrl() != null && !config.publicBaseUrl().isBlank()) {
            String u = config.publicBaseUrl().trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
        int port = getContractPort();
        return "http://localhost:" + port;
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
        if (httpRegister != null) {
            httpRegister.close();
            httpRegister = null;
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

            touch(sessionId);

            if (SessionEvent.SESSION_START.equals(event.getEventType())) {
                phases.put(sessionId, PHASE_ACTIVE);
                busyFlags.put(sessionId, true);
            } else if (SessionEvent.SESSION_END.equals(event.getEventType())) {
                // A finished conversation is resumable, so it is idle rather than terminated;
                // terminated is reserved for sessions the runtime has actually dropped.
                phases.put(sessionId, PHASE_IDLE);
                busyFlags.put(sessionId, false);
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
        describeSession(sessionId, systemPrompt, tools, maxTokens, null);
    }

    /** Lets the adapter seed the tracker, including the model name when known. */
    public void describeSession(
            String sessionId,
            String systemPrompt,
            List<ContextSnapshot.ToolInfo> tools,
            int maxTokens,
            String model) {
        synchronized (lock) {
            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            phases.putIfAbsent(sessionId, PHASE_IDLE);
            if (systemPrompt != null) {
                tracker.setSystemPrompt(systemPrompt);
            }
            if (tools != null) {
                tracker.setTools(tools);
            }
            if (maxTokens > 0) {
                tracker.setMaxTokens(maxTokens);
            }
            if (model != null && !model.isEmpty()) {
                tracker.setModel(model);
            }
        }
    }

    /** Stores the last middleware-composed system prompt for a session. */
    public void setEffectiveSystemPrompt(String sessionId, String prompt) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            tracker.setEffectiveSystemPrompt(prompt);
        }
    }

    /** Last effective system prompt for the session, or empty. */
    public String effectiveSystemPrompt(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "";
        }
        synchronized (lock) {
            ContextTracker tracker = trackers.get(sessionId);
            return tracker == null ? "" : tracker.getEffectiveSystemPrompt();
        }
    }

    /**
     * Records whether a turn is in progress by driving {@code phase}: {@code active} while busy,
     * {@code idle} when the turn ends. {@code busy} on Level-1 responses is derived from phase.
     */
    public void setBusy(String sessionId, boolean busy) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        busyFlags.put(sessionId, busy);
        String current = phases.get(sessionId);
        if (PHASE_COMPRESSING.equals(current)
                || PHASE_ARCHIVED.equals(current)
                || PHASE_TERMINATED.equals(current)) {
            // Do not clobber in-flight compress / terminal states from turn hooks.
            touch(sessionId);
            return;
        }
        phases.put(sessionId, busy ? PHASE_ACTIVE : PHASE_IDLE);
        touch(sessionId);
    }

    /** Sets operational phase explicitly (e.g. compressing). */
    public void setPhase(String sessionId, String phase) {
        if (sessionId == null || sessionId.isEmpty() || phase == null || phase.isEmpty()) {
            return;
        }
        phases.put(sessionId, phase);
        boolean active = PHASE_ACTIVE.equals(phase);
        busyFlags.put(sessionId, active);
        touch(sessionId);
    }

    /** Updates context-window occupancy used for Level-1 {@code contextPressure}. */
    public void setContextUsedTokens(String sessionId, int usedTokens) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ContextTracker tracker = trackers.get(sessionId);
            if (tracker != null) {
                tracker.setContextUsedTokens(usedTokens);
            }
        }
    }

    private void touch(String sessionId) {
        long now = System.currentTimeMillis();
        startedAt.putIfAbsent(sessionId, now);
        lastActiveAt.put(sessionId, now);
    }

    private static String isoOrNull(Long epochMillis) {
        return epochMillis == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Omits unknown fields entirely; the contract reads a missing key as "not reported". */
    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
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
                int usedForPressure = tracker.getContextUsedTokens();
                double pressure =
                        tracker.getMaxTokens() > 0 && usedForPressure > 0
                                ? (double) usedForPressure / tracker.getMaxTokens()
                                : 0.0;
                out.add(
                        SessionSnapshot.newBuilder()
                                .setSessionId(entry.getKey())
                                .setPhase(phases.getOrDefault(entry.getKey(), PHASE_IDLE))
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
        int active = (int) phases.values().stream().filter(PHASE_ACTIVE::equals).count();
        List<Inventory.SubagentInfo> subagents = awaitOrDefault(adapter.listSubagents(), List.of());
        List<Inventory.WorkspaceInfo> workspaces =
                awaitOrDefault(adapter.listWorkspaces(), List.of());
        Inventory inventory =
                new Inventory(
                        subagents, workspaces, new Inventory.InstanceHealth(true, "", active));
        grpc.reportInventory(inventory.toProto());
    }

    // ─── team events (ASDP downstream) ───

    /**
     * Wakes the local teammate session addressed by a control-plane TeamEvent. The event names the
     * member; the payload may additionally carry the concrete session id.
     */
    private void onTeamEvent(
            String teamId, String eventType, String memberName, String taskId, byte[] payload) {
        LOG.log(
                Level.FINE,
                "aistio: downstream team event team={0} type={1} member={2} task={3}",
                new Object[] {teamId, eventType, memberName, taskId});
        String notice = readNotice(payload);
        TeamsMiddleware.wakeupTeamMember(teamId, memberName, notice);
        String sessionId = readSessionId(payload);
        if (!sessionId.isEmpty()) {
            TeamsMiddleware.wakeupSession(sessionId, notice);
        }
    }

    /**
     * Extracts the human-readable body of a team event so the woken turn starts with the content.
     * The control plane sends the message text as the raw payload; JSON payloads carry it under
     * {@code content}.
     */
    private static String readNotice(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            JsonNode root = TEAM_EVENT_MAPPER.readTree(payload);
            String content = root.path("content").asText("");
            return content.isEmpty() ? root.toString() : content;
        } catch (IOException e) {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private static String readSessionId(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            return TEAM_EVENT_MAPPER.readTree(payload).path("sessionId").asText("");
        } catch (IOException e) {
            LOG.log(Level.FINE, "aistio: team event payload is not JSON", e);
            return "";
        }
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
        if (adapter != null) {
            Map<String, Object> agentConfig = adapter.buildAgentConfig();
            if (agentConfig != null && !agentConfig.isEmpty()) {
                out.put("agentConfig", agentConfig);
            }
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
                String sessionId = entry.getKey();
                ContextTracker tracker = entry.getValue();
                int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
                int usedForPressure = tracker.getContextUsedTokens();
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("id", sessionId);
                session.put("phase", phases.getOrDefault(sessionId, PHASE_IDLE));
                Boolean busy = busyFlags.get(sessionId);
                if (busy == null) {
                    busy = PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE));
                }
                session.put("busy", busy);
                if (!tracker.getModel().isEmpty()) {
                    session.put("model", tracker.getModel());
                }
                putIfPresent(session, "startedAt", isoOrNull(startedAt.get(sessionId)));
                putIfPresent(session, "lastActiveAt", isoOrNull(lastActiveAt.get(sessionId)));
                session.put("messageCount", tracker.getMessageCount());
                Map<String, Object> tokenUsage = new LinkedHashMap<>();
                tokenUsage.put("promptTokens", tracker.getTokensIn());
                tokenUsage.put("completionTokens", tracker.getTokensOut());
                tokenUsage.put("totalTokens", totalTokens);
                if (tracker.getMaxTokens() > 0) {
                    tokenUsage.put("maxTokens", tracker.getMaxTokens());
                }
                session.put("tokenUsage", tokenUsage);
                session.put(
                        "contextPressure",
                        tracker.getMaxTokens() > 0 && usedForPressure > 0
                                ? (double) usedForPressure / tracker.getMaxTokens()
                                : 0.0);
                Map<String, Object> taskSummary = resolveTaskSummary(sessionId);
                if (taskSummary != null) {
                    session.put("taskSummary", taskSummary);
                }
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
        // Window occupancy only — never fall back to lifetime spend (tokensIn+tokensOut).
        int usedForPressure = tracker.getContextUsedTokens();
        Map<String, Object> pressure = new LinkedHashMap<>();
        pressure.put("usedTokens", usedForPressure);
        pressure.put("maxTokens", maxTokens);
        pressure.put(
                "ratio",
                maxTokens > 0 && usedForPressure > 0 ? (double) usedForPressure / maxTokens : 0.0);

        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("promptTokens", tracker.getTokensIn());
        tokenUsage.put("completionTokens", tracker.getTokensOut());
        tokenUsage.put("totalTokens", totalTokens);
        if (maxTokens > 0) {
            tokenUsage.put("maxTokens", maxTokens);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("id", sessionId);
        out.put("phase", phases.getOrDefault(sessionId, PHASE_IDLE));
        Boolean busy = busyFlags.get(sessionId);
        if (busy == null) {
            busy = PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE));
        }
        out.put("busy", busy);
        if (!tracker.getModel().isEmpty()) {
            out.put("model", tracker.getModel());
        }
        putIfPresent(out, "startedAt", isoOrNull(startedAt.get(sessionId)));
        putIfPresent(out, "lastActiveAt", isoOrNull(lastActiveAt.get(sessionId)));
        out.put("messageCount", tracker.getMessageCount());
        out.put("tokenUsage", tokenUsage);
        out.put("contextPressure", pressure);
        if (tracker.isCompacted()) {
            out.put("isCompacted", true);
        }
        Map<String, Object> taskSummary = resolveTaskSummary(sessionId);
        if (taskSummary != null) {
            out.put("taskSummary", taskSummary);
        }
        out.put("framework", frameworkName());
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
        setPhase(sessionId, PHASE_COMPRESSING);
        try {
            dispatchCommand(sessionId, FrameworkAdapter.COMMAND_COMPRESS, null);
            setPhase(sessionId, PHASE_IDLE);
        } catch (RuntimeException e) {
            setPhase(sessionId, PHASE_IDLE);
            throw e;
        }
    }

    @Override
    public void terminate(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_TERMINATE, null);
        setPhase(sessionId, PHASE_TERMINATED);
    }

    @Override
    public void abort(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_ABORT, null);
        setBusy(sessionId, false);
    }

    @Override
    public Map<String, Object> tasks(String sessionId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Map<String, Object>> items;
        try {
            items = adapter.listTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (!trackers.containsKey(sessionId)) {
                throw new NotFoundException("session not found: " + sessionId);
            }
            throw e;
        }
        if ((items == null || items.isEmpty()) && !trackers.containsKey(sessionId)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", items == null ? List.of() : items);
        return out;
    }

    @Override
    public Map<String, Object> subagentTasks(String sessionId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Map<String, Object>> items =
                adapter.listSubagentTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", items == null ? List.of() : items);
        return out;
    }

    @Override
    public void cancelSubagentTask(String sessionId, String taskId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.cancelSubagentTask(sessionId, taskId).block(ADAPTER_CALL_TIMEOUT);
    }

    @Override
    public void planMode(String sessionId, byte[] body) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.setPlanMode(sessionId, body).block(ADAPTER_CALL_TIMEOUT);
    }

    @Override
    public void teamJoin(byte[] body) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("team join body required");
        }
        dispatchTeamCommand(body, FrameworkAdapter.COMMAND_TEAM_JOIN, "team join");
    }

    @Override
    public void teamLeave(byte[] body) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("team leave body required");
        }
        dispatchTeamCommand(body, FrameworkAdapter.COMMAND_TEAM_LEAVE, "team leave");
    }

    /** Unwraps the {@code {sessionId, params}} envelope shared by the team HTTP endpoints. */
    private void dispatchTeamCommand(byte[] body, String command, String label) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = TEAM_EVENT_MAPPER.readValue(body, Map.class);
            Object sid = root.get("sessionId");
            if (sid == null || String.valueOf(sid).isBlank()) {
                throw new IllegalArgumentException("sessionId required");
            }
            byte[] params;
            Object rawParams = root.get("params");
            if (rawParams == null) {
                params = new byte[0];
            } else if (rawParams instanceof String s) {
                params = s.getBytes(StandardCharsets.UTF_8);
            } else {
                params = TEAM_EVENT_MAPPER.writeValueAsBytes(rawParams);
            }
            dispatchCommand(String.valueOf(sid), command, params);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid " + label + " body: " + e.getMessage(), e);
        }
    }

    @Override
    public String sessionPhase(String sessionId) {
        return phases.getOrDefault(sessionId, PHASE_IDLE);
    }

    // ─── helpers ───

    private Map<String, Object> resolveTaskSummary(String sessionId) {
        if (adapter == null) {
            return null;
        }
        try {
            List<Map<String, Object>> tasks =
                    adapter.listTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
            return summarizeTasks(tasks);
        } catch (UnsupportedOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> summarizeTasks(List<Map<String, Object>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (Map<String, Object> task : tasks) {
            Object state = task.get("state");
            String wire = state == null ? "" : state.toString();
            switch (wire) {
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                default -> pending++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", tasks.size());
        summary.put("pending", pending);
        summary.put("inProgress", inProgress);
        summary.put("completed", completed);
        return summary;
    }

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
