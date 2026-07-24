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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Global manager for graceful shutdown lifecycle and active request tracking.
 */
public final class GracefulShutdownManager {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownManager.class);
    private static final GracefulShutdownManager INSTANCE = new GracefulShutdownManager();

    private final AtomicReference<ShutdownState> state =
            new AtomicReference<>(ShutdownState.RUNNING);
    private final AtomicReference<GracefulShutdownConfig> config =
            new AtomicReference<>(GracefulShutdownConfig.DEFAULT);

    /**
     * Active requests keyed by their unique per-call {@code requestId}, not by agent id: a single
     * agent instance may serve many concurrent calls (distinct {@code (userId, sessionId)}
     * sessions), each of which must be tracked, interrupted, and saved independently. Keying by
     * agent id would collapse concurrent calls to one entry (last-writer-wins) and let one call's
     * completion unregister another in-flight call.
     */
    private final ConcurrentHashMap<String, ActiveRequestContext> activeRequestsById =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ShutdownStateSaver> stateSavers =
            new ConcurrentHashMap<>();
    private final AtomicReference<Instant> shutdownStartedAt = new AtomicReference<>(null);
    private final AtomicBoolean monitorStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService monitor =
            new ScheduledThreadPoolExecutor(
                    1,
                    r -> {
                        Thread t = new Thread(r, "agentscope-shutdown-monitor");
                        t.setDaemon(true);
                        return t;
                    });
    private final Object terminationLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> monitorFuture = new AtomicReference<>();
    private final AtomicReference<Sinks.Empty<Void>> shutdownTimeoutSignal =
            new AtomicReference<>(Sinks.empty());

    private GracefulShutdownManager() {
        AgentScopeJvmShutdownHook.register(this);
    }

    public static GracefulShutdownManager getInstance() {
        return INSTANCE;
    }

    public ShutdownState getState() {
        return state.get();
    }

    public GracefulShutdownConfig getConfig() {
        return config.get();
    }

    public void setConfig(GracefulShutdownConfig config) {
        this.config.set(config);
    }

    /**
     * Returns a {@link Mono} that completes when the shutdown timeout is reached.
     * Tool executors can race their execution against this signal to abort early.
     */
    public Mono<Void> getShutdownTimeoutSignal() {
        return shutdownTimeoutSignal.get().asMono();
    }

    /**
     * Register a {@link ShutdownStateSaver} for the given agent.
     *
     * <p>The saver is invoked during shutdown to persist the agent's {@link AgentState}
     * (with {@code shutdownInterrupted} set to {@code true}).
     */
    public void bindStateSaver(Agent agent, ShutdownStateSaver saver) {
        if (agent == null || saver == null) {
            return;
        }
        stateSavers.put(agent.getAgentId(), saver);
    }

    /**
     * Unregister the {@link ShutdownStateSaver} for the given agent.
     *
     * <p>Agents must call this when they are closed so the process-wide shutdown manager does not
     * retain short-lived agent instances through their saver callbacks.
     */
    public void unbindStateSaver(Agent agent) {
        if (agent == null) {
            return;
        }
        stateSavers.remove(agent.getAgentId());
    }

    /**
     * Check whether the agent was previously interrupted by shutdown, and clear the flag.
     *
     * <p>Called from {@link GracefulShutdownMiddleware} on each {@code onAgent} to detect
     * a client retry after shutdown interruption, so the duplicate user prompt can be
     * replaced with a "continue" message.
     *
     * @return true if the flag was present and cleared
     */
    public boolean checkAndClearShutdownInterrupted(Agent agent) {
        if (!(agent instanceof AgentBase ab)) {
            return false;
        }
        AgentState st = ab.getAgentState();
        if (st != null && st.isShutdownInterrupted()) {
            st.setShutdownInterrupted(false);
            return true;
        }
        return false;
    }

    public boolean isAcceptingRequests() {
        return getState() == ShutdownState.RUNNING;
    }

    public int getActiveRequestCount() {
        return activeRequestsById.size();
    }

    public void ensureAcceptingRequests() {
        if (!isAcceptingRequests()) {
            throw new AgentShuttingDownException();
        }
    }

    public String registerRequest(Agent agent) {
        if (!(agent instanceof AgentBase agentBase)) {
            return "";
        }
        ShutdownStateSaver saver = stateSavers.get(agent.getAgentId());
        String requestId = UUID.randomUUID().toString();
        ActiveRequestContext ctx = new ActiveRequestContext(requestId, agentBase, saver);
        activeRequestsById.put(requestId, ctx);
        return requestId;
    }

    /**
     * Bind the per-call {@link AgentState} resolved for the in-flight request identified by
     * {@code requestId}, so shutdown interruption and state-saving target that exact
     * {@code (userId, sessionId)} session rather than the agent's no-arg "most-recently-active"
     * accessors. Invoked by {@code AgentBase} once a call has activated its session slot. No-op when
     * no request is tracked for the id or the state is {@code null}.
     */
    public void bindRequestState(String requestId, AgentState state) {
        if (state == null) {
            return;
        }
        getActiveRequest(requestId).ifPresent(ctx -> ctx.bindState(state));
    }

    public void unregisterRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        activeRequestsById.remove(requestId);
        updateTerminatedIfNoRequests();
    }

    private Optional<ActiveRequestContext> getActiveRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeRequestsById.get(requestId));
    }

    public void interruptIfShuttingDown(String requestId) {
        Optional<ActiveRequestContext> contextOpt = getActiveRequest(requestId);
        if (contextOpt.isEmpty()) {
            return;
        }
        if (getState() == ShutdownState.SHUTTING_DOWN) {
            contextOpt.get().interruptForShutdown();
        }
    }

    /**
     * Called from agent's handleInterrupt when a SYSTEM interrupt is observed.
     * Always saves because memory may have been updated after the previous safe-point/timeout save.
     */
    public void saveOnInterruptObserved(String requestId) {
        getActiveRequest(requestId).ifPresent(ActiveRequestContext::saveState);
    }

    public boolean performGracefulShutdown() {
        ShutdownState previous = state.getAndUpdate(this::transitionToShuttingDown);
        if (previous == ShutdownState.TERMINATED) {
            return false;
        }
        if (previous == ShutdownState.RUNNING) {
            shutdownStartedAt.set(Instant.now());
            Duration timeout = config.get().shutdownTimeout();
            log.info(
                    "Graceful shutdown initiated, {} active request(s), timeout={}",
                    getActiveRequestCount(),
                    timeout != null ? timeout : "infinite");
        }
        startMonitorIfNeeded();
        return true;
    }

    private ShutdownState transitionToShuttingDown(ShutdownState current) {
        if (current == ShutdownState.TERMINATED) {
            return ShutdownState.TERMINATED;
        }
        return ShutdownState.SHUTTING_DOWN;
    }

    private void startMonitorIfNeeded() {
        if (!monitorStarted.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> future =
                monitor.scheduleAtFixedRate(
                        this::enforceTimeoutAndInterrupt, 0, 1, TimeUnit.SECONDS);
        monitorFuture.set(future);
    }

    private void enforceTimeoutAndInterrupt() {
        if (getState() != ShutdownState.SHUTTING_DOWN) {
            return;
        }
        Instant started = shutdownStartedAt.get();
        if (started == null) {
            return;
        }

        GracefulShutdownConfig cfg = config.get();
        Duration timeout = cfg.shutdownTimeout();

        if (timeout != null) {
            Duration elapsed = Duration.between(started, Instant.now());
            if (elapsed.compareTo(timeout) >= 0) {
                log.info(
                        "Shutdown timeout reached ({}s elapsed, limit={}s), force interrupting {}"
                                + " active request(s)",
                        elapsed.getSeconds(),
                        timeout.getSeconds(),
                        activeRequestsById.size());
                shutdownTimeoutSignal.get().tryEmitEmpty();

                for (ActiveRequestContext ctx : activeRequestsById.values()) {
                    ctx.saveState();
                    if (ctx.interruptForShutdown()) {
                        log.info(
                                "Shutdown force interrupt issued for request {}",
                                ctx.getRequestId());
                    }
                }
            }
        }
    }

    private void updateTerminatedIfNoRequests() {
        if (getState() == ShutdownState.SHUTTING_DOWN && activeRequestsById.isEmpty()) {
            if (state.compareAndSet(ShutdownState.SHUTTING_DOWN, ShutdownState.TERMINATED)) {
                synchronized (terminationLock) {
                    terminationLock.notifyAll();
                }
            }
        }
    }

    /**
     * Block until the shutdown state reaches TERMINATED or the given timeout elapses.
     *
     * @param timeout maximum time to wait; {@code null} means wait indefinitely
     * @return true if TERMINATED was reached, false if timed out
     */
    public boolean awaitTermination(Duration timeout) {
        updateTerminatedIfNoRequests();
        long deadline =
                timeout != null ? System.currentTimeMillis() + timeout.toMillis() : Long.MAX_VALUE;
        synchronized (terminationLock) {
            while (getState() != ShutdownState.TERMINATED) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    terminationLock.wait(Math.min(remaining, 1000));
                } catch (java.lang.InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Reset manager state. Intended for testing and demo purposes only.
     */
    public void resetForTesting() {
        ScheduledFuture<?> future = monitorFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        state.set(ShutdownState.RUNNING);
        activeRequestsById.clear();
        stateSavers.clear();
        shutdownStartedAt.set(null);
        monitorStarted.set(false);
        shutdownTimeoutSignal.set(Sinks.empty());
    }
}
