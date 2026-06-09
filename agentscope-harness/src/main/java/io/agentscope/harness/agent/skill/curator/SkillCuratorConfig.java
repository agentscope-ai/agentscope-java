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

/**
 * Configuration knobs for {@link SkillCurator}. Defaults match the conservative enterprise
 * profile from the design plan: 7-day cycle, 30/90 day stale/archive cutoffs, dry-run only
 * for the LLM umbrella pass.
 */
public final class SkillCuratorConfig {

    public enum UmbrellaPassMode {
        /** Phase-1 only: pure-function active/stale/archive transitions. */
        DISABLED,
        /** Run the LLM umbrella pass but never invoke skill_manage; emit a report only. */
        DRY_RUN_ONLY,
        /** Live LLM umbrella pass (consolidations + prunings actually applied). */
        LIVE
    }

    private final boolean enabled;
    private final int intervalHours;
    private final int minIdleHours;
    private final int staleAfterDays;
    private final int archiveAfterDays;
    private final UmbrellaPassMode umbrellaPassMode;
    private final int backupRetention;

    private SkillCuratorConfig(Builder b) {
        this.enabled = b.enabled;
        this.intervalHours = b.intervalHours;
        this.minIdleHours = b.minIdleHours;
        this.staleAfterDays = b.staleAfterDays;
        this.archiveAfterDays = b.archiveAfterDays;
        this.umbrellaPassMode = b.umbrellaPassMode;
        this.backupRetention = b.backupRetention;
    }

    public boolean enabled() {
        return enabled;
    }

    public int intervalHours() {
        return intervalHours;
    }

    public int minIdleHours() {
        return minIdleHours;
    }

    public int staleAfterDays() {
        return staleAfterDays;
    }

    public int archiveAfterDays() {
        return archiveAfterDays;
    }

    public UmbrellaPassMode umbrellaPassMode() {
        return umbrellaPassMode;
    }

    public int backupRetention() {
        return backupRetention;
    }

    public static SkillCuratorConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private int intervalHours = 24 * 7; // 7 days
        private int minIdleHours = 2;
        private int staleAfterDays = 30;
        private int archiveAfterDays = 90;
        private UmbrellaPassMode umbrellaPassMode = UmbrellaPassMode.DRY_RUN_ONLY;
        private int backupRetention = 5;

        private Builder() {}

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder intervalHours(int v) {
            this.intervalHours = Math.max(1, v);
            return this;
        }

        public Builder minIdleHours(int v) {
            this.minIdleHours = Math.max(0, v);
            return this;
        }

        public Builder staleAfterDays(int v) {
            this.staleAfterDays = Math.max(1, v);
            return this;
        }

        public Builder archiveAfterDays(int v) {
            this.archiveAfterDays = Math.max(this.staleAfterDays, v);
            return this;
        }

        public Builder umbrellaPassMode(UmbrellaPassMode mode) {
            this.umbrellaPassMode = mode != null ? mode : UmbrellaPassMode.DRY_RUN_ONLY;
            return this;
        }

        public Builder backupRetention(int v) {
            this.backupRetention = Math.max(0, v);
            return this;
        }

        public SkillCuratorConfig build() {
            return new SkillCuratorConfig(this);
        }
    }
}
