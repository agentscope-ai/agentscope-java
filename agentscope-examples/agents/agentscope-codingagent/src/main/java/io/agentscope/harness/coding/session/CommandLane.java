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
 * Scheduling lane for subagent / main work, aligned with OpenClaw gateway {@code CommandLane}
 * semantics.
 *
 * <p>AgentStateStore serialization (one embedded run at a time per {@code sessionKey}) is implemented
 * separately via per-session locks; lanes bound <strong>global</strong> concurrency across sessions
 * (e.g. cap parallel subagent runs process-wide).
 */
public enum CommandLane {

    /** Primary user-facing / default agent turns. */
    MAIN,

    /**
     * Background subagent runs ({@code sessions_spawn} child work). Shares a process-wide semaphore
     * so many subagents can progress without unbounded parallelism.
     */
    SUBAGENT,

    /**
     * Nested / inner hops that must not hold the parent lane slot (avoids deadlock in layered
     * scheduling). Used sparingly for follow-on work that must not count against the subagent pool.
     */
    NESTED
}
