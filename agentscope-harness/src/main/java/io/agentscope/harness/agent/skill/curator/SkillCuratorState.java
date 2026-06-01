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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Persistent state for {@link SkillCurator} — kept in {@code skills/.curator_state.json}.
 * Records the last run timestamp, run count, paused flag, and a brief human-readable summary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillCuratorState(
        @JsonProperty("last_run_at") Instant lastRunAt,
        @JsonProperty("run_count") long runCount,
        @JsonProperty("paused") boolean paused,
        @JsonProperty("last_run_summary") String lastRunSummary,
        @JsonProperty("last_run_duration_seconds") Long lastRunDurationSeconds,
        @JsonProperty("last_report_path") String lastReportPath) {

    public static SkillCuratorState defaults() {
        return new SkillCuratorState(null, 0L, false, null, null, null);
    }

    @JsonCreator
    public static SkillCuratorState ofJson(
            @JsonProperty("last_run_at") Instant lastRunAt,
            @JsonProperty("run_count") Long runCount,
            @JsonProperty("paused") Boolean paused,
            @JsonProperty("last_run_summary") String lastRunSummary,
            @JsonProperty("last_run_duration_seconds") Long lastRunDurationSeconds,
            @JsonProperty("last_report_path") String lastReportPath) {
        return new SkillCuratorState(
                lastRunAt,
                runCount == null ? 0L : runCount,
                paused != null && paused,
                lastRunSummary,
                lastRunDurationSeconds,
                lastReportPath);
    }
}
