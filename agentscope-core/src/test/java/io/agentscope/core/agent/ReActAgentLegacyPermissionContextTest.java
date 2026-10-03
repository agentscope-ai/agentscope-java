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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * End-to-end regression test for #2769: when a session has only v1 legacy keys
 * ({@code memory_messages} / {@code toolkit_activeGroups}), the first call reconstructs state via
 * {@code LegacyStateLoader}. Legacy keys cannot carry a permission context, so the builder-supplied
 * {@code permissionContext} must be forwarded to the loader instead of silently falling back to
 * {@link PermissionMode#DEFAULT}.
 */
class ReActAgentLegacyPermissionContextTest {

    private static final String SESSION = "session-legacy-perm";

    private final InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

    @Test
    void legacySessionLoad_keepsBuilderPermissionContext() {
        Msg legacyMsg = Msg.builder().role(MsgRole.USER).textContent("hello from v1").build();
        stateStore.save(null, SESSION, "memory_messages", List.of(legacyMsg));
        stateStore.save(
                null, SESSION, "toolkit_activeGroups", new ToolkitState(List.of("team-alpha")));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(new TextModel())
                        .stateStore(stateStore)
                        .defaultSessionId(SESSION)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .build();

        Msg reply = agent.call(List.of()).block();
        assertNotNull(reply);

        AgentState state = agent.getAgentState();
        assertEquals(
                PermissionMode.BYPASS,
                state.getPermissionContext().getMode(),
                "builder-supplied permission context must survive legacy v1 session loading");
        assertEquals(
                SESSION,
                state.getSessionId(),
                "migrated state must keep the slot's session id instead of a random value");
        assertNull(state.getUserId(), "anonymous slot keeps a null user id");
        assertTrue(
                state.getContext().stream()
                        .anyMatch(m -> "hello from v1".equals(m.getTextContent())),
                "legacy conversation context must be preserved");
        assertEquals(
                List.of("team-alpha"),
                state.getToolContext().getActivatedGroups(),
                "legacy tool activation groups must be preserved");
    }

    @Test
    void existingV2State_winsOverBuilderPermissionContext() {
        // A pre-existing 2.0 state is authoritative: its own permission context must not be
        // clobbered by the builder template on a legacy-triggered load.
        stateStore.save(
                null, SESSION, "agent_state", AgentState.builder().sessionId(SESSION).build());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(new TextModel())
                        .stateStore(stateStore)
                        .defaultSessionId(SESSION)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .build();

        Msg reply = agent.call(List.of()).block();
        assertNotNull(reply);

        assertEquals(
                PermissionMode.DEFAULT,
                agent.getAgentState().getPermissionContext().getMode(),
                "an existing v2 state keeps its own permission context over the builder template");
    }

    private static final class TextModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "text";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }
}
