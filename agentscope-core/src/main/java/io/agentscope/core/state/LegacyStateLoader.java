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

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.legacy.ToolkitState;
import java.util.List;
import java.util.Optional;

/**
 * Utility for loading v1 session data into the 2.0 {@link AgentState} format.
 *
 * <p>This class reads the legacy session keys ({@code memory_messages},
 * {@code toolkit_activeGroups}) and constructs an equivalent {@link AgentState} object. The
 * original session data is not modified or migrated in place; subsequent saves will use the new
 * format automatically.
 */
public final class LegacyStateLoader {

    private LegacyStateLoader() {}

    /**
     * Load an {@link AgentState} from a v1 session.
     *
     * <p>Reads:
     * <ul>
     *   <li>{@code memory_messages} — conversation history</li>
     *   <li>{@code toolkit_activeGroups} — tool activation state</li>
     * </ul>
     *
     * @param stateStore the state store to read from
     * @param userId nullable user identifier
     * @param sessionId the session identifier (required)
     * @return a new AgentState populated with the legacy data
     */
    public static AgentState loadFromLegacySession(
            AgentStateStore stateStore, String userId, String sessionId) {
        return loadFromLegacySessionWithPresence(stateStore, userId, sessionId).state();
    }

    /**
     * Loads legacy state without discarding the presence of the singleton tool-group key.
     *
     * <p>The presence bit is required because an explicitly persisted {@code
     * toolkit_activeGroups=[]} has different semantics from a missing key even though both produce
     * an empty {@link ToolContextState}.
     */
    public static LegacyLoadResult loadFromLegacySessionWithPresence(
            AgentStateStore stateStore, String userId, String sessionId) {
        List<Msg> msgs = stateStore.getList(userId, sessionId, "memory_messages", Msg.class);
        Optional<ToolkitState> toolkitState =
                stateStore.get(userId, sessionId, "toolkit_activeGroups", ToolkitState.class);

        AgentState.Builder builder = AgentState.builder().context(msgs);

        toolkitState.ifPresent(
                value -> {
                    ToolContextState.Builder tc = ToolContextState.builder();
                    for (String group : value.activeGroups()) {
                        tc.addActivatedGroup(group);
                    }
                    builder.toolContext(tc.build());
                });

        return new LegacyLoadResult(builder.build(), !msgs.isEmpty() || toolkitState.isPresent());
    }

    /**
     * Result of reading the v1 session keys. {@code found} is true for non-empty legacy messages or
     * a present tool-group key, including a tool-group value whose list is explicitly empty.
     */
    public record LegacyLoadResult(AgentState state, boolean found) {}
}
