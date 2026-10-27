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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SubAgentToolStateRecoveryTest {

    private static final String SESSION_ID = "continued-session";

    @Test
    void continuedSessionAcceptsNewInputAfterInterruptedStateIsLoaded() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        Msg interruptedInput = userMsg("interrupted-id", "interrupted request");
        store.save(
                null,
                SESSION_ID,
                "agent_state",
                AgentState.builder()
                        .sessionId(SESSION_ID)
                        .addMessage(interruptedInput)
                        .shutdownInterrupted(true)
                        .shutdownInputMessageIds(List.of(interruptedInput.getId()))
                        .build());
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);

        tool.callAsync(continuedCall("different request")).block(Duration.ofSeconds(5));

        assertTrue(
                model.receivedMessages.stream()
                        .anyMatch(msg -> "different request".equals(msg.getTextContent())),
                "a newly identified request must reach the model");
        AgentState saved = savedState(store);
        assertTrue(
                saved.getContext().stream()
                        .anyMatch(msg -> "different request".equals(msg.getTextContent())),
                "the newly identified request must be saved");
        assertFalse(saved.isShutdownInterrupted());
        assertNull(saved.getShutdownInputMessageIds());
    }

    @Test
    void continuedSessionOverwritesStaleLiveInputIdsWithStoredIds() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        List<String> storedIds = List.of("stored-first", "stored-second");
        store.save(
                null,
                SESSION_ID,
                "agent_state",
                AgentState.builder()
                        .sessionId(SESSION_ID)
                        .shutdownInputMessageIds(storedIds)
                        .build());
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, List.of("stale-live-id"));

        tool.callAsync(continuedCall("next request")).block(Duration.ofSeconds(5));

        assertTrue(
                model.receivedMessages.stream()
                        .anyMatch(msg -> "next request".equals(msg.getTextContent())));
        assertEquals(
                storedIds,
                savedState(store).getShutdownInputMessageIds(),
                "state loading must replace the live list with the complete stored list");
    }

    @Test
    void continuedSessionClearsStaleLiveInputIdsForLegacyCheckpoint() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.save(
                null,
                SESSION_ID,
                "agent_state",
                AgentState.builder().sessionId(SESSION_ID).shutdownInterrupted(true).build());
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, List.of("stale-live-id"));

        tool.callAsync(continuedCall("legacy retry")).block(Duration.ofSeconds(5));

        assertFalse(
                model.receivedMessages.stream()
                        .anyMatch(msg -> "legacy retry".equals(msg.getTextContent())),
                "a legacy checkpoint must retain its one-time input discard behavior");
        AgentState saved = savedState(store);
        assertFalse(saved.isShutdownInterrupted());
        assertNull(saved.getShutdownInputMessageIds());
    }

    private static SubAgentTool tool(
            InMemoryAgentStateStore store, CapturingModel model, List<String> liveInputIds) {
        SubAgentProvider<ReActAgent> provider =
                () -> {
                    ReActAgent agent =
                            ReActAgent.builder()
                                    .name("state-recovery-sub-agent")
                                    .sysPrompt("Answer the user.")
                                    .model(model)
                                    .build();
                    agent.getAgentState().setShutdownInputMessageIds(liveInputIds);
                    return agent;
                };
        return new SubAgentTool(
                provider, SubAgentConfig.builder().forwardEvents(false).stateStore(store).build());
    }

    private static ToolCallParam continuedCall(String message) {
        return ToolCallParam.builder()
                .input(Map.of("session_id", SESSION_ID, "message", message))
                .build();
    }

    private static AgentState savedState(InMemoryAgentStateStore store) {
        return store.get(null, SESSION_ID, "agent_state", AgentState.class).orElseThrow();
    }

    private static Msg userMsg(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class CapturingModel extends ChatModelBase {
        private List<Msg> receivedMessages = List.of();

        @Override
        public String getModelName() {
            return "capturing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            receivedMessages = List.copyOf(messages);
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }
}
