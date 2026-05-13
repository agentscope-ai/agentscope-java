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
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
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
    private final ConcurrentHashMap<String, ActiveRequestContext> activeRequestsByAgentId =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ShutdownSessionBinding> sessionBindings =
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

    public void bindSession(Agent agent, Session session, SessionKey sessionKey) {
        if (agent == null || session == null || sessionKey == null) {
            return;
        }
        sessionBindings.put(agent.getAgentId(), new ShutdownSessionBinding(session, sessionKey));
    }

    /**
     * Check whether the agent's session was previously interrupted by shutdown, and clear the flag.
     *
     * <p>Called from {@link GracefulShutdownHook} at {@code PreCallEvent} to detect
     * a client retry after shutdown interruption, so the duplicate user prompt can be
     * replaced with a "continue" message.
     *
     * @return true if the flag was present and cleared
     */
    public boolean checkAndClearShutdownInterrupted(Agent agent) {
        if (agent == null) {
            return false;
        }
        ShutdownSessionBinding binding = sessionBindings.get(agent.getAgentId());
        if (binding == null) {
            return false;
        }
        Optional<ShutdownInterruptedState> flag =
                binding.session()
                        .get(
                                binding.sessionKey(),
                                ActiveRequestContext.SHUTDOWN_INTERRUPTED_KEY,
                                ShutdownInterruptedState.class);
        if (flag.isPresent() && flag.get().interrupted()) {
            binding.session()
                    .delete(binding.sessionKey(), ActiveRequestContext.SHUTDOWN_INTERRUPTED_KEY);
            return true;
        }
        return false;
    }

    public boolean isAcceptingRequests() {
        return getState() == ShutdownState.RUNNING;
    }

    public int getActiveRequestCount() {
        return activeRequestsByAgentId.size();
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
        Optional<ShutdownSessionBinding> binding =
                Optional.ofNullable(sessionBindings.get(agent.getAgentId()));
        var session = binding.map(ShutdownSessionBinding::session).orElse(null);
        var sessionKey = binding.map(ShutdownSessionBinding::sessionKey).orElse(null);
        String requestId = UUID.randomUUID().toString();
        ActiveRequestContext ctx =
                new ActiveRequestContext(requestId, agentBase, session, sessionKey);
        activeRequestsByAgentId.put(agent.getAgentId(), ctx);
        return requestId;
    }

    public void unregisterRequest(Agent agent) {
        if (agent == null) {
            return;
        }
        activeRequestsByAgentId.remove(agent.getAgentId());
        sessionBindings.remove(agent.getAgentId());
        updateTerminatedIfNoRequests();
    }

    private Optional<ActiveRequestContext> getActiveRequestByAgent(Agent agent) {
        if (agent == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeRequestsByAgentId.get(agent.getAgentId()));
    }

    public void interruptIfShuttingDown(Agent agent) {
        Optional<ActiveRequestContext> contextOpt = getActiveRequestByAgent(agent);
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
    public void saveOnInterruptObserved(Agent agent) {
        getActiveRequestByAgent(agent).ifPresent(ActiveRequestContext::saveToSession);
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

        // Check timeout and force interrupt if needed
        if (timeout != null) {
            Duration elapsed = Duration.between(started, Instant.now());
            if (elapsed.compareTo(timeout) >= 0) {
                log.info(
                        "Shutdown timeout reached ({}s elapsed, limit={}s), force interrupting {}"
                                + " active request(s)",
                        elapsed.getSeconds(),
                        timeout.getSeconds(),
                        activeRequestsByAgentId.size());
                shutdownTimeoutSignal.get().tryEmitEmpty();

                for (ActiveRequestContext ctx : activeRequestsByAgentId.values()) {
                    ctx.saveToSession();
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
        if (getState() == ShutdownState.SHUTTING_DOWN && activeRequestsByAgentId.isEmpty()) {
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
        activeRequestsByAgentId.clear();
        sessionBindings.clear();
        shutdownStartedAt.set(null);
        monitorStarted.set(false);
        shutdownTimeoutSignal.set(Sinks.empty());
    }
}
