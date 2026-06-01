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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillUsageStoreTest {

    @TempDir Path workspace;
    private SkillUsageStore store;

    @BeforeEach
    void setUp() {
        store = new SkillUsageStore(new LocalFilesystem(workspace));
    }

    @Test
    void emptyStoreLoadsAsEmptyMap() {
        Map<String, SkillUsageRecord> data = store.load();
        assertNotNull(data);
        assertTrue(data.isEmpty());
    }

    @Test
    void markAgentDraft_createsRecordWithDraftProvenance() {
        store.markAgentDraft("csv-sum", "session-42");

        SkillUsageRecord rec = store.get("csv-sum").orElseThrow();
        assertEquals("agent-draft", rec.createdBy());
        assertEquals(State.DRAFT, rec.state());
        assertEquals("session-42", rec.sourceSessionId());
        assertEquals(List.of("draft"), rec.environments());
        assertNotNull(rec.createdAt());
    }

    @Test
    void markAgentCreated_promotesAndStampsReviewer() {
        store.markAgentDraft("evolve", "session-1");
        store.markAgentCreated("evolve", "alice@example.com", List.of("prod"));

        SkillUsageRecord rec = store.get("evolve").orElseThrow();
        assertEquals("agent", rec.createdBy());
        assertEquals(State.ACTIVE, rec.state());
        assertEquals("alice@example.com", rec.promotedBy());
        assertNotNull(rec.promotedAt());
        assertEquals(List.of("prod"), rec.environments());
        // sourceSessionId from the original draft should survive the promotion.
        assertEquals("session-1", rec.sourceSessionId());
    }

    @Test
    void bumpView_skipsRecordsWithoutAgentProvenance() {
        // Manually plant a record with createdBy=null (simulating an external skill that the
        // agent has somehow seen — should not be tracked).
        store.save(Map.of("external-skill", SkillUsageRecord.defaults()));
        store.bumpView("external-skill");
        SkillUsageRecord rec = store.get("external-skill").orElseThrow();
        assertEquals(0, rec.viewCount(), "External skills must NOT accumulate telemetry");
    }

    @Test
    void bumpView_recordsForAgentDraft() {
        store.markAgentDraft("draft-x", "s");
        store.bumpView("draft-x");
        store.bumpView("draft-x");
        SkillUsageRecord rec = store.get("draft-x").orElseThrow();
        assertEquals(2, rec.viewCount());
        assertNotNull(rec.lastViewedAt());
    }

    @Test
    void bumpPatchAndUseAccumulateIndependently() {
        store.markAgentDraft("multi", null);
        store.bumpUse("multi");
        store.bumpUse("multi");
        store.bumpPatch("multi");
        SkillUsageRecord rec = store.get("multi").orElseThrow();
        assertEquals(2, rec.useCount());
        assertEquals(1, rec.patchCount());
        assertEquals(0, rec.viewCount());
        assertEquals(3, rec.activityCount());
    }

    @Test
    void setState_andSetPinned() {
        store.markAgentDraft("life", null);
        store.setState("life", State.STALE);
        assertEquals(State.STALE, store.get("life").orElseThrow().state());

        store.setState("life", State.ARCHIVED);
        SkillUsageRecord arch = store.get("life").orElseThrow();
        assertEquals(State.ARCHIVED, arch.state());
        assertNotNull(arch.archivedAt());

        store.setPinned("life", true);
        assertTrue(store.get("life").orElseThrow().pinned());
        store.setPinned("life", false);
        assertFalse(store.get("life").orElseThrow().pinned());
    }

    @Test
    void forget_removesRecord() {
        store.markAgentDraft("ephemeral", null);
        assertTrue(store.get("ephemeral").isPresent());
        store.forget("ephemeral");
        assertTrue(store.get("ephemeral").isEmpty());
    }

    @Test
    void agentCreatedReport_filtersOutNonAgentRecords() {
        store.markAgentDraft("d1", null);
        store.markAgentDraft("d2", null);
        store.markAgentCreated("p1", "auto", List.of("prod"));
        // External skill — must be excluded
        store.save(
                Map.of(
                        "external", SkillUsageRecord.defaults(),
                        "d1", SkillUsageRecord.newAgentDraft("s"),
                        "d2", SkillUsageRecord.newAgentDraft("s"),
                        "p1",
                                new SkillUsageRecord(
                                        "agent",
                                        0,
                                        0,
                                        0,
                                        null,
                                        null,
                                        null,
                                        java.time.Instant.now(),
                                        State.ACTIVE,
                                        false,
                                        null,
                                        java.time.Instant.now(),
                                        "auto",
                                        null,
                                        List.of("prod"))));

        var report = store.agentCreatedReport();
        var names = report.stream().map(Map.Entry::getKey).toList();
        assertEquals(3, report.size(), "external skill must be filtered out");
        assertTrue(names.contains("d1"));
        assertTrue(names.contains("d2"));
        assertTrue(names.contains("p1"));
        assertFalse(names.contains("external"));
    }

    @Test
    void load_afterSave_persistsAcrossNewStoreInstance() {
        store.markAgentDraft("persist", "s1");
        store.bumpUse("persist");

        SkillUsageStore reopened = new SkillUsageStore(new LocalFilesystem(workspace));
        SkillUsageRecord rec = reopened.get("persist").orElseThrow();
        assertEquals("agent-draft", rec.createdBy());
        assertEquals(1, rec.useCount());
        assertEquals("s1", rec.sourceSessionId());
    }

    @Test
    void latestActivityAt_returnsMaxOfThreeTimestamps() {
        store.markAgentDraft("hot", null);
        store.bumpView("hot"); // populates lastViewedAt
        SkillUsageRecord rec1 = store.get("hot").orElseThrow();
        assertNotNull(rec1.latestActivityAt());

        // Mutate by-hand to give clearly-ordered timestamps via setState (doesn't touch
        // last_*_at). Ensure latest still picks lastViewedAt.
        SkillUsageRecord rec = store.get("hot").orElseThrow();
        assertEquals(rec.lastViewedAt(), rec.latestActivityAt());

        // Empty case: never-bumped agent draft has no latest activity.
        store.markAgentDraft("cold", null);
        assertNull(store.get("cold").orElseThrow().latestActivityAt());
    }
}
