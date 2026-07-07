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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Per-process dispatcher that wakes idle sessions when background work completes.
 *
 * <p>Subscribes to the shared wakeup signal channel on {@link MessageBus#subscribeWakeup()} and
 * drains the durable wakeup queue on each signal. For each queued entry whose target session is
 * idle, triggers a new reasoning round via {@link WakeupTarget#runWakeup(String)}.
 *
 * It serves as the universal activator for all cross-session communication:
 *
 * <ul>
 *   <li>Background subagent task completion
 *   <li>Async tool results ({@link io.agentscope.harness.agent.middleware.AsyncToolMiddleware})
 *   <li>Team messages (future Agent Teams support)
 *   <li>Scheduled task triggers
 * </ul>
 *
 * <p>Lifecycle: call {@link #start()} after the gateway is fully configured. Call {@link #close()}
 * on shutdown. Typically managed by the application bootstrap or gateway factory.
 */
public class WakeupDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WakeupDispatcher.class);

    private static final int MAX_DRAIN_COUNT = 64;

    private final MessageBus messageBus;
    private final WakeupTarget target;
    private volatile Disposable subscription;

    /**
     * Thin interface for the two gateway operations the dispatcher needs. {@link HarnessGateway}
     * implements this directly; tests can supply a stub.
     */
    public interface WakeupTarget {
        boolean isSessionRunning(String sessionId);

        /**
         * Triggers a wakeup run without an owning user id.
         *
         * @param sessionId the session to wake up
         * @return the agent's response, or {@link Mono#empty()} if the session is unknown
         * @deprecated implementers should override {@link #runWakeup(String, String)} instead, so
         *     the dispatcher can propagate the owning user id. Required for runtimes that isolate
         *     agent state by {@code (userId, sessionId)}: without it, a wakeup-driven round loads
         *     state from an anonymous slot and the original conversation is lost. This overload is
         *     retained for backward compatibility and defaults to ignoring the user id.
         */
        @Deprecated
        Mono<Msg> runWakeup(String sessionId);

        /**
         * Triggers a wakeup run for an idle session, carrying the owning user id when present.
         *
         * <p>Called by {@link WakeupDispatcher} when a background task completes or a team message
         * arrives. Implementations should set the user id on the agent runtime context when
         * non-blank, so agent state is loaded from the same {@code (userId, sessionId)} slot as
         * the original user-driven run.
         *
         * @param userId the owning user id carried by the wakeup entry; {@code null} or blank when
         *     the producer had none (e.g. single-tenant runtimes)
         * @param sessionId the session to wake up
         * @return the agent's response, or {@link Mono#empty()} if the session is unknown
         */
        default Mono<Msg> runWakeup(String userId, String sessionId) {
            return runWakeup(sessionId);
        }
    }

    public WakeupDispatcher(MessageBus messageBus, WakeupTarget target) {
        this.messageBus = messageBus;
        this.target = target;
    }

    /**
     * Starts the dispatcher: performs an initial drain of any queued wakeups (to pick up signals
     * produced while this process was down), then subscribes to the live signal channel.
     */
    public void start() {
        drainAndDispatch();
        subscription =
                messageBus
                        .subscribeWakeup()
                        .subscribe(
                                signal -> drainAndDispatch(),
                                err ->
                                        log.error(
                                                "WakeupDispatcher subscription error; "
                                                        + "dispatcher is dead",
                                                err));
        log.info("WakeupDispatcher started");
    }

    @Override
    public void close() {
        Disposable d = subscription;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        subscription = null;
        log.info("WakeupDispatcher stopped");
    }

    private void drainAndDispatch() {
        try {
            List<BusEntry> entries =
                    messageBus.queueDrain("agentscope:wakeups", MAX_DRAIN_COUNT).block();
            if (entries == null || entries.isEmpty()) {
                return;
            }

            for (BusEntry entry : entries) {
                dispatch(entry.payload());
            }
        } catch (Exception e) {
            log.warn("WakeupDispatcher: drainAndDispatch failed", e);
        }
    }

    private void dispatch(Map<String, Object> payload) {
        String userId = getString(payload, "userId");
        String sessionId = getString(payload, "sessionId");
        String agentId = getString(payload, "agentId");

        if (sessionId == null || sessionId.isBlank()) {
            log.debug("WakeupDispatcher: skipping entry with no sessionId");
            return;
        }

        if (target.isSessionRunning(sessionId)) {
            log.debug(
                    "WakeupDispatcher: session {} is running, skipping (current run will drain"
                            + " inbox)",
                    sessionId);
            return;
        }

        log.info(
                "WakeupDispatcher: waking idle session {}, userId={}, agentId={}",
                sessionId,
                userId,
                agentId);
        target.runWakeup(userId, sessionId)
                .subscribe(
                        msg ->
                                log.debug(
                                        "WakeupDispatcher: wakeup run completed for session {}",
                                        sessionId),
                        err ->
                                log.warn(
                                        "WakeupDispatcher: wakeup run failed for session {}",
                                        sessionId,
                                        err));
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
