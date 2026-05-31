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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.skill.WritableFilesystemSkillRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background skill maintenance orchestrator. Mirrors hermes-agent's {@code agent/curator.py}
 * lifecycle:
 * <ul>
 *   <li>Phase 1 — pure-function {@code applyAutomaticTransitions}: walk every agent-tracked
 *       skill, transition {@code ACTIVE → STALE → ARCHIVED} based on the latest activity
 *       timestamp from the sidecar. Pinned + DRAFT skills are skipped.</li>
 *   <li>Phase 2 — LLM umbrella pass: produces a markdown report of consolidation candidates;
 *       in {@code DRY_RUN_ONLY} mode (default) it does not call {@code skill_manage}, just
 *       writes the report. {@code LIVE} mode is reserved for a future milestone (it requires
 *       an auxiliary {@code Model} + a forked {@code ReActAgent}; the M5 implementation only
 *       exposes the dry-run report-generation path).</li>
 * </ul>
 *
 * <p>Strict invariants:
 * <ul>
 *   <li>Only touches sidecar entries with {@code createdBy != null} (agent-authored skills).</li>
 *   <li>Pinned skills bypass every auto-transition.</li>
 *   <li>Never deletes — only archives.</li>
 *   <li>{@code DRAFT} state is owned by the promotion gate, not the curator.</li>
 * </ul>
 */
public class SkillCurator {

    private static final Logger log = LoggerFactory.getLogger(SkillCurator.class);

    public static final String STATE_PATH = "skills/.curator_state.json";
    public static final String REPORTS_DIR = "skills/.curator_reports";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AbstractFilesystem filesystem;
    private final SkillUsageStore usageStore;
    private final WritableFilesystemSkillRepository mainRepo;
    private final SkillCuratorConfig config;

    public SkillCurator(
            AbstractFilesystem filesystem,
            SkillUsageStore usageStore,
            WritableFilesystemSkillRepository mainRepo,
            SkillCuratorConfig config) {
        this.filesystem = java.util.Objects.requireNonNull(filesystem, "filesystem");
        this.usageStore = java.util.Objects.requireNonNull(usageStore, "usageStore");
        this.mainRepo = java.util.Objects.requireNonNull(mainRepo, "mainRepo");
        this.config = config != null ? config : SkillCuratorConfig.defaults();
    }

    public SkillCuratorConfig config() {
        return config;
    }

    // ---------------------------------------------------------------------
    //  State sidecar
    // ---------------------------------------------------------------------

    public SkillCuratorState loadState() {
        try {
            var rr = filesystem.read(RuntimeContext.empty(), STATE_PATH, 0, 0);
            if (rr.isSuccess() && rr.fileData() != null && rr.fileData().content() != null) {
                String body = rr.fileData().content();
                if (!body.isBlank()) {
                    return JSON.readValue(body, SkillCuratorState.class);
                }
            }
        } catch (Exception e) {
            log.debug("loadState() failed: {}", e.getMessage());
        }
        return SkillCuratorState.defaults();
    }

    public void saveState(SkillCuratorState state) {
        try {
            String json = JSON.writeValueAsString(state);
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    STATE_PATH, json.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("saveState() failed: {}", e.getMessage());
        }
    }

    public boolean isPaused() {
        return loadState().paused();
    }

    public void setPaused(boolean paused) {
        SkillCuratorState s = loadState();
        saveState(
                new SkillCuratorState(
                        s.lastRunAt(),
                        s.runCount(),
                        paused,
                        s.lastRunSummary(),
                        s.lastRunDurationSeconds(),
                        s.lastReportPath()));
    }

    /**
     * Idle-and-interval gate. Returns true iff the curator should fire now: enabled, not
     * paused, last_run_at older than {@code intervalHours}. First-run behaviour seeds
     * last_run_at without firing — a fresh deployment doesn't get LLM'd until at least one
     * interval has elapsed.
     */
    public boolean shouldRunNow(Instant now) {
        if (!config.enabled() || isPaused()) {
            return false;
        }
        SkillCuratorState s = loadState();
        if (s.lastRunAt() == null) {
            // Seed and defer.
            saveState(
                    new SkillCuratorState(
                            now,
                            s.runCount(),
                            s.paused(),
                            "deferred first run — curator seeded",
                            null,
                            null));
            return false;
        }
        Duration sinceLast = Duration.between(s.lastRunAt(), now);
        return sinceLast.toHours() >= config.intervalHours();
    }

    // ---------------------------------------------------------------------
    //  Phase 1 — pure-function transitions
    // ---------------------------------------------------------------------

    public record TransitionCounts(int checked, int markedStale, int archived, int reactivated) {}

