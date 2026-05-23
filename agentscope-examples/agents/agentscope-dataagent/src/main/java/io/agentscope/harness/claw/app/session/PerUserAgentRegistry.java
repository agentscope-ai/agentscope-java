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
package io.agentscope.harness.claw.app.session;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy per-user cache of {@link HarnessAgent} instances.
 *
 * <p>HarnessAgent holds a single {@code AtomicReference<String> userIdRef} that is mutated on every
 * invocation to set the namespace prefix for filesystem operations. When two requests from
 * different users hit the same agent instance concurrently, the namespace prefix races, and one
 * user's tool call can land in another user's namespace. The single-tenant {@code agentscope-claw}
 * never hit this because every user's workspace was the same path, so the prefix was always
 * empty. Once we wire per-user workspaces in, the race is no longer theoretical.
 *
 * <p>This registry sidesteps that entirely: each {@code userId} gets its own dedicated
 * {@link HarnessAgent} whose {@code workspace} is locked at build time to that user's directory.
 * The factory passed to the constructor is responsible for producing fully-wired agents (model,
 * filesystem, hooks, skills, sessions tool).
 *
 * <p>Concurrency: per-user build is serialised through {@link ConcurrentHashMap#computeIfAbsent},
 * so two simultaneous first-time chats for the same user wait for one shared build. Different
 * users build in parallel.
 *
 * <p>Eviction: idle entries (no access for {@code idleTtlMs}) are reclaimed by a background sweep.
 * Eviction is best-effort — a build in flight while sweep runs may briefly outlive the cutoff but
 * never gets corrupted.
 */
public final class PerUserAgentRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PerUserAgentRegistry.class);

    private final Function<String, HarnessAgent> agentFactory;
    private final long idleTtlMs;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    /**
     * @param agentFactory builds a fully-wired {@link HarnessAgent} for the given userId
     * @param idleTtlMs how long an entry can sit unused before being evicted; pass {@code 0} or
     *     negative to disable eviction (useful for tests)
     */
    public PerUserAgentRegistry(Function<String, HarnessAgent> agentFactory, long idleTtlMs) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.idleTtlMs = idleTtlMs;
        if (idleTtlMs > 0) {
            this.sweeper =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                Thread t = new Thread(r, "per-user-agent-sweeper");
                                t.setDaemon(true);
                                return t;
                            });
            long period = Math.max(60_000L, idleTtlMs / 4);
            this.sweeper.scheduleAtFixedRate(
                    this::sweepIdle, period, period, TimeUnit.MILLISECONDS);
        } else {
            this.sweeper = null;
        }
    }

    /**
     * Returns the cached agent for {@code userId}, building it on the first call. Updates the
     * last-access timestamp on each call so idle eviction never trims a hot entry.
     */
    public HarnessAgent getOrBuild(String userId) {
        UserWorkspaceProvisioner.validateUserId(userId);
        Entry entry =
                entries.computeIfAbsent(
                        userId,
                        uid -> {
                            HarnessAgent agent = agentFactory.apply(uid);
                            if (agent == null) {
                                throw new IllegalStateException(
                                        "agentFactory returned null for userId=" + uid);
                            }
                            log.debug("Built HarnessAgent for userId={}", uid);
                            return new Entry(agent);
                        });
        entry.lastAccessMs.set(System.currentTimeMillis());
        return entry.agent;
    }

    /**
     * Returns the cached agent for {@code userId} without building one. Used by admin/observability
     * paths that don't want to incidentally provision a tenant.
     */
    public HarnessAgent peek(String userId) {
        if (userId == null) return null;
        Entry e = entries.get(userId);
        return e == null ? null : e.agent;
    }

    /** Snapshot of all currently-cached user ids (for admin / metrics). */
    public Collection<String> cachedUserIds() {
        return List.copyOf(entries.keySet());
    }

    /** Forces eviction of {@code userId}'s cached agent. Next call rebuilds. */
    public void evict(String userId) {
        if (userId == null) return;
        Entry removed = entries.remove(userId);
        if (removed != null) {
            log.debug("Evicted HarnessAgent for userId={}", userId);
        }
    }

    /** Number of currently-cached entries. Mainly for tests / metrics. */
    public int size() {
        return entries.size();
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    private void sweepIdle() {
        try {
            long cutoff = System.currentTimeMillis() - idleTtlMs;
            List<String> doomed = new ArrayList<>();
            entries.forEach(
                    (uid, entry) -> {
                        if (entry.lastAccessMs.get() < cutoff) {
                            doomed.add(uid);
                        }
                    });
            for (String uid : doomed) {
                evict(uid);
            }
            if (!doomed.isEmpty()) {
                log.info("Idle-evicted {} cached HarnessAgent(s): {}", doomed.size(), doomed);
            }
        } catch (Exception e) {
            log.warn("PerUserAgentRegistry idle sweep failed: {}", e.getMessage());
        }
    }

    private static final class Entry {
        final HarnessAgent agent;
        final AtomicLong lastAccessMs;

        Entry(HarnessAgent agent) {
            this.agent = agent;
            this.lastAccessMs = new AtomicLong(System.currentTimeMillis());
        }
    }
}
