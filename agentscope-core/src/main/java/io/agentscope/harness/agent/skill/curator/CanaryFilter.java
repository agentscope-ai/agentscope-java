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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.legacy.skill.AgentSkill;
import java.nio.charset.StandardCharsets;

/**
 * Percentage-based rollout for agent-authored skills. Hashes {@code userId × skillName}, and
 * admits the skill iff {@code hash mod 100 < percent}.
 *
 * <p>Only applies to agent-authored skills (via {@link AbstractAgentCreatedFilter}). Hand-
 * authored / hub-installed skills always pass through.
 *
 * <p>The {@code rampUpDays} parameter is currently a placeholder — accepted on construction
 * but the M4 implementation does not yet ramp; it always uses the configured static
 * percentage. Future milestones will interpolate using {@code SkillUsageRecord.promotedAt}.
 */
public class CanaryFilter extends AbstractAgentCreatedFilter {

    private final int percent;
    private final int rampUpDays;

    public CanaryFilter(int percent, SkillUsageStore usageStore) {
        this(percent, 0, usageStore);
    }

    public CanaryFilter(int percent, int rampUpDays, SkillUsageStore usageStore) {
        super(usageStore);
        this.percent = clamp(percent);
        this.rampUpDays = Math.max(0, rampUpDays);
    }

    private static int clamp(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key =
                (ctx != null && ctx.getUserId() != null ? ctx.getUserId() : "anonymous")
                        + "|"
                        + skill.getName();
        int bucket = stableBucket(key, 100);
        return bucket < percent;
    }

    /** Stable hash → bucket. Uses Java's String.hashCode so behavior is deterministic. */
    static int stableBucket(String key, int modulus) {
        // Avoid signed-overflow surprises; use absolute value of a 32-bit rolling hash.
        int h = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + (b & 0xFF);
        }
        if (h < 0) {
            h = -(h + 1); // Math.abs(Integer.MIN_VALUE) edge case
        }
        return h % modulus;
    }

    public int getRampUpDays() {
        return rampUpDays;
    }
}
