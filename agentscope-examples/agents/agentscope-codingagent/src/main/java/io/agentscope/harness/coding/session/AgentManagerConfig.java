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
package io.agentscope.harness.coding.session;

/**
 * Tunable concurrency, announce behavior, session reset policy, and maintenance configuration for
 * {@link SessionAgentManager}.
 */
public record AgentManagerConfig(
        int maxConcurrentSubagentRuns,
        int maxConcurrentNestedRuns,
        boolean queueAnnounceToRequester,
        int maxPendingAnnouncePerRequester,
        SessionResetPolicy sessionResetPolicy,
        SessionMaintenanceConfig maintenanceConfig) {

    public static final int DEFAULT_MAX_SUBAGENT_CONCURRENT = 4;
    public static final int DEFAULT_MAX_NESTED_CONCURRENT = 8;
    public static final int DEFAULT_MAX_PENDING_ANNOUNCE = 256;

    public AgentManagerConfig {
        if (maxConcurrentSubagentRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentSubagentRuns must be >= 1");
        }
        if (maxConcurrentNestedRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentNestedRuns must be >= 1");
        }
        if (maxPendingAnnouncePerRequester < 1) {
            throw new IllegalArgumentException("maxPendingAnnouncePerRequester must be >= 1");
        }
        if (sessionResetPolicy == null) {
            sessionResetPolicy = SessionResetPolicy.never();
        }
        if (maintenanceConfig == null) {
            maintenanceConfig = SessionMaintenanceConfig.disabled();
        }
    }

    /** Backwards-compatible constructor without maintenance config. */
    public AgentManagerConfig(
            int maxConcurrentSubagentRuns,
            int maxConcurrentNestedRuns,
            boolean queueAnnounceToRequester,
            int maxPendingAnnouncePerRequester,
            SessionResetPolicy sessionResetPolicy) {
        this(
                maxConcurrentSubagentRuns,
                maxConcurrentNestedRuns,
                queueAnnounceToRequester,
                maxPendingAnnouncePerRequester,
                sessionResetPolicy,
                SessionMaintenanceConfig.disabled());
    }

    /** Backwards-compatible constructor without sessionResetPolicy or maintenance. */
    public AgentManagerConfig(
            int maxConcurrentSubagentRuns,
            int maxConcurrentNestedRuns,
            boolean queueAnnounceToRequester,
            int maxPendingAnnouncePerRequester) {
        this(
                maxConcurrentSubagentRuns,
                maxConcurrentNestedRuns,
                queueAnnounceToRequester,
                maxPendingAnnouncePerRequester,
                SessionResetPolicy.never(),
                SessionMaintenanceConfig.disabled());
    }

    public static AgentManagerConfig defaults() {
        return new AgentManagerConfig(
                DEFAULT_MAX_SUBAGENT_CONCURRENT,
                DEFAULT_MAX_NESTED_CONCURRENT,
                true,
                DEFAULT_MAX_PENDING_ANNOUNCE,
                SessionResetPolicy.never(),
                SessionMaintenanceConfig.disabled());
    }
}
