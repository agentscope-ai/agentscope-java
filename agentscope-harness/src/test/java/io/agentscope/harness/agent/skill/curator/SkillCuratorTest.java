/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.skill.curator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WritableFilesystemSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class SkillCuratorTest {

    @TempDir Path workspace;

    private AbstractFilesystem fs;
    private SkillUsageStore store;
    private WritableFilesystemSkillRepository mainRepo;

    @BeforeEach
    void setUp() {
        fs = new LocalFilesystem(workspace);
        store = new SkillUsageStore(fs);
        mainRepo =
                new WritableFilesystemSkillRepository(fs, "skills", RuntimeContext::empty, "main");
    }

    private SkillCurator newCurator(int staleAfterDays, int archiveAfterDays) {
        SkillCuratorConfig cfg =
                SkillCuratorConfig.builder()
                        .staleAfterDays(staleAfterDays)
                        .archiveAfterDays(archiveAfterDays)
                        .umbrellaPassMode(SkillCuratorConfig.UmbrellaPassMode.DRY_RUN_ONLY)
                        .build();
        return new SkillCurator(fs, store, mainRepo, cfg);
    }

    /** Plant a skill record + a real SKILL.md so {@code mainRepo.delete} can move it. */
    private void plantAgent(String name, Instant lastUsedAt) {
        var skill =
                new io.agentscope.core.skill.AgentSkill(name, "desc " + name, "# " + name, null);
        assertTrue(mainRepo.save(List.of(skill), false));
        store.markAgentCreated(name, "auto", List.of("prod"));
        // Backdate the activity via load+modify+save (avoid clobbering siblings).
        var all = new java.util.LinkedHashMap<>(store.load());
        var rec = all.get(name);
        var updated =
                new SkillUsageRecord(
                        rec.createdBy(),
                        rec.useCount() + 1,
                        rec.viewCount(),
                        rec.patchCount(),
                        lastUsedAt,
                        rec.lastViewedAt(),
                        rec.lastPatchedAt(),
                        rec.createdAt(),
                        rec.state(),
                        rec.pinned(),
                        rec.archivedAt(),
                        rec.promotedAt(),
                        rec.promotedBy(),
                        rec.sourceSessionId(),
                        rec.environments());
        all.put(name, updated);
        store.save(all);
    }

    // ---- pure-function transitions ----

    @Test
    void freshSkill_staysActive() {
        plantAgent("hot", Instant.now().minus(1, ChronoUnit.DAYS));
        SkillCurator curator = newCurator(30, 90);
        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(0, counts.markedStale());
        assertEquals(0, counts.archived());
        assertEquals(SkillUsageRecord.State.ACTIVE, store.get("hot").orElseThrow().state());
    }

    @Test
    void oldSkill_marksStale_butNotArchivedYet() {
        plantAgent("warmish", Instant.now().minus(45, ChronoUnit.DAYS));
        SkillCurator curator = newCurator(30, 90);
        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(1, counts.markedStale());
        assertEquals(0, counts.archived());
        assertEquals(SkillUsageRecord.State.STALE, store.get("warmish").orElseThrow().state());
    }

    @Test
    void veryOldSkill_isArchived() {
        plantAgent("cold", Instant.now().minus(120, ChronoUnit.DAYS));
        SkillCurator curator = newCurator(30, 90);
        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(1, counts.archived());
        assertEquals(SkillUsageRecord.State.ARCHIVED, store.get("cold").orElseThrow().state());
        // Live dir gone, archived dir present.
        assertFalse(Files.exists(workspace.resolve("skills/cold")));
        assertTrue(Files.isDirectory(workspace.resolve("skills/.archive")));
    }

    @Test
    void staleSkillReactivates_whenItRecentlyUsed() {
        // First make it stale.
        plantAgent("revive", Instant.now().minus(45, ChronoUnit.DAYS));
        SkillCurator curator = newCurator(30, 90);
        curator.applyAutomaticTransitions(Instant.now());
        // Now bump lastUsedAt to recent.
        var all = new java.util.LinkedHashMap<>(store.load());
        var rec = all.get("revive");
        var updated =
                new SkillUsageRecord(
                        rec.createdBy(),
                        rec.useCount() + 1,
                        rec.viewCount(),
                        rec.patchCount(),
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        rec.lastViewedAt(),
                        rec.lastPatchedAt(),
                        rec.createdAt(),
                        rec.state(),
                        rec.pinned(),
                        rec.archivedAt(),
                        rec.promotedAt(),
                        rec.promotedBy(),
                        rec.sourceSessionId(),
                        rec.environments());
        all.put("revive", updated);
        store.save(all);

        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(1, counts.reactivated());
        assertEquals(SkillUsageRecord.State.ACTIVE, store.get("revive").orElseThrow().state());
    }

    @Test
    void pinnedSkill_neverArchived() {
        plantAgent("pinned-cold", Instant.now().minus(200, ChronoUnit.DAYS));
        store.setPinned("pinned-cold", true);
        SkillCurator curator = newCurator(30, 90);
        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(0, counts.archived());
        assertTrue(Files.exists(workspace.resolve("skills/pinned-cold/SKILL.md")));
    }

    @Test
    void draftSkill_isSkipped() {
        // Plant a draft via store only — no on-disk file under skills/, simulating an unpromoted
        // draft.
        store.markAgentDraft("draft-noop", null);
        SkillCurator curator = newCurator(30, 90);
        var counts = curator.applyAutomaticTransitions(Instant.now());
        assertEquals(0, counts.checked(), "drafts must NOT be in the candidate set");
    }

    // ---- shouldRunNow gate ----

    @Test
    void firstRun_seedsAndDefers() {
        SkillCurator curator = newCurator(30, 90);
        Instant now = Instant.now();
        assertFalse(curator.shouldRunNow(now), "first call should defer");
        var state = curator.loadState();
        assertNotNull(state.lastRunAt(), "lastRunAt must be seeded");
    }

    @Test
    void secondRun_blockedUntilIntervalElapsed() {
        SkillCurator curator = newCurator(30, 90);
        // First call seeds.
        curator.shouldRunNow(Instant.now());
        // Just after seeding — interval is 7 days default; should still be false.
        assertFalse(curator.shouldRunNow(Instant.now()));
    }

    @Test
    void runOnce_writesDryRunReport() {
        plantAgent("hermes-config-foo", Instant.now());
        plantAgent("hermes-config-bar", Instant.now());
        plantAgent("hermes-config-baz", Instant.now());
        SkillCurator curator = newCurator(30, 90);
        var report = curator.runOnce(Instant.now());
        assertNotNull(report.dryRunReportPath());
        Path reportFile = workspace.resolve(report.dryRunReportPath());
        assertTrue(Files.exists(reportFile));
        try {
            String body = Files.readString(reportFile);
            assertTrue(
                    body.contains("hermes-config-foo"),
                    "report should list the cluster members by name");
            assertTrue(
                    body.contains("hermes-***"),
                    "report should show prefix-cluster header (current heuristic"
                            + " splits on first dash)");
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assertions.fail("read report: " + e.getMessage());
        }
    }
}
