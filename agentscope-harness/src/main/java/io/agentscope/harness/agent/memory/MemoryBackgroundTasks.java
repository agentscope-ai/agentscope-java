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

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks in-flight fire-and-forget memory background tasks (flush, maintenance) so
 * {@code HarnessAgent.close()} can wait for them to quiesce before releasing resources.
 *
 * <p>Middleware registers work under its owning agent before dispatch. Closing one Harness
 * waits for its delegate's work only. The global overload is retained for application-wide
 * drains and test workspace cleanup.
 */
public final class MemoryBackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTasks.class);

    private static final Object MONITOR = new Object();
    private static int inFlight = 0;
    private static final Map<Object, Integer> OWNERS = new IdentityHashMap<>();

    private MemoryBackgroundTasks() {}

    /** Marks the start of a fire-and-forget background task. */
    public static void begin() {
        begin(null);
    }

    /** Marks a task before dispatch, scoped to the agent that owns its resources. */
    public static void begin(Object owner) {
        synchronized (MONITOR) {
            inFlight++;
            OWNERS.merge(owner, 1, Integer::sum);
        }
    }

    /** Marks the end of a fire-and-forget background task (must pair with {@link #begin()}). */
    public static void end() {
        end(null);
    }

    /** Releases the matching owner's task, including work displaced before execution. */
    public static void end(Object owner) {
        synchronized (MONITOR) {
            Integer count = OWNERS.get(owner);
            if (count == null) return;
            if (count == 1) OWNERS.remove(owner);
            else OWNERS.put(owner, count - 1);
            inFlight--;
            MONITOR.notifyAll();
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
        return await(null, true, timeout, unit);
    }

    /** Waits only for this agent's tasks; unrelated agents cannot delay its cleanup. */
    public static boolean awaitQuiescence(Object owner, long timeout, TimeUnit unit) {
        return await(owner, false, timeout, unit);
    }

    private static boolean await(Object owner, boolean global, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (MONITOR) {
            while (global ? inFlight > 0 : OWNERS.containsKey(owner)) {
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