    public TransitionCounts applyAutomaticTransitions(Instant now) {
        if (now == null) {
            now = Instant.now();
        }
        Instant staleCutoff = now.minus(config.staleAfterDays(), ChronoUnit.DAYS);
        Instant archiveCutoff = now.minus(config.archiveAfterDays(), ChronoUnit.DAYS);

        int checked = 0;
        int markedStale = 0;
        int archived = 0;
        int reactivated = 0;

        for (var entry : usageStore.agentCreatedReport()) {
            String name = entry.getKey();
            SkillUsageRecord rec = entry.getValue();
            if (rec == null) {
                continue;
            }
            // Only auto-manage promoted (createdBy=="agent") skills. Drafts belong to the
            // promotion gate.
            if (!"agent".equals(rec.createdBy()) || rec.pinned()) {
                continue;
            }
            checked++;

            Instant anchor = rec.latestActivityAt();
            if (anchor == null) {
                anchor = rec.createdAt() != null ? rec.createdAt() : now;
            }
            SkillUsageRecord.State current = rec.state();

            if (anchor.isBefore(archiveCutoff)
                    && current != SkillUsageRecord.State.ARCHIVED) {
                // Move to archive (mainRepo.delete archives non-destructively).
                try {
                    mainRepo.delete(name);
                } catch (Exception e) {
                    log.debug("archive {} failed: {}", name, e.getMessage());
                }
                usageStore.setState(name, SkillUsageRecord.State.ARCHIVED);
                archived++;
            } else if (anchor.isBefore(staleCutoff)
                    && current == SkillUsageRecord.State.ACTIVE) {
                usageStore.setState(name, SkillUsageRecord.State.STALE);
                markedStale++;
            } else if (anchor.isAfter(staleCutoff)
                    && current == SkillUsageRecord.State.STALE) {
                usageStore.setState(name, SkillUsageRecord.State.ACTIVE);
                reactivated++;
            }
        }
        return new TransitionCounts(checked, markedStale, archived, reactivated);
    }

    // ---------------------------------------------------------------------
    //  Phase 2 — umbrella pass (M5: dry-run report only)
    // ---------------------------------------------------------------------

    /**
     * Generate a human-readable markdown report listing every agent-created skill grouped by
     * common name prefix. This is the M5 stub for the LLM-powered umbrella consolidation pass:
     * the prompt + LLM call require an auxiliary model and are deferred to a future milestone.
     * In {@code DRY_RUN_ONLY} or {@code LIVE} mode this method writes a report to
     * {@code skills/.curator_reports/&lt;ts&gt;/REPORT.md}; in {@code DISABLED} mode it returns
     * {@code null} without writing.
     *
     * @return path of the report relative to the workspace, or {@code null} when disabled
     */
    public String runUmbrellaDryRunReport(Instant now) {
        if (config.umbrellaPassMode() == SkillCuratorConfig.UmbrellaPassMode.DISABLED) {
            return null;
        }
        if (now == null) {
            now = Instant.now();
        }
        String ts =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(now);
        String reportPath = REPORTS_DIR + "/" + ts + "/REPORT.md";

        // Cluster skills by first dash-separated segment (cheap heuristic, matches hermes
        // umbrella prompt's "PREFIX CLUSTERS" guidance).
        Map<String, java.util.List<String>> clusters = new LinkedHashMap<>();
        for (var entry : usageStore.agentCreatedReport()) {
            SkillUsageRecord rec = entry.getValue();
            if (!"agent".equals(rec.createdBy()) || rec.pinned()) {
                continue;
            }
            String name = entry.getKey();
            String prefix = name.split("[-_.]", 2)[0];
            clusters.computeIfAbsent(prefix, k -> new java.util.ArrayList<>()).add(name);
        }

        StringBuilder report = new StringBuilder();
        report.append("# Skill Curator Dry-Run Report\n\n");
        report.append("Generated: ").append(now).append("\n");
        report.append("Mode: ").append(config.umbrellaPassMode()).append("\n\n");

        int multiMemberClusters = 0;
        report.append("## Prefix clusters\n\n");
        for (var c : clusters.entrySet()) {
            if (c.getValue().size() < 2) {
                continue;
            }
            multiMemberClusters++;
            report.append("- **")
                    .append(c.getKey())
                    .append("-*** (")
                    .append(c.getValue().size())
                    .append(" members): ")
                    .append(String.join(", ", c.getValue()))
                    .append("\n");
        }
        if (multiMemberClusters == 0) {
            report.append("(no clusters of 2+ skills found)\n");
        }
        report.append("\n## Structured summary (required)\n\n```yaml\n");
        report.append("consolidations: []\n");
        report.append("prunings: []\n");
        report.append("```\n");

        try {
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    reportPath,
                                    report.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("Failed to write curator report: {}", e.getMessage());
            return null;
        }
        return reportPath;
    }

    // ---------------------------------------------------------------------
    //  Combined entry point
    // ---------------------------------------------------------------------

    public record CuratorRunReport(
            TransitionCounts transitions,
            String dryRunReportPath,
            Instant ranAt,
            long durationMs) {}

    /** Run a full curator pass: phase-1 transitions + phase-2 (dry-run) report. */
    public CuratorRunReport runOnce(Instant now) {
        long startNanos = System.nanoTime();
        Instant ranAt = now != null ? now : Instant.now();
        TransitionCounts counts = applyAutomaticTransitions(ranAt);
        String reportPath = runUmbrellaDryRunReport(ranAt);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // Persist run state.
        SkillCuratorState s = loadState();
        String summary =
                String.format(
                        "checked=%d markedStale=%d archived=%d reactivated=%d",
                        counts.checked(),
                        counts.markedStale(),
                        counts.archived(),
                        counts.reactivated());
        saveState(
                new SkillCuratorState(
                        ranAt,
                        s.runCount() + 1,
                        s.paused(),
                        summary,
                        durationMs / 1000,
                        reportPath));

        return new CuratorRunReport(counts, reportPath, ranAt, durationMs);
    }
}
