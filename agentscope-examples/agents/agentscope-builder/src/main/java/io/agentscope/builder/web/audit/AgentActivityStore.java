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
package io.agentscope.builder.web.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-agent append-only activity log stored as JSONL inside the agent's own namespaced workspace.
 *
 * <p>Layout (relative to the agent's namespace root):
 *
 * <pre>
 *   activity/activity.jsonl              # the live log
 *   activity/activity-{timestampMs}.jsonl # rotated chunks (most recent first by name)
 * </pre>
 *
 * <p>All paths live under the {@code activity/} prefix. In remote mode, the harness composite
 * filesystem (see {@link io.agentscope.builder.web.config.BuilderConfig}) registers that path via
 * {@code RemoteFilesystemSpec.addSharedPrefix("activity/")}, so writes land on the shared {@link
 * BaseStore} and remain visible across pods. Local and sandbox modes keep the activity log in their
 * selected workspace backend.
 *
 * <p>Writes go through the per-agent {@link HarnessAgent#workspaceFor(String, String)} view, which
 * runs in the owner's namespace ({@code IsolationScope.USER}) so the audit trail is keyed to the
 * agent owner regardless of which caller triggered the event. Append is implemented as
 * read-modify-write because the abstract filesystem does not expose a streaming append primitive;
 * per-agent in-process locking keeps concurrent appends consistent on a single node.
 * {@code WorkspaceCopier} excludes {@code activity*.jsonl}, so clones start with a fresh trail.
 *
 * <p>Rotation: when {@code activity/activity.jsonl} grows beyond {@link #ROTATION_THRESHOLD_BYTES}
 * the next write copies it to {@code activity/activity-{ts}.jsonl} (the abstract filesystem does
 * not expose {@code rename}/{@code move}) before opening a new live file.
 *
 * <p>Reads are returned newest-first; rotated chunks are merged in lexicographic order (most
 * recent rotation first) after the live file.
 */
@Service
public class AgentActivityStore {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ACTIVITY_DIR = "activity";
    static final String LIVE_FILE = ACTIVITY_DIR + "/activity.jsonl";
    static final String ROTATED_PREFIX = ACTIVITY_DIR + "/activity-";
    static final String ROTATED_SUFFIX = ".jsonl";

    /** Roll the live log when it crosses this size. */
    static final int ROTATION_THRESHOLD_BYTES = 1024 * 1024; // 1 MiB

    /** Hard cap on a single read response so a log explosion can't blow up the UI. */
    static final int MAX_RETURN = 500;

    private final AgentCatalogService catalogService;
    private final UserStore userStore;

    /** One lock per (ownerId, agentId) keeps in-process appends consistent. */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AgentActivityStore(AgentCatalogService catalogService, UserStore userStore) {
        this.catalogService = catalogService;
        this.userStore = userStore;
    }

    /**
     * Appends a single event to {@code (ownerId, agentId)}'s log. Best-effort: persistence errors
     * are logged but never propagated, so a failing audit log cannot break the user-facing call.
     */
    public void record(String ownerId, String agentId, ActorRef actor, String action) {
        record(ownerId, agentId, actor, action, null, null);
    }

    /**
     * Appends a single event with an optional target and metadata. See
     * {@link #record(String, String, ActorRef, String)} for failure semantics.
     */
    public void record(
            String ownerId,
            String agentId,
            ActorRef actor,
            String action,
            String target,
            Map<String, Object> metadata) {
        if (ownerId == null || agentId == null || action == null) {
            return;
        }
        ActivityEvent event =
                new ActivityEvent(
                        newId(),
                        System.currentTimeMillis(),
                        actor != null ? actor.userId() : null,
                        actor != null ? actor.username() : null,
                        action,
                        target,
                        metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata));
        ReentrantLock lock = lockFor(ownerId, agentId);
        lock.lock();
        try {
            appendLocked(ownerId, agentId, event);
        } catch (RuntimeException ex) {
            log.warn("Activity log write failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the {@code limit} most-recent events for {@code (ownerId, agentId)}, optionally
     * filtered to events strictly newer than {@code sinceMs}. Returned newest-first.
     */
    public List<ActivityEvent> list(String ownerId, String agentId, Long sinceMs, int limit) {
        int cap = Math.min(limit > 0 ? limit : 50, MAX_RETURN);
        ReentrantLock lock = lockFor(ownerId, agentId);
        lock.lock();
        try {
            return readNewestFirst(ownerId, agentId, sinceMs, cap);
        } catch (RuntimeException ex) {
            log.warn("Activity log read failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /** Resolve a user's display name once; tolerant of unknown ids (returns the id). */
    public ActorRef actor(String userId) {
        if (userId == null) return new ActorRef(null, null);
        Optional<UserStore.UserRecord> rec = userStore.findById(userId);
        return new ActorRef(userId, rec.map(UserStore.UserRecord::username).orElse(userId));
    }

    // -----------------------------------------------------------------
    //  Append path
    // -----------------------------------------------------------------

    private void appendLocked(String ownerId, String agentId, ActivityEvent event) {
        Optional<AbstractFilesystem> fsOpt = scopedFs(ownerId, agentId);
        if (fsOpt.isEmpty()) {
            return; // agent no longer exists; drop the event silently
        }
        AbstractFilesystem fs = fsOpt.get();
        String line;
        try {
            line = MAPPER.writeValueAsString(event) + "\n";
        } catch (Exception e) {
            throw new RuntimeException("activity event serialization failed", e);
        }
        String existing = readUtf8(fs, LIVE_FILE).orElse("");
        if (existing.length() >= ROTATION_THRESHOLD_BYTES) {
            rotateLocked(fs);
            existing = "";
        }
        String combined = existing + line;
        fs.uploadFiles(
                null,
                List.of(
                        new AbstractMap.SimpleEntry<>(
                                LIVE_FILE, combined.getBytes(StandardCharsets.UTF_8))));
    }

    private void rotateLocked(AbstractFilesystem fs) {
        Optional<String> body = readUtf8(fs, LIVE_FILE);
        if (body.isEmpty() || body.get().isEmpty()) {
            return;
        }
        String name = ROTATED_PREFIX + System.currentTimeMillis() + ROTATED_SUFFIX;
        fs.uploadFiles(
                null,
                List.of(
                        new AbstractMap.SimpleEntry<>(
                                name, body.get().getBytes(StandardCharsets.UTF_8))));
        // Live file will be overwritten by the next write; explicit delete keeps reads consistent
        // if the next append is delayed.
        try {
            fs.delete(null, "/" + LIVE_FILE);
        } catch (RuntimeException ignored) {
            // tolerated; the next uploadFiles will overwrite
        }
    }

    // -----------------------------------------------------------------
    //  Read path
    // -----------------------------------------------------------------

    private List<ActivityEvent> readNewestFirst(
            String ownerId, String agentId, Long sinceMs, int cap) {
        Optional<AbstractFilesystem> fsOpt = scopedFs(ownerId, agentId);
        if (fsOpt.isEmpty()) {
            return List.of();
        }
        AbstractFilesystem fs = fsOpt.get();

        List<String> files = new ArrayList<>();
        files.add(LIVE_FILE);

        GlobResult glob = fs.glob(null, ROTATED_PREFIX + "*" + ROTATED_SUFFIX, null);
        if (glob != null && glob.isSuccess() && glob.matches() != null) {
            List<String> rotated = new ArrayList<>();
            for (FileInfo info : glob.matches()) {
                if (info.isDirectory()) continue;
                String absPath = info.path();
                int slash = absPath.lastIndexOf('/');
                String leaf = slash >= 0 ? absPath.substring(slash + 1) : absPath;
                String rel = ACTIVITY_DIR + "/" + leaf;
                if (leaf.startsWith("activity-") && leaf.endsWith(ROTATED_SUFFIX)) {
                    rotated.add(rel);
                }
            }
            // Most recent rotation first.
            rotated.sort(Comparator.reverseOrder());
            files.addAll(rotated);
        }

        List<ActivityEvent> out = new ArrayList<>();
        for (String name : files) {
            Optional<String> body = readUtf8(fs, name);
            if (body.isEmpty()) continue;
            List<ActivityEvent> parsed = parseLines(body.get());
            // newest at end of file; iterate in reverse to keep newest-first ordering.
            for (int i = parsed.size() - 1; i >= 0; i--) {
                ActivityEvent ev = parsed.get(i);
                if (sinceMs != null && ev.timestampMs() <= sinceMs) {
                    continue;
                }
                out.add(ev);
                if (out.size() >= cap) {
                    return out;
                }
            }
        }
        return out;
    }

    private static List<ActivityEvent> parseLines(String body) {
        List<ActivityEvent> events = new ArrayList<>();
        if (body == null || body.isEmpty()) return events;
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            try {
                events.add(MAPPER.readValue(line, ActivityEvent.class));
            } catch (Exception ex) {
                // tolerate corrupted line
            }
        }
        return events;
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Returns the owner-scoped filesystem view for {@code (ownerId, agentId)}. Activity events
     * are owner-keyed (not caller-keyed) so the audit trail follows the agent rather than
     * fragmenting across every user who interacted with it.
     */
    private Optional<AbstractFilesystem> scopedFs(String ownerId, String agentId) {
        HarnessAgent agent = catalogService.getOrInstantiateRunningAgent(ownerId, agentId);
        if (agent == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(agent.workspaceFor(ownerId, null).getFilesystem());
    }

    private static Optional<String> readUtf8(AbstractFilesystem fs, String path) {
        ReadResult r = fs.read(null, path, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return Optional.empty();
        String content = r.fileData().content();
        return Optional.of(content == null ? "" : content);
    }

    private ReentrantLock lockFor(String ownerId, String agentId) {
        return locks.computeIfAbsent(ownerId + "/" + agentId, k -> new ReentrantLock());
    }

    private static String newId() {
        byte[] buf = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** Identifying tuple for the actor who triggered an event. Both fields may be {@code null}. */
    public record ActorRef(String userId, String username) {}
}
