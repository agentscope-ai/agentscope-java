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
package io.agentscope.core.skill.mirror;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionEventCodecRegistry;
import io.agentscope.core.session.SessionRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Persists skill version anchors on the {@link SessionRecorder} event journal: which version
 * a {@code SkillVersionPolicy.PIN} session is bound to, restored when the session resumes.
 * The anchor is a journal event, so it survives eviction of in-memory session state. Event
 * semantics:
 *
 * <ul>
 *   <li>{@code skill/version-anchored} with {@code reason=INITIAL} — anchoring at session
 *       establishment; <b>first writer wins</b>: re-anchoring an already anchored skill is a
 *       no-op, so concurrent session builders converge on one version.
 *   <li>{@code skill/version-anchored} with {@code reason=FALLBACK} — the only re-anchor: an
 *       unreachable pinned version fell back to the current latest, which the session now
 *       pins; the abandoned version is carried as {@code fromVersion} for audit.
 *   <li>{@code skill/version-refreshed} — audit-only record of a LATEST version advance.
 * </ul>
 *
 * <p>All methods tolerate a {@code null} recorder (no session journal configured): nothing
 * is persisted and anchors cannot be recovered after a restart.
 *
 * @author shanhongyu
 */
public final class SkillAnchorService {

    /** Anchor/fallback event type on the session journal. */
    public static final String EVENT_TYPE = "skill/version-anchored";

    /** Refresh audit event type on the session journal. */
    public static final String REFRESH_EVENT_TYPE = "skill/version-refreshed";

    /**
     * Process-wide per-skill locks serializing INITIAL anchoring's check-then-append within
     * one process; the journal itself offers no compare-and-append for cross-process races.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> ANCHOR_LOCKS =
            new ConcurrentHashMap<>();

    /** An anchored version: the skill, its pinned version, the mode and the anchor time. */
    public record AnchoredVersion(String skillId, String version, String mode, long anchoredAt) {}

    // Registers the anchor and refresh event types with validation-only codecs: the events
    // carry no session-state side effects, replay simply exposes them to findAnchor.
    static {
        SessionEventCodecRegistry registry = SessionEventCodecRegistry.defaultRegistry();
        registry.register(
                EVENT_TYPE,
                new SessionEventCodecRegistry.Codec(
                        data -> {
                            Objects.requireNonNull(data.get("skillId"), "skillId");
                            Objects.requireNonNull(data.get("version"), "version");
                            Objects.requireNonNull(data.get("mode"), "mode");
                            Objects.requireNonNull(data.get("reason"), "reason");
                        },
                        (state, data) -> {}));
        registry.register(
                REFRESH_EVENT_TYPE,
                new SessionEventCodecRegistry.Codec(
                        data -> Objects.requireNonNull(data.get("skillId"), "skillId"),
                        (state, data) -> {}));
    }

    /** Utility class; no instances. */
    private SkillAnchorService() {}

    /**
     * Anchors {@code skillId} at {@code version} (INITIAL) unless already anchored — first
     * writer wins, so every caller of a race ends up with the same effective anchor. The
     * check-then-append is serialized process-wide per skill; across processes the journal
     * has no compare-and-append, so two racers can both append and the journal's later
     * append is the effective anchor.
     *
     * @param recorder the session journal recorder; {@code null} disables persistence
     * @param skillId the composite skill id (name_source)
     * @param version the version to anchor (never null)
     * @param mode pin or latest
     * @return the effective anchor after this call, possibly another racer's
     */
    public static AnchoredVersion anchorInitial(
            SessionRecorder recorder, String skillId, String version, String mode) {
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        ReentrantLock lock = ANCHOR_LOCKS.computeIfAbsent(skillId, k -> new ReentrantLock());
        lock.lock();
        try {
            AnchoredVersion existing = findAnchor(recorder, skillId).orElse(null);
            if (existing != null) {
                return existing;
            }
            long now = System.currentTimeMillis();
            appendAnchored(recorder, skillId, version, mode, "INITIAL", null, now);
            // Read the journal back instead of trusting our own write: a cross-process racer
            // may have landed a later INITIAL while we held only the process-local lock.
            return findAnchor(recorder, skillId)
                    .orElseGet(() -> new AnchoredVersion(skillId, version, mode, now));
        } finally {
            lock.unlock();
            // Best-effort removal keeps the lock set bounded by in-flight skills, not by
            // every skill ever anchored (same pattern as SkillMirrorStore's key locks). A
            // benign race — the entry vanishing while a late waiter holds the old lock —
            // costs at most a redundant journal read; first-writer-wins still converges.
            if (!lock.hasQueuedThreads()) {
                ANCHOR_LOCKS.remove(skillId, lock);
            }
        }
    }

