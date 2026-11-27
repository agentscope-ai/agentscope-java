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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Regression test for #2888, on the caller side of {@code LegacyStateLoader}. A session that
 * only has v1 keys ({@code memory_messages} / {@code toolkit_activeGroups}) is reconstructed on
 * first load, and legacy keys carry no permission context. The builder's {@code
 * permissionContext} has to reach the reconstructed state, as it does for a fresh one.
 */
class ReActAgentLegacyPermissionContextTest {

    private static final String USER = "uid-1";
    private static final String SESSION = "session-legacy-perm";

    private final InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

    private static PermissionContextState bypassWithRule() {
        return PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addDenyRule(
                        "shell",
                        new PermissionRule("shell", "rm *", PermissionBehavior.DENY, "settings"))
                .build();
    }

    private static ReActAgent builder(
            InMemoryAgentStateStore store, PermissionContextState permissionContext) {
        ReActAgent.Builder builder =
                ReActAgent.builder().name("asst").model(new TextModel()).stateStore(store);
        return permissionContext == null
                ? builder.build()
                : builder.permissionContext(permissionContext).build();
    }

    private static void seedLegacyKeys(InMemoryAgentStateStore store) {
        Msg legacyMsg = Msg.builder().role(MsgRole.USER).textContent("hello from v1").build();
        store.save(USER, SESSION, "memory_messages", List.of(legacyMsg));
        store.save(USER, SESSION, "toolkit_activeGroups", new ToolkitState(List.of("team-alpha")));
    }

    @Test
    void legacySessionIsReconstructedWithTheBuilderPermissionContext() {
        PermissionContextState configured = bypassWithRule();
        seedLegacyKeys(stateStore);
        try (var agent = builder(stateStore, configured)) {
            AgentState state = agent.getAgentState(USER, SESSION);

            assertEquals(
                    configured,
                    state.getPermissionContext(),
                    "the builder's permission context must survive the legacy v1 load");
            assertEquals(1, state.getContext().size(), "legacy conversation must be preserved");
            assertEquals(
                    List.of("team-alpha"),
                    state.getToolContext().getActivatedGroups(),
                    "legacy tool activation groups must be preserved");
        }
    }

    @Test
    void legacySessionWithoutBuilderContextKeepsTheDefault() {
        seedLegacyKeys(stateStore);
        try (var agent = builder(stateStore, null)) {
            assertEquals(
                    PermissionMode.DEFAULT,
                    agent.getAgentState(USER, SESSION).getPermissionContext().getMode());
        }
    }

    @Test
    void storedStateKeepsItsOwnPermissionContext() {
        // Only the legacy path is changed: an existing 2.0 state is loaded as it was persisted.
        stateStore.save(
                USER,
                SESSION,
                "agent_state",
                AgentState.builder()
                        .userId(USER)
                        .sessionId(SESSION)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.EXPLORE)
                                        .build())
                        .build());
        try (var agent = builder(stateStore, bypassWithRule())) {
            assertEquals(
                    PermissionMode.EXPLORE,
                    agent.getAgentState(USER, SESSION).getPermissionContext().getMode());
        }
    }

    @Test
    void callAppliesTheBuilderPermissionContextOnFirstActivation() {
        // Not part of the regression: a call already applies the builder's context when it
        // activates a slot for the first time. This pins that the fix leaves that path as it is.
        PermissionContextState configured = bypassWithRule();
        seedLegacyKeys(stateStore);
        try (var agent = builder(stateStore, configured)) {
            assertNotNull(
                    agent.call(
                                    List.of(),
                                    RuntimeContext.builder()
                                            .userId(USER)
                                            .sessionId(SESSION)
                                            .build())
                            .block());

            assertEquals(configured, agent.getAgentState(USER, SESSION).getPermissionContext());
        }
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
