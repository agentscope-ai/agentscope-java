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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sidecar telemetry store for per-skill usage and provenance.
 *
 * <p>Persists {@link SkillUsageRecord} instances (one per skill). The default backend stores all
 * records in a single JSON file at {@code <workspace>/skills/.usage.json}; when constructed with
 * a {@link BaseStore}, each skill is stored under {@code ["skills", "usage"]} with CAS updates.
 *
 * <p><b>Provenance gate</b>: counter mutators (bumpView/bumpUse/bumpPatch) silently skip skills
 * that are not agent-created. This avoids polluting telemetry for bundled / hub-installed /
 * user-authored skills, matching the hermes-agent {@code skill_usage._mutate} behavior.
 */
public class SkillUsageStore {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageStore.class);

    /** Default workspace-relative path for the filesystem sidecar. */
    public static final String DEFAULT_RELATIVE_PATH = "skills/.usage.json";

    private final SkillUsageBackend backend;

    public SkillUsageStore(AbstractFilesystem filesystem) {
        this(filesystem, DEFAULT_RELATIVE_PATH);
    }

    public SkillUsageStore(AbstractFilesystem filesystem, String relativePath) {
        this(new FilesystemSkillUsageBackend(filesystem, relativePath));
    }

    /** Creates a store backed by a distributed {@link BaseStore} (one key per skill). */
    public static SkillUsageStore baseStore(BaseStore store) {
        return new SkillUsageStore(new BaseStoreSkillUsageBackend(store));
    }

    SkillUsageStore(SkillUsageBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    // ---------------------------------------------------------------------
    //  Read / write
    // ---------------------------------------------------------------------

    /** Load the entire sidecar map. Returns an empty map on missing / unreadable / corrupt. */
    public Map<String, SkillUsageRecord> load() {
        return backend.loadAll();
    }

    /** Persist the entire sidecar map. Best-effort on failure. */
    public void save(Map<String, SkillUsageRecord> data) {
        backend.replaceAll(data != null ? data : Map.of());
    }

    /** Read a single record by name. */
    public Optional<SkillUsageRecord> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return backend.get(name);
    }

    /**
     * Apply {@code mutator} to the record for {@code name}. Creates a fresh
     * {@link SkillUsageRecord#defaults()} record if none exists. Concurrency is owned by the
     * backend (in-process lock or CAS).
     */
    private void mutate(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            backend.mutate(name, mutator);
        } catch (Exception e) {
            log.debug("SkillUsageStore.mutate({}) failed: {}", name, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    //  Counter bumps (provenance-gated: skip skills NOT created by the agent)
    // ---------------------------------------------------------------------

    public void bumpView(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount() + 1,
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                Instant.now(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpUse(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount() + 1,
                                rec.viewCount(),
                                rec.patchCount(),
                                Instant.now(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpPatch(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount() + 1,
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                Instant.now(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /**
     * Apply {@code mutator} only if a record for {@code name} exists with non-null {@code
     * createdBy} (i.e. the agent has explicitly tracked this skill). Skipping unknown / external
     * skills keeps the sidecar focused on agent-authored procedural memory.
     *
     * <p>The provenance check runs inside the backend mutator so CAS / locking covers the full
     * read-modify-write (no TOCTOU between a separate get and mutate).
     */
    private void bumpIfAgentTracked(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            backend.mutate(
                    name,
                    rec -> {
                        if (rec.createdBy() == null) {
                            return rec; // same reference → backend no-op
                        }
                        return mutator.apply(rec);
                    });
        } catch (Exception e) {
            log.debug("SkillUsageStore.bumpIfAgentTracked({}) failed: {}", name, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    //  Provenance / lifecycle setters (always allowed)
    // ---------------------------------------------------------------------

    /** Tag a freshly-created agent draft (called from {@code SkillManageTool.create}). */
    public void markAgentDraft(String name, String sessionId) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                "agent-draft",
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                                State.DRAFT,
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                sessionId,
                                List.of("draft")));
    }

    /**
     * Mark a skill as a fully agent-created (i.e. promoted from draft). Used by
     * {@code SkillManageTool} when {@code autoPromote=true} (no staging) and by the future
     * promotion gate.
     */
    public void markAgentCreated(String name, String reviewerId, List<String> environments) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                "agent",
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                                State.ACTIVE,
                                rec.pinned(),
                                rec.archivedAt(),
                                Instant.now(),
                                reviewerId,
                                rec.sourceSessionId(),
                                environments != null ? List.copyOf(environments) : List.of()));
    }

    public void setState(String name, State newState) {
        if (newState == null) {
            return;
        }
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                newState,
                                rec.pinned(),
                                newState == State.ARCHIVED ? Instant.now() : null,
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void setPinned(String name, boolean pinned) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                pinned,
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /** Drop the record entirely. Called when a skill is archived / deleted permanently. */
    public void forget(String name) {
        mutate(name, rec -> null);
    }

    // ---------------------------------------------------------------------
    //  Read views
    // ---------------------------------------------------------------------

    /** All records whose {@code createdBy} is non-null (i.e. agent-authored, draft or live). */
    public List<Map.Entry<String, SkillUsageRecord>> agentCreatedReport() {
        Map<String, SkillUsageRecord> all = load();
        List<Map.Entry<String, SkillUsageRecord>> out = new ArrayList<>();
        for (Map.Entry<String, SkillUsageRecord> e : all.entrySet()) {
            if (e.getValue() != null && e.getValue().createdBy() != null) {
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
