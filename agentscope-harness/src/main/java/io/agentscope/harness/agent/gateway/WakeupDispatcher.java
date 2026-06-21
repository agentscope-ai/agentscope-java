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

        Mono<Msg> runWakeup(String sessionId);
    }

    public WakeupDispatcher(MessageBus messageBus, WakeupTarget target) {
        this.messageBus = messageBus;
        this.target = target;
    }

    /**
     * Starts the dispatcher: subscribes to the live signal channel, then performs an initial drain
     * of any queued wakeups produced while this process was down.
     */
    public void start() {
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
        drainAndDispatch();
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
            while (true) {
                List<BusEntry> entries =
                        messageBus.queueDrain("agentscope:wakeups", MAX_DRAIN_COUNT).block();
                if (entries == null || entries.isEmpty()) {
                    return;
                }

                for (BusEntry entry : entries) {
                    dispatch(entry.payload());
                }

                if (entries.size() < MAX_DRAIN_COUNT) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("WakeupDispatcher: drainAndDispatch failed", e);
        }
    }

    private void dispatch(Map<String, Object> payload) {
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

        log.info("WakeupDispatcher: waking idle session {}, agentId={}", sessionId, agentId);
        target.runWakeup(sessionId)
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
