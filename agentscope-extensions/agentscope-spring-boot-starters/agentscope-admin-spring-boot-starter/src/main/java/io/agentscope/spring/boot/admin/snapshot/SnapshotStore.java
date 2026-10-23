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
package io.agentscope.spring.boot.admin.snapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory undo/redo stacks per session.
 *
 * <p>Stacks store the serialized JSON form of {@link io.agentscope.core.state.AgentState} —
 * deserialization happens at restore time. A bounded per-session capacity (default 20) caps
 * memory use; the oldest snapshot is dropped when the limit is hit.
 *
 * <p>This is a deliberately simple, single-node store. Cluster-aware deployments should swap in
 * a Redis-backed implementation by exposing a custom {@code SnapshotStore} bean.
 */
public final class SnapshotStore {

    /** Default per-session undo depth. */
    public static final int DEFAULT_CAPACITY = 20;

    private final int capacityPerSession;
    private final ConcurrentMap<String, SessionStacks> sessions = new ConcurrentHashMap<>();

    public SnapshotStore() {
        this(DEFAULT_CAPACITY);
    }

    public SnapshotStore(int capacityPerSession) {
        this.capacityPerSession = Math.max(1, capacityPerSession);
    }

    /** Push a new snapshot for {@code sessionId}. Clears the redo stack — this is a fresh edit. */
    public synchronized void push(String sessionId, String json) {
        if (sessionId == null || json == null) return;
        SessionStacks s = sessions.computeIfAbsent(sessionId, k -> new SessionStacks());
        s.undo.push(json);
        s.redo.clear();
        while (s.undo.size() > capacityPerSession) {
            s.undo.removeLast();
        }
    }

    /**
     * Pop the most recent undo snapshot, and atomically push {@code currentJson} onto the redo
     * stack so a subsequent {@link #redo(String, String)} can restore it.
     *
     * @return the JSON of the snapshot to restore, or empty if none available
     */
    public synchronized Optional<String> undo(String sessionId, String currentJson) {
        SessionStacks s = sessions.get(sessionId);
        if (s == null || s.undo.isEmpty()) {
            return Optional.empty();
        }
        String snap = s.undo.pop();
        if (currentJson != null) {
            s.redo.push(currentJson);
            while (s.redo.size() > capacityPerSession) {
                s.redo.removeLast();
            }
        }
        return Optional.of(snap);
    }

    /**
     * Pop the most recent redo snapshot, and push {@code currentJson} onto the undo stack so a
     * subsequent {@link #undo(String, String)} can revert it.
     */
    public synchronized Optional<String> redo(String sessionId, String currentJson) {
        SessionStacks s = sessions.get(sessionId);
        if (s == null || s.redo.isEmpty()) {
            return Optional.empty();
        }
        String snap = s.redo.pop();
        if (currentJson != null) {
            s.undo.push(currentJson);
            while (s.undo.size() > capacityPerSession) {
                s.undo.removeLast();
            }
        }
        return Optional.of(snap);
    }

    public synchronized int undoDepth(String sessionId) {
        SessionStacks s = sessions.get(sessionId);
        return s == null ? 0 : s.undo.size();
    }

    public synchronized int redoDepth(String sessionId) {
        SessionStacks s = sessions.get(sessionId);
        return s == null ? 0 : s.redo.size();
    }

    public synchronized void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    private static final class SessionStacks {
        final Deque<String> undo = new ArrayDeque<>();
        final Deque<String> redo = new ArrayDeque<>();
    }
}
