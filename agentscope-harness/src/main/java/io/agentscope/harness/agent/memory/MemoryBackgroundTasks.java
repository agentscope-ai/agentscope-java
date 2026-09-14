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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

/**
 * Tracks in-flight fire-and-forget memory background tasks (flush, maintenance) so
 * {@code HarnessAgent.close()} can cancel them and wait for them to quiesce before releasing
 * resources.
 *
 * <p>The middleware instances that dispatch these tasks are created per agent, so they
 * cannot be reached from the agent's {@code close()} method. Like
 * {@link io.agentscope.harness.agent.memory.session.SessionTree#awaitMirrorQuiescence}, the
 * in-flight count is process-wide: {@code close()} blocks until every background task started
 * before the call has finished, so async workspace writes do not race with resource cleanup
 * (e.g. temp workspace deletion in tests).
 *
 * <p>Tasks are registered with an <em>owner</em> — the {@code HarnessAgent} whose memory
 * middleware dispatched them — so cancellation is agent-scoped: closing one agent cancels only
 * that agent's tasks and never the in-flight tasks of other live agents. The no-arg
 * {@link #cancelAll()} keeps the legacy global-cancel behaviour for tests and tooling.
 *
 * <p>An owner that has been marked via {@link #shutdown(Object)} rejects further
 * registrations: tasks dispatched concurrently with the agent's shutdown are disposed
 * immediately instead of starting real work (a model call whose HTTP connection would
 * otherwise outlive the closed agent).
 */
public final class MemoryBackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTasks.class);

    private static final Object MONITOR = new Object();
    private static int inFlight = 0;

    /**
     * Sentinel owner for legacy {@link #register(Disposable)} registrations and for callers
     * that pass a {@code null} owner: such tasks are only cancelled by the global
     * {@link #cancelAll()}. (The map itself must not hold {@code null} values.)
     */
    private static final Object UNOWNED = new Object();

    /**
     * In-flight tasks keyed by their {@link Disposable}, valued by the owner that dispatched
     * them.
     */
    private static final ConcurrentHashMap<Disposable, Object> IN_FLIGHT_TASKS =
            new ConcurrentHashMap<>();

    /**
     * Owners (agent instances) that have been shut down. Weak references so a closed agent can
     * be garbage-collected once nothing else references it.
     */
    private static final Set<Object> SHUTDOWN_OWNERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
     * Registers an in-flight background task without an owner. Equivalent to
     * {@link #register(Object, Disposable)} with a {@code null} owner: the task is only
     * cancelled by the global {@link #cancelAll()}.
     */
    public static void register(Disposable d) {
        register(UNOWNED, d);
    }

    /**
     * Registers an in-flight background task owned by {@code owner} so
     * {@link #cancelAll(Object)} can cancel it when that agent shuts down. The task's
     * {@link Disposable} must be passed here instead of being discarded, otherwise its
     * underlying model call (and the HTTP connection behind it) can outlive the agent.
     *
     * <p>If {@code owner} has already been {@link #shutdown(Object)}-marked, the disposable is
     * disposed immediately: the task was dispatched concurrently with shutdown and must not
     * start real work.
     */
    public static void register(Object owner, Disposable d) {
        if (d == null || d.isDisposed()) {
            return;
        }
        Object effectiveOwner = owner != null ? owner : UNOWNED;
        if (isShutdown(effectiveOwner)) {
            d.dispose();
            return;
        }
        IN_FLIGHT_TASKS.put(d, effectiveOwner);
        // Re-check after publishing: a concurrent shutdown(owner) may have been marked between
        // the check above and this put. In that case cancelAll(owner) has already scanned the
        // registry and will never see this task, so dispose it here rather than letting it
        // outlive its agent.
        if (isShutdown(effectiveOwner) && IN_FLIGHT_TASKS.remove(d) != null) {
            disposeQuietly(d);
        }
    }

    /** Removes a finished task from the registry (call from the task's {@code doFinally}). */
    public static void unregister(Disposable d) {
        if (d != null) {
            IN_FLIGHT_TASKS.remove(d);
        }
    }

    /**
     * Cancels every in-flight background task regardless of owner. Intended for tests and
     * tooling; production code should use the agent-scoped {@link #cancelAll(Object)} so other
     * live agents are not affected.
     */
    public static void cancelAll() {
        for (Map.Entry<Disposable, Object> entry : List.copyOf(IN_FLIGHT_TASKS.entrySet())) {
            if (IN_FLIGHT_TASKS.remove(entry.getKey()) != null) {
                disposeQuietly(entry.getKey());
            }
        }
    }

    /**
     * Cancels every in-flight task owned by {@code owner}, leaving other agents' tasks
     * running. Called from {@code HarnessAgent.close()} so the agent's model calls (and their
     * HTTP connections) are released promptly instead of lingering until their per-call
     * timeout. Each task is removed from the registry <em>before</em> being disposed, so tasks
     * registered concurrently with this call stay tracked.
     */
    public static void cancelAll(Object owner) {
        if (owner == null) {
            return;
        }
        for (Map.Entry<Disposable, Object> entry : List.copyOf(IN_FLIGHT_TASKS.entrySet())) {
            if (owner.equals(entry.getValue()) && IN_FLIGHT_TASKS.remove(entry.getKey()) != null) {
                disposeQuietly(entry.getKey());
            }
        }
    }

    /**
     * Marks {@code owner} (an agent) as shut down. Subsequent
     * {@link #register(Object, Disposable)} calls for this owner dispose their task
     * immediately, so background work dispatched concurrently with or after
     * {@code HarnessAgent.close()} never starts.
     */
    public static void shutdown(Object owner) {
        if (owner != null) {
            SHUTDOWN_OWNERS.add(owner);
        }
    }

    /** @return whether {@link #shutdown(Object)} has been called for {@code owner}. */
    public static boolean isShutdown(Object owner) {
        return owner != null && SHUTDOWN_OWNERS.contains(owner);
    }

    private static void disposeQuietly(Disposable d) {
        try {
            d.dispose();
        } catch (Exception e) {
            log.debug("Error cancelling memory background task: {}", e.getMessage());
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
