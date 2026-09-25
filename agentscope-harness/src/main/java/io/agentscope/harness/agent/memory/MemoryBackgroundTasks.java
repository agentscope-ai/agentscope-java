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
package io.agentscope.harness.agent.memory;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks in-flight fire-and-forget memory background tasks (flush, maintenance) so
 * {@code HarnessAgent.close()} can wait for them to quiesce before releasing resources.
 *
 * <p>The middleware instances that dispatch these tasks are created per agent call, so they
 * cannot be reached from the agent's {@code close()} method. Like
 * {@link io.agentscope.harness.agent.memory.session.SessionTree#awaitMirrorQuiescence}, the
 * in-flight count is process-wide: {@code close()} blocks until every background task started
 * before the call has finished, so async workspace writes do not race with resource cleanup
 * (e.g. temp workspace deletion in tests).
 */
public final class MemoryBackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTasks.class);

    private static final Object MONITOR = new Object();
    private static int inFlight = 0;

    private MemoryBackgroundTasks() {}

    /** Marks the start of a fire-and-forget background task. */
    public static void begin() {
        synchronized (MONITOR) {
            inFlight++;
        }
    }

    /** Marks the end of a fire-and-forget background task (must pair with {@link #begin()}). */
    public static void end() {
        synchronized (MONITOR) {
            if (inFlight > 0) {
                inFlight--;
            }
            if (inFlight == 0) {
                MONITOR.notifyAll();
            }
        }
    }

    /**
     * Blocks until all background tasks started before this call have finished, or the timeout
     * elapses.
     *
     * @param timeout maximum time to wait
     * @param unit time unit of {@code timeout}
     * @return {@code true} if the tasks quiesced within the timeout
     */
    public static boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (MONITOR) {
            while (inFlight > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    MONITOR.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Memory background tasks await interrupted: {}", e.getMessage());
                    return false;
                }
            }
            return true;
        }
    }
}
