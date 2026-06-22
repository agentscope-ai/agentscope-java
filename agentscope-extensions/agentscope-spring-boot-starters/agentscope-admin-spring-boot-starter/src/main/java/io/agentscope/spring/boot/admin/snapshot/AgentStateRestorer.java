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
package io.agentscope.spring.boot.admin.snapshot;

import io.agentscope.core.state.AgentState;

/**
 * Copies the mutable fields of a snapshotted {@link AgentState} back into a live one.
 *
 * <p>Why a copy instead of a swap: {@code ReActAgent} holds its {@code AgentState} in a
 * {@code private final} field, so we cannot replace the instance from outside. The next best
 * thing is a field-by-field overwrite of everything that has a setter or is itself mutable.
 *
 * <p><b>Coverage</b>:
 *
 * <ul>
 *   <li>{@code summary} — overwritten via setter
 *   <li>{@code replyId} — overwritten via setter
 *   <li>{@code curIter} — overwritten via setter
 *   <li>{@code shutdownInterrupted} — overwritten via setter
 *   <li>{@code context} — cleared and refilled in-place
 * </ul>
 *
 * <p>The sub-context records ({@code permissionContext}, {@code toolContext},
 * {@code tasksContext}, {@code planModeContext}) are <em>not</em> restored — they would need
 * additional core support to be swappable mid-run. Currently this matches the scope of
 * {@code session:compact} (which only mutates {@code summary} + {@code context}); broader undo
 * coverage tracks the {@code AgentState} refactor in {@code project_java-agentstate-refactor.md}.
 */
public final class AgentStateRestorer {

    private AgentStateRestorer() {}

    /**
     * Restore the live {@code AgentState} from a snapshot.
     *
     * @param live the agent's current state (mutated in place)
     * @param snapshotJson JSON produced by {@link AgentState#toJson()}
     * @throws IllegalArgumentException if the JSON is invalid or targets a different session
     */
    public static void restore(AgentState live, String snapshotJson) {
        if (live == null) {
            throw new IllegalArgumentException("live state must not be null");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshot JSON must not be blank");
        }
        AgentState snap;
        try {
            snap = AgentState.fromJsonString(snapshotJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "invalid AgentState snapshot JSON: " + e.getMessage(), e);
        }
        if (!live.getSessionId().equals(snap.getSessionId())) {
            throw new IllegalArgumentException(
                    "snapshot sessionId="
                            + snap.getSessionId()
                            + " does not match live sessionId="
                            + live.getSessionId());
        }
        live.setSummary(snap.getSummary());
        live.setReplyId(snap.getReplyId());
        live.setCurIter(snap.getCurIter());
        live.setShutdownInterrupted(snap.isShutdownInterrupted());
        live.contextMutable().clear();
        live.contextMutable().addAll(snap.getContext());
    }
}