    /**
     * Re-anchors {@code skillId} at {@code toVersion} after its pinned version became
     * unreachable (FALLBACK) — the only overwrite of an existing anchor. Guarded as a
     * compare-and-set: the append happens only while {@code fromVersion} is still the
     * effective anchor, so a stale or duplicate fallback cannot clobber a newer one.
     *
     * @param recorder the session journal recorder; {@code null} disables persistence
     * @param skillId the composite skill id (never null)
     * @param fromVersion the unreachable version (never null)
     * @param toVersion the fallback version actually materialized (never null)
     */
    public static void anchorFallback(
            SessionRecorder recorder, String skillId, String fromVersion, String toVersion) {
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(fromVersion, "fromVersion must not be null");
        Objects.requireNonNull(toVersion, "toVersion must not be null");
        if (recorder == null) {
            return;
        }
        AnchoredVersion current = findAnchor(recorder, skillId).orElse(null);
        if (current == null || !current.version().equals(fromVersion)) {
            return; // No anchor to repair, or a newer anchor already won — keep it.
        }
        appendAnchored(
                recorder,
                skillId,
                toVersion,
                "pin",
                "FALLBACK",
                fromVersion,
                System.currentTimeMillis());
    }

    /**
     * Appends a pure-audit refresh event for a LATEST version advance.
     *
     * @param recorder the session journal recorder; {@code null} is a no-op
     * @param skillId the composite skill id (never null)
     * @param fromVersion the previously served version (may be null on first observation;
     *     journaled as the empty string because the payload map rejects nulls)
     * @param toVersion the newly observed version (never null)
     */
    public static void refreshAudit(
            SessionRecorder recorder, String skillId, String fromVersion, String toVersion) {
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(toVersion, "toVersion must not be null");
        if (recorder == null) {
            return;
        }
        recorder.append(
                REFRESH_EVENT_TYPE,
                Map.of(
                        "skillId", skillId,
                        "fromVersion", fromVersion == null ? "" : fromVersion,
                        "toVersion", toVersion));
    }

    /**
     * Finds the effective anchor for {@code skillId}: the last anchored version — an INITIAL
     * anchor stays frozen unless a later FALLBACK replaced it.
     *
     * @param recorder the session journal recorder; {@code null} yields empty
     * @param skillId the composite skill id (never null)
     * @return the effective anchor, or {@code empty()} when never anchored
     */
    public static Optional<AnchoredVersion> findAnchor(SessionRecorder recorder, String skillId) {
        Objects.requireNonNull(skillId, "skillId must not be null");
        if (recorder == null) {
            return Optional.empty();
        }
        Predicate<SessionEvent> matching = event -> skillId.equals(dataOf(event).get("skillId"));
        return recorder.findLast(EVENT_TYPE, matching).map(SkillAnchorService::anchoredFrom);
    }

    /** Appends one anchor event and flushes the recorder so the anchor is immediately visible
     * to {@link #findAnchor} (first-writer-wins depends on read-your-own-write). {@code
     * fromVersion} is carried only by FALLBACK events (null omits the key). */
    private static void appendAnchored(
            SessionRecorder recorder,
            String skillId,
            String version,
            String mode,
            String reason,
            String fromVersion,
            long anchoredAt) {
        if (recorder == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skillId", skillId);
        data.put("version", version);
        data.put("mode", mode);
        data.put("reason", reason);
        data.put("anchoredAt", anchoredAt);
        if (fromVersion != null) {
            data.put("fromVersion", fromVersion);
        }
        recorder.append(EVENT_TYPE, data);
        // Anchor decisions must be immediately visible to findLast (first-writer-wins).
        recorder.flushNow();
    }

    /** Rebuilds an {@link AnchoredVersion} from a journaled anchor event's data map. */
    private static AnchoredVersion anchoredFrom(SessionEvent event) {
        Map<String, Object> data = dataOf(event);
        Object anchoredAt = data.get("anchoredAt");
        return new AnchoredVersion(
                String.valueOf(data.get("skillId")),
                String.valueOf(data.get("version")),
                String.valueOf(data.get("mode")),
                anchoredAt instanceof Number number ? number.longValue() : event.occurredAt());
    }

    /** Returns the event's data map (never null for journaled events). */
    private static Map<String, Object> dataOf(SessionEvent event) {
        return event.data();
    }
}
