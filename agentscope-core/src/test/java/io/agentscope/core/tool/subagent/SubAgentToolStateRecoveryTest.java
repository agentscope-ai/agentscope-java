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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactToolCallRetryDoesNotAppendTheSubAgentInputTwice(boolean forwardEvents) {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null, forwardEvents);
        ToolCallParam call = continuedCall("same request", "parent-tool-call-1");

        tool.callAsync(call).block(Duration.ofSeconds(5));
        Msg firstInput = inputWithText(savedState(store), "same request");
        saveInterruptedState(store, SESSION_ID, firstInput);

        tool.callAsync(call).block(Duration.ofSeconds(5));

        assertEquals(
                1,
                model.receivedMessages.stream()
                        .filter(msg -> "same request".equals(msg.getTextContent()))
                        .count(),
                "retrying the same parent tool call must not duplicate its sub-agent input");
        AgentState saved = savedState(store);
        assertFalse(saved.isShutdownInterrupted());
        assertNull(saved.getShutdownInputMessageIds());
    }

    @Test
    void differentToolCallWithSameTextIsAcceptedAfterInterruptedStateIsLoaded() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);

        tool.callAsync(continuedCall("same request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));
        Msg firstInput = inputWithText(savedState(store), "same request");
        saveInterruptedState(store, SESSION_ID, firstInput);

        tool.callAsync(continuedCall("same request", "parent-tool-call-2"))
                .block(Duration.ofSeconds(5));

        List<Msg> matching = inputsWithText(model.receivedMessages, "same request");
        assertEquals(2, matching.size());
        assertNotEquals(matching.get(0).getId(), matching.get(1).getId());
    }

    @Test
    void sameToolCallWithChangedEffectiveMessageIsAccepted() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);

        tool.callAsync(continuedCall("first request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));
        Msg firstInput = inputWithText(savedState(store), "first request");
        saveInterruptedState(store, SESSION_ID, firstInput);

        tool.callAsync(continuedCall("changed request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));

        Msg changed = inputWithText(model.receivedMessages, "changed request");
        assertNotEquals(firstInput.getId(), changed.getId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void unusableToolCallIdKeepsRandomMessageIdentity(String toolCallId) {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);
        ToolCallParam call = continuedCall("uncorrelated request", toolCallId);

        tool.callAsync(call).block(Duration.ofSeconds(5));
        tool.callAsync(call).block(Duration.ofSeconds(5));

        List<Msg> matching = inputsWithText(savedState(store).getContext(), "uncorrelated request");
        assertEquals(2, matching.size());
        assertNotEquals(matching.get(0).getId(), matching.get(1).getId());
    }

    @Test
    void missingToolUseBlockKeepsRandomMessageIdentity() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);
        ToolCallParam call = continuedCall("uncorrelated request");

        tool.callAsync(call).block(Duration.ofSeconds(5));
        tool.callAsync(call).block(Duration.ofSeconds(5));

        List<Msg> matching = inputsWithText(savedState(store).getContext(), "uncorrelated request");
        assertEquals(2, matching.size());
        assertNotEquals(matching.get(0).getId(), matching.get(1).getId());
    }

    @Test
    void sameToolCallInDifferentSubAgentSessionsHasDifferentMessageIdentity() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);

        tool.callAsync(continuedCall("session-a", "same request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));
        tool.callAsync(continuedCall("session-b", "same request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));

        Msg sessionA = inputWithText(savedState(store, "session-a"), "same request");
        Msg sessionB = inputWithText(savedState(store, "session-b"), "same request");
        assertNotEquals(sessionA.getId(), sessionB.getId());
    }

    @Test
    void presetAndInterceptorChangesParticipateInEffectiveInputIdentity() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);
        AtomicReference<String> rewrittenMessage = new AtomicReference<>("effective request 1");
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (invocation, next) -> {
                                            Map<String, Object> input =
                                                    new HashMap<>(invocation.input());
                                            input.put("message", rewrittenMessage.get());
                                            return next.apply(invocation.withInput(input));
                                        })
                                .build());
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), Map.of("session_id", SESSION_ID)))
                .apply();
        ToolUseBlock parentCall =
                ToolUseBlock.builder()
                        .id("parent-tool-call-1")
                        .name(tool.getName())
                        .input(Map.of("message", "original request"))
                        .build();

        toolkit.callTool(ToolCallParam.builder().toolUseBlock(parentCall).build())
                .block(Duration.ofSeconds(5));
        Msg first = inputWithText(savedState(store), "effective request 1");
        saveInterruptedState(store, SESSION_ID, first);

        toolkit.callTool(ToolCallParam.builder().toolUseBlock(parentCall).build())
                .block(Duration.ofSeconds(5));
        assertEquals(1, inputsWithText(model.receivedMessages, "effective request 1").size());

        rewrittenMessage.set("effective request 2");
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(parentCall).build())
                .block(Duration.ofSeconds(5));

        Msg second = inputWithText(savedState(store), "effective request 2");
        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void legacyRandomMessageIdCannotBeCorrelatedToTheDerivedRetryId() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        Msg legacyInput = userMsg("legacy-random-id", "same request");
        saveInterruptedState(store, SESSION_ID, legacyInput);
        CapturingModel model = new CapturingModel();
        SubAgentTool tool = tool(store, model, null);

        tool.callAsync(continuedCall("same request", "parent-tool-call-1"))
                .block(Duration.ofSeconds(5));

        List<Msg> matching = inputsWithText(model.receivedMessages, "same request");
        assertEquals(
                2,
                matching.size(),
                "a pre-fix random message id cannot be safely inferred from the parent call");
        assertNotEquals(matching.get(0).getId(), matching.get(1).getId());
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
        return tool(store, model, liveInputIds, false);
    }

    private static SubAgentTool tool(
            InMemoryAgentStateStore store,
            CapturingModel model,
            List<String> liveInputIds,
            boolean forwardEvents) {
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
                provider,
                SubAgentConfig.builder().forwardEvents(forwardEvents).stateStore(store).build());
    }

    private static ToolCallParam continuedCall(String message) {
        return ToolCallParam.builder()
                .input(Map.of("session_id", SESSION_ID, "message", message))
                .build();
    }

    private static ToolCallParam continuedCall(String message, String toolCallId) {
        return continuedCall(SESSION_ID, message, toolCallId);
    }

    private static ToolCallParam continuedCall(
            String sessionId, String message, String toolCallId) {
        Map<String, Object> input = Map.of("session_id", sessionId, "message", message);
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id(toolCallId)
                                .name("call_state_recovery_sub_agent")
                                .input(input)
                                .build())
                .input(input)
                .build();
    }

    private static AgentState savedState(InMemoryAgentStateStore store) {
        return savedState(store, SESSION_ID);
    }

    private static AgentState savedState(InMemoryAgentStateStore store, String sessionId) {
        return store.get(null, sessionId, "agent_state", AgentState.class).orElseThrow();
    }

    private static void saveInterruptedState(
            InMemoryAgentStateStore store, String sessionId, Msg input) {
        store.save(
                null,
                sessionId,
                "agent_state",
                AgentState.builder()
                        .sessionId(sessionId)
                        .addMessage(input)
                        .shutdownInterrupted(true)
                        .shutdownInputMessageIds(List.of(input.getId()))
                        .build());
    }

    private static Msg inputWithText(AgentState state, String text) {
        return inputWithText(state.getContext(), text);
    }

    private static Msg inputWithText(List<Msg> messages, String text) {
        return messages.stream()
                .filter(msg -> text.equals(msg.getTextContent()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Msg> inputsWithText(List<Msg> messages, String text) {
        return messages.stream().filter(msg -> text.equals(msg.getTextContent())).toList();
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
