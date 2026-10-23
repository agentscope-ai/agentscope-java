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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the per-{@code (userId, agentId)} live {@link Sandbox} instances used by the DataAgent web
 * tier.
 *
 * <p>Both the browser workspace controllers and the agent runtime (via {@link
 * io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()}) read and write through
 * the sandbox returned by {@link #borrow(String, String)}. Reusing a single container per user
 * across browser requests and agent turns is what makes the workspace user-isolated — every other
 * route the old {@link io.agentscope.harness.agent.filesystem.CompositeFilesystem} fell through to
 * a shared {@code LocalFilesystem}, leaking content across tenants.
 *
 * <p>Lifecycle: a sandbox is created+started lazily on the first {@link #borrow} for a key, kept
 * alive across subsequent borrows, and closed once it has gone idle for {@link #idleTtl}. {@link
 * #shutdownAll()} runs on bean destruction. Approval of a marketplace contribution calls
 * {@link #invalidate(String, String)} to evict all sandboxes whose shared layer has changed.
 *
 * <p>Threading: {@link ConcurrentHashMap#compute} serialises {@link #borrow} for the same key, so
 * {@link Sandbox#start()} is only invoked once per container. Concurrent borrows on different keys
 * are independent.
 *
 * <p>Multi-replica: this registry is in-memory only. Deployments with more than one replica must
 * use sticky load-balancing by {@code userId} to keep a user's traffic on the same pod (each pod
 * would otherwise spin up its own container for the same user, and the frontend would observe
 * non-deterministic state).
 */
public final class UserSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxRegistry.class);

    private static final List<String> DEFAULT_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge");

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final Path hostWorkspaceRoot;
    private final Duration idleTtl;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    /**
     * @param client backend client used to {@link SandboxClient#create create} new sandboxes
     * @param hostWorkspaceRoot directory under which per-agent shared seed lives, organised as
     *     {@code <hostWorkspaceRoot>/agents/<agentId>/{AGENTS.md, skills/, subagents/, knowledge/}}.
     *     Each container is projected with the slice for its own {@code agentId} only, so two
     *     agents on the same host do not see each other's shared layer. May be {@code null} to
     *     skip projection entirely (every container starts empty).
     * @param idleTtl how long a sandbox may sit unused before {@link #evictIdle()} closes it
     * @param evictionPollInterval how often the background scheduler checks for idle sandboxes
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            Path hostWorkspaceRoot,
            Duration idleTtl,
            Duration evictionPollInterval) {
        this.client = Objects.requireNonNull(client, "client");
        this.hostWorkspaceRoot = hostWorkspaceRoot;
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "UserSandboxRegistry-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "UserSandboxRegistry initialised: idleTtl={}, evictionPoll={},"
                        + " hostWorkspaceRoot={}",
                idleTtl,
                evictionPollInterval,
                hostWorkspaceRoot);
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)}, creating + starting it on
     * first call. Subsequent calls within the {@link #idleTtl} window return the same instance
     * and bump its idle timer.
     */
    public Sandbox borrow(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null) {
                                existing.touch();
                                return existing;
                            }
                            return new Entry(createAndStart(k));
                        });
        return entry.sandbox;
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)} if one is already cached.
     * Does NOT create a new container — useful for callers (e.g. file-tree rendering on a freshly
     * loaded UI) that should not pay the cold-start cost when nothing has happened yet.
     */
    public Optional<Sandbox> peek(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Entry e = entries.get(new Key(userId, agentId));
        if (e == null) {
            return Optional.empty();
        }
        e.touch();
        return Optional.of(e.sandbox);
    }

    /**
     * Closes and removes cached sandboxes matching {@code (userId, agentId)}.
     *
     * <ul>
     *   <li>{@code userId} non-null: only that user's sandbox for the agent is evicted.
     *   <li>{@code userId} null: every user's sandbox for the agent is evicted — used by the
     *       contribution-approval flow so the next {@link #borrow} for any user of that agent
     *       reconstructs the container and picks up the newly approved shared content.
     * </ul>
     *
     * <p>Safe to call concurrently with other {@link #borrow} calls — they will simply re-create
     * the sandbox on the next access.
     */
    public void invalidate(String userId, String agentId) {
        validateSegment("agentId", agentId);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Key k = e.getKey();
            if (!k.agentId().equals(agentId)) {
                continue;
            }
            if (userId != null && !userId.isBlank() && !k.userId().equals(userId)) {
                continue;
            }
            it.remove();
            closeQuietly(k, e.getValue().sandbox, "invalidate");
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[sandbox-registry] invalidated {} sandbox(es) for agentId={}, userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /**
     * Visible for tests. Closes every sandbox whose last access time is older than {@link
     * #idleTtl}.
     */
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - idleTtl.toMillis();
        entries.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().lastAccessMs >= cutoff) {
                                return false;
                            }
                            closeQuietly(e.getKey(), e.getValue().sandbox, "idle");
                            return true;
                        });
    }

    private void evictIdleQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[sandbox-registry] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().sandbox, "shutdown");
        }
        entries.clear();
    }

    private Sandbox createAndStart(Key key) {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions();
        WorkspaceSpec ws = buildWorkspaceSpec(key);
        Sandbox sandbox = client.create(ws, new NoopSnapshotSpec(), options);
        try {
            sandbox.start();
        } catch (Exception startErr) {
            try {
                sandbox.close();
            } catch (Exception closeErr) {
                log.warn(
                        "[sandbox-registry] failed to close half-started sandbox for {}: {}",
                        key,
                        closeErr.getMessage());
            }
            throw new IllegalStateException(
                    "Failed to start sandbox for " + key + ": " + startErr.getMessage(), startErr);
        }
        log.info("[sandbox-registry] started sandbox for {}", key);
        return sandbox;
    }

    /**
     * Builds the workspace projection spec for {@code key}: the source root is the per-agent slice
     * under {@link #hostWorkspaceRoot} so the container only sees its own agent's shared layer.
     * The directory is created on demand to avoid a Docker mount failure when the agent's slice
     * doesn't exist yet.
     */
    private WorkspaceSpec buildWorkspaceSpec(Key key) {
        WorkspaceSpec spec = new WorkspaceSpec();
        if (hostWorkspaceRoot == null) {
            return spec;
        }
        Path agentSlice =
                hostWorkspaceRoot
                        .resolve("agents")
                        .resolve(key.agentId())
                        .toAbsolutePath()
                        .normalize();
        try {
            Files.createDirectories(agentSlice);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to ensure per-agent shared dir " + agentSlice + ": " + e.getMessage(),
                    e);
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(agentSlice.toString());
        projection.setIncludeRoots(DEFAULT_PROJECTION_ROOTS);
        Map<String, WorkspaceEntry> es = new LinkedHashMap<>();
        es.put("__workspace_projection__", projection);
        spec.setEntries(es);
        return spec;
    }

    private void closeQuietly(Key key, Sandbox sandbox, String reason) {
        try {
            sandbox.close();
            log.info("[sandbox-registry] closed sandbox for {} ({})", key, reason);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-registry] failed to close sandbox for {} ({}): {}",
                    key,
                    reason,
                    e.getMessage(),
                    e);
        }
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /** Composite key used to scope a sandbox to one user + agent. */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs;

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
