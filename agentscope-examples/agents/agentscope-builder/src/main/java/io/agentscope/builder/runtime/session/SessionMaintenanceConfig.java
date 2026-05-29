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
package io.agentscope.builder.runtime.session;

/**
 * Configuration for session store maintenance. Controls automated pruning of stale sessions and
 * capping total entry count, mirroring OpenClaw's session maintenance modes.
 *
 * @param enabled whether maintenance runs automatically on session creation/touch
 * @param pruneAfterMs remove sessions not updated within this duration (0 = disabled)
 * @param maxEntries cap the total number of sessions in the store (0 = unlimited)
 */
public record SessionMaintenanceConfig(boolean enabled, long pruneAfterMs, int maxEntries) {

    /** Maintenance disabled by default. */
    public static SessionMaintenanceConfig disabled() {
        return new SessionMaintenanceConfig(false, 0, 0);
    }

    /**
     * @param pruneAfterMs prune sessions older than this many milliseconds (e.g. 7 days =
     *     604_800_000)
     * @param maxEntries maximum sessions to keep (evicts oldest first); 0 = unlimited
     */
    public static SessionMaintenanceConfig enabled(long pruneAfterMs, int maxEntries) {
        return new SessionMaintenanceConfig(true, pruneAfterMs, maxEntries);
    }
}
