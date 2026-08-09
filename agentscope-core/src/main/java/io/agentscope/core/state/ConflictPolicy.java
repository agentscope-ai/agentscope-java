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
package io.agentscope.core.state;

/**
 * Policy applied when an optimistic-concurrency save of agent session state conflicts with
 * another writer's update.
 *
 * <p>CAS failures occur at the end of a turn — the reply may already have been streamed and tool
 * side effects may already have run — so the policy cannot roll those back. Choose explicitly:
 *
 * <ul>
 *   <li>{@link #OVERWRITE} — unconditional last-writer-wins (default; matches pre-versioning
 *       behaviour).
 *   <li>{@link #FAIL} — throw {@link ConcurrentSessionModificationException} and skip the write.
 *       Recommended when a distributed session turn gate is enabled.
 *   <li>{@link #APPEND_MERGE} — reload the latest baseline, append this turn's new messages, and
 *       retry CAS. Reasonable for ordinary chat turns; unsafe for compaction/summary turns.
 * </ul>
 */
public enum ConflictPolicy {
    /** Unconditional overwrite (default; preserves legacy last-writer-wins semantics). */
    OVERWRITE,

    /** Fail the save and surface {@link ConcurrentSessionModificationException}. */
    FAIL,

    /**
     * Reload the latest baseline, append this turn's newly added messages, and retry CAS.
     * Opt-in only — not safe for compaction / summary turns that rewrite history.
     */
    APPEND_MERGE
}
