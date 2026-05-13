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

import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM hook for SIGTERM graceful shutdown entry.
 *
 * <p>Shutdown order:
 * <ol>
 *   <li>Trigger graceful shutdown (reject new requests, interrupt at checkpoints)</li>
 *   <li>Wait for all active agent requests to complete or timeout</li>
 *   <li>Close HTTP transports (only after agents are done)</li>
 * </ol>
 */
public final class AgentScopeJvmShutdownHook {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeJvmShutdownHook.class);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static final Duration INTERRUPT_GRACE_PERIOD = Duration.ofSeconds(5);

    private AgentScopeJvmShutdownHook() {}

    public static void register(GracefulShutdownManager manager) {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        manager.performGracefulShutdown();
                                        Duration timeout = manager.getConfig().shutdownTimeout();
                                        // Wait for shutdown timeout + an extra grace period,
                                        // so agents have time to handle the interrupt and
                                        // clean up before HTTP transports are closed.
                                        Duration awaitTimeout =
                                                timeout != null
                                                        ? timeout.plus(INTERRUPT_GRACE_PERIOD)
                                                        : null;
                                        manager.awaitTermination(awaitTimeout);
                                    } catch (Exception e) {
                                        log.warn("Graceful shutdown hook failed", e);
                                    } finally {
                                        HttpTransportFactory.shutdown();
                                    }
                                },
                                "agentscope-jvm-shutdown-hook"));
    }
}
