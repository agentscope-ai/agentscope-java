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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Schedules {@link SkillCurator} runs after the main {@code call()} completes. Behaves like
 * {@code MemoryMaintenanceMiddleware}: gates on idle-time + interval, runs on a single-thread
 * daemon executor so the agent loop is never blocked.
 */
public class SkillCuratorMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorMiddleware.class);

    private final SkillCurator curator;
    private final ScheduledExecutorService executor;
    private final AtomicReference<Instant> lastCallEnded = new AtomicReference<>();
    private volatile boolean shutdown = false;

    public SkillCuratorMiddleware(SkillCurator curator) {
        this.curator = java.util.Objects.requireNonNull(curator, "curator");
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "skill-curator-mw");
                            t.setDaemon(true);
                            return t;
                        });
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnComplete(
                        () -> {
                            lastCallEnded.set(Instant.now());
                            maybeRunCurator();
                        });
    }

    /**
     * Called from the {@code onAgent} doOnComplete: if the gate accepts, dispatch a curator run
     * to the daemon executor. {@code minIdleHours} == 0 makes this run effectively eagerly,
     * matching plan-text default.
     */
    private void maybeRunCurator() {
        if (shutdown) {
            return;
        }
        Instant now = Instant.now();
        if (!curator.shouldRunNow(now)) {
            return;
        }
        executor.submit(
                () -> {
                    try {
                        SkillCurator.CuratorRunReport report = curator.runOnce(Instant.now());
                        log.info(
                                "skill-curator ran: transitions={} report={} duration_ms={}",
                                report.transitions(),
                                report.dryRunReportPath(),
                                report.durationMs());
                    } catch (Exception e) {
                        log.warn("skill-curator run failed: {}", e.getMessage(), e);
                    }
                });
    }

    /** Stop accepting new background work; idempotent. */
    public void close() {
        shutdown = true;
        executor.shutdownNow();
    }

    /** Direct access to the underlying curator (for {@code agent.runCuratorOnce}). */
    public SkillCurator curator() {
        return curator;
    }
}
