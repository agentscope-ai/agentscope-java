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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.legacy.ToolkitState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LegacyStateLoader}: the v1-session reconstruction path must always preserve
 * the caller-supplied {@link PermissionContextState} — legacy keys themselves never carry one,
 * so a missing permission context must not silently downgrade the reconstructed state.
 */
class LegacyStateLoaderTest {

    private static final String USER = "uid-1";
    private static final String SESSION = "session-1";

    private final InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

    @Test
    void withPresence_appliesSuppliedPermissionContext() {
        seedLegacyKeys();
        PermissionContextState ctx =
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build();

        LegacyStateLoader.LegacyLoadResult result =
                LegacyStateLoader.loadFromLegacySessionWithPresence(stateStore, USER, SESSION, ctx);

        assertTrue(result.found());
        assertEquals(PermissionMode.BYPASS, result.state().getPermissionContext().getMode());
    }

    @Test
    void withoutPresence_appliesSuppliedPermissionContext() {
        seedLegacyKeys();
        PermissionContextState ctx =
                PermissionContextState.builder().mode(PermissionMode.EXPLORE).build();

        AgentState state = LegacyStateLoader.loadFromLegacySession(stateStore, USER, SESSION, ctx);

        assertEquals(PermissionMode.EXPLORE, state.getPermissionContext().getMode());
    }

    @Test
    void noPermissionContext_keepsDefault() {
        seedLegacyKeys();

        AgentState state = LegacyStateLoader.loadFromLegacySession(stateStore, USER, SESSION);

        assertEquals(
                PermissionMode.DEFAULT,
                state.getPermissionContext().getMode(),
                "the overload without a permission context must keep the default mode");
    }

    @Test
    void withPresence_noPermissionContext_keepsDefault() {
        seedLegacyKeys();

        LegacyStateLoader.LegacyLoadResult result =
                LegacyStateLoader.loadFromLegacySessionWithPresence(stateStore, USER, SESSION);

        assertTrue(result.found());
        assertEquals(PermissionMode.DEFAULT, result.state().getPermissionContext().getMode());
    }

    @Test
    void legacyContent_isPreservedAlongsidePermissionContext() {
        Msg legacyMsg = Msg.builder().role(MsgRole.USER).textContent("hello from v1").build();
        stateStore.save(USER, SESSION, "memory_messages", List.of(legacyMsg));
        stateStore.save(
                USER, SESSION, "toolkit_activeGroups", new ToolkitState(List.of("team-alpha")));
        PermissionContextState ctx =
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build();

        AgentState state = LegacyStateLoader.loadFromLegacySession(stateStore, USER, SESSION, ctx);

        assertEquals(1, state.getContext().size());
        assertEquals(legacyMsg, state.getContext().get(0));
        assertEquals(PermissionMode.BYPASS, state.getPermissionContext().getMode());
        assertEquals(
                List.of("team-alpha"),
                state.getToolContext().getActivatedGroups(),
                "legacy tool activation groups must survive reconstruction");
    }

    @Test
    void noLegacyKeys_returnsNotFound() {
        LegacyStateLoader.LegacyLoadResult result =
                LegacyStateLoader.loadFromLegacySessionWithPresence(
                        stateStore,
                        USER,
                        SESSION,
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        assertFalse(result.found());
    }

    private void seedLegacyKeys() {
        stateStore.save(
                USER, SESSION, "toolkit_activeGroups", new ToolkitState(List.of("team-alpha")));
    }
}
