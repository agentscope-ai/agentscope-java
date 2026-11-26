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
package io.agentscope.core.tool.subagent;

/**
 * How a {@link SubAgentTool} chooses the child session for each delegation.
 *
 * <p>The child session decides which conversation history the sub-agent loads and saves through
 * {@link SubAgentConfig#getStateStore()}. In every strategy the child inherits the caller's user
 * id, so histories stay isolated per user.
 */
public enum SubAgentSessionStrategy {

    /**
     * The model controls continuity (default). The tool exposes an optional {@code session_id}
     * parameter: omit it to start a new child session with a random id, pass a previously
     * returned id to continue that session.
     */
    MODEL_CONTROLLED,

    /**
     * One child session per caller session. The child session id is derived from the caller's
     * session id and the tool name ({@code <parentSessionId>:<toolName>}), so every delegation
     * within the same caller session continues the same child history without the model having
     * to echo an id back. The {@code session_id} parameter is not exposed to the model and is
     * ignored if supplied.
     *
     * <p>The derived id never equals the caller's own session id, so the child's state cannot
     * overwrite the caller's state even when both share a state store, and two sub-agent tools
     * under the same caller session keep separate histories.
     *
     * <p>If the caller's runtime context carries no session id, the delegation fails with an error
     * result instead of silently starting a fresh child session, since that would lose the
     * continuity this strategy is chosen for.
     *
     * <p>Concurrency: every delegation from one caller session targets the same child session,
     * and the child state is loaded before and saved after each delegation without locking. Run at
     * most one delegation per tool and caller session at a time. Two concurrent delegations (for
     * example parallel tool calls in one reasoning step) both start from the same stored history,
     * and the later save overwrites the earlier one's turns. Under {@link #MODEL_CONTROLLED} such
     * calls usually get separate random sessions, so this exposure is specific to this strategy.
     */
    PER_PARENT_SESSION
}
