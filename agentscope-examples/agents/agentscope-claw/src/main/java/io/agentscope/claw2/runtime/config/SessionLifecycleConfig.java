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
package io.agentscope.claw2.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Optional {@code session} block in {@code agentscope.json} controlling session lifecycle and
 * maintenance.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * "session": {
 *   "reset": { "dailyAt": "04:00", "idleMinutes": 360 },
 *   "maintenance": { "mode": "prune", "pruneAfter": "7d", "maxEntries": 1000 }
 * }
 * }</pre>
 *
 * <p>Mirrors OpenClaw's session-level config. Mapped at runtime to:
 *
 * <ul>
 *   <li>{@link io.agentscope.claw2.runtime.session.SessionMaintenanceConfig} for prune/cap policy
 *   <li>A scheduled task ({@code SessionLifecycleScheduler}) that triggers reset events
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionLifecycleConfig {

    @JsonProperty("reset")
    private ResetConfig reset;

    @JsonProperty("maintenance")
    private MaintenanceConfig maintenance;

    public ResetConfig getReset() {
        return reset;
    }

    public void setReset(ResetConfig reset) {
        this.reset = reset;
    }

    public MaintenanceConfig getMaintenance() {
        return maintenance;
    }

    public void setMaintenance(MaintenanceConfig maintenance) {
        this.maintenance = maintenance;
    }

    // -----------------------------------------------------------------
    //  Reset config (auto-reset triggers)
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResetConfig {

        /**
         * 24-hour clock time ({@code "HH:mm"}) at which all sessions are auto-reset daily. Null /
         * blank to disable. Time is interpreted in the JVM's default timezone.
         */
        @JsonProperty("dailyAt")
        private String dailyAt;

        /**
         * If a session is idle (no activity) for this many minutes, it is considered eligible for
         * auto-reset on the next inbound message. {@code 0} disables.
         */
        @JsonProperty("idleMinutes")
        private Integer idleMinutes;

        public String getDailyAt() {
            return dailyAt;
        }

        public void setDailyAt(String dailyAt) {
            this.dailyAt = dailyAt;
        }

        public Integer getIdleMinutes() {
            return idleMinutes;
        }

        public void setIdleMinutes(Integer idleMinutes) {
            this.idleMinutes = idleMinutes;
        }
    }

    // -----------------------------------------------------------------
    //  Maintenance config (background pruning / capping)
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MaintenanceConfig {

        /** {@code "off"} (no-op), {@code "prune"} (time-based), or {@code "cap"} (entry-based). */
        @JsonProperty("mode")
        private String mode;

        /**
         * Maximum age before a session is considered stale and removed. Accepts durations like
         * {@code "7d"}, {@code "24h"}, {@code "60m"}, or a raw milliseconds long.
         */
        @JsonProperty("pruneAfter")
        private String pruneAfter;

        /** Hard cap on session entry count; oldest entries are evicted first when exceeded. */
        @JsonProperty("maxEntries")
        private Integer maxEntries;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPruneAfter() {
            return pruneAfter;
        }

        public void setPruneAfter(String pruneAfter) {
            this.pruneAfter = pruneAfter;
        }

        public Integer getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(Integer maxEntries) {
            this.maxEntries = maxEntries;
        }

        /** Parse a duration string like {@code "7d"} or {@code "60m"} to milliseconds. */
        public long pruneAfterMs() {
            if (pruneAfter == null || pruneAfter.isBlank()) return 0L;
            String s = pruneAfter.trim().toLowerCase();
            try {
                if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
                if (s.endsWith("s")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 1_000L;
                }
                if (s.endsWith("m")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 60_000L;
                }
                if (s.endsWith("h")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 3_600_000L;
                }
                if (s.endsWith("d")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 86_400_000L;
                }
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}
