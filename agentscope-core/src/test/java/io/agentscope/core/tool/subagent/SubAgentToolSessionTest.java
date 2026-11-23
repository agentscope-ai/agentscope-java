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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentExecutionHost;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.journal.JournalExportSink;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SubAgentToolSessionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void continuesChildSessionWithoutUsingParentSession(boolean forwardEvents) {
        List<MockModel> models = new ArrayList<>();
        InMemoryAgentStateStore stateStore = spy(new InMemoryAgentStateStore());
        SubAgentConfig config =
                SubAgentConfig.builder()
                        .forwardEvents(forwardEvents)
                        .stateStore(stateStore)
                        .build();
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            MockModel model = new MockModel("OK");
                            models.add(model);
                            return ReActAgent.builder()
                                    .name("child")
                                    .sysPrompt("You are helpful")
                                    .model(model)
                                    .toolkit(new Toolkit())
                                    .build();
                        },
                        config);

        RuntimeContext firstParent =
                RuntimeContext.builder().userId("merchant").sessionId("parent-one").build();
        AgentState firstParentState = mock(AgentState.class);
        firstParent.setAgentState(firstParentState);
        ToolResultBlock first =
                tool.callAsync(param("Remember this", null, firstParent))
                        .block(Duration.ofSeconds(10));
        String firstOutput = ((TextBlock) first.getOutput().get(0)).getText();
        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(stateStore)
                .save(
                        eq("merchant"),
                        sessionIdCaptor.capture(),
                        eq("agent_state"),
                        any(AgentState.class));
        String childSessionId = sessionIdCaptor.getValue();
        assertTrue(firstOutput.contains("session_id: " + childSessionId));
        AgentState saved =
                config.getStateStore()
                        .get("merchant", childSessionId, "agent_state", AgentState.class)
                        .orElseThrow();

        assertTrue(
                saved.getContext().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
        assertSame(firstParentState, firstParent.getAgentState());

        RuntimeContext secondParent =
                RuntimeContext.builder().userId("merchant").sessionId("parent-two").build();
        AgentState secondParentState = mock(AgentState.class);
        secondParent.setAgentState(secondParentState);
        ToolResultBlock second =
                tool.callAsync(param("Continue", childSessionId, secondParent))
                        .block(Duration.ofSeconds(10));

        assertTrue(((TextBlock) second.getOutput().get(0)).getText().contains(childSessionId));
        // SubAgentTool creates one sample agent in its constructor, then one per call.
        assertEquals(3, models.size());
        assertTrue(
                models.get(2).getLastMessages().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
        assertSame(secondParentState, secondParent.getAgentState());

        RuntimeContext attacker =
                RuntimeContext.builder().userId("attacker").sessionId("attacker-parent").build();
        ToolResultBlock attackerResult =
                tool.callAsync(param("Steal history", childSessionId, attacker))
                        .block(Duration.ofSeconds(10));

        assertTrue(
                ((TextBlock) attackerResult.getOutput().get(0)).getText().contains(childSessionId));
        assertEquals(4, models.size());
        assertTrue(
                models.get(3).getLastMessages().stream()
                        .noneMatch(message -> "Remember this".equals(message.getTextContent())));
        assertTrue(
                saved.getContext().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
    }

    @Test
    void nativeChildSessionDoesNotReuseParentExecutionOrTurn() {
        InMemoryThreadJournalStore childStore = new InMemoryThreadJournalStore();
        AtomicReference<ExecutionIdentity> childExecutionIdentity = new AtomicReference<>();
        MiddlewareBase executionIdentityProbe =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        childExecutionIdentity.set(ctx.get(ExecutionIdentity.CONTEXT_KEY));
                    }
                };
        SubAgentTool tool =
                new SubAgentTool(
                        () ->
                                ReActAgent.builder()
                                        .name("child")
                                        .model(new MockModel("OK"))
                                        .middleware(executionIdentityProbe)
                                        .threadJournalStore(childStore)
                                        .build(),
                        SubAgentConfig.builder().forwardEvents(false).build());
        RuntimeContext parent =
                RuntimeContext.builder().userId("merchant").sessionId("parent-session").build();
        ThreadJournal parentLog =
                new InMemoryThreadJournalStore()
                        .open(
                                new ThreadKey("merchant", "parent", "parent-session", "main"),
                                parent);
        InvocationRecorder parentRecorder = new InvocationRecorder(parentLog, "parent-turn");
        AgentExecutionHost parentExecution = mock(AgentExecutionHost.class);
        parent.put(InvocationRecorder.CONTEXT_KEY, parentRecorder);
        parent.put(JournalExportSink.CONTEXT_KEY, mock(JournalExportSink.class));
        parent.put(AgentExecutionHost.class, parentExecution);
        ExecutionIdentity parentExecutionIdentity =
                new ExecutionIdentity(
                        "parent", "parent-session", "main", "parent-turn", "parent-run");
        parent.put(ExecutionIdentity.CONTEXT_KEY, parentExecutionIdentity);
        try {
            ToolResultBlock result =
                    tool.callAsync(param("hello", null, parent)).block(Duration.ofSeconds(10));
            String output = ((TextBlock) result.getOutput().get(0)).getText();
            assertTrue(output.contains("OK"), output);
            String childSessionId =
                    childStore.list(parent).stream()
                            .filter(key -> "child".equals(key.agentId()))
                            .findFirst()
                            .orElseThrow()
                            .sessionId();
            assertTrue(output.contains("session_id: " + childSessionId));
            ThreadJournal childLog =
                    childStore.open(
                            new ThreadKey("merchant", "child", childSessionId, "main"), parent);
            var events = childLog.readAfter(0, 1000);

            assertTrue(events.stream().allMatch(event -> event.turnId() == null));
            assertFalse(events.stream().anyMatch(event -> "parent-turn".equals(event.turnId())));
            assertTrue(events.stream().anyMatch(event -> "subagent/linked".equals(event.type())));
            ExecutionIdentity childIdentity = childExecutionIdentity.get();
            assertNotSame(parentExecutionIdentity, childIdentity);
            assertEquals("child", childIdentity.agentId());
            assertEquals(childSessionId, childIdentity.sessionId());
            assertEquals(null, childIdentity.turnId());
            assertSame(parentRecorder, InvocationRecorder.from(parent));
            assertSame(parentExecution, parent.get(AgentExecutionHost.class));
            assertSame(parentExecutionIdentity, parent.get(ExecutionIdentity.CONTEXT_KEY));
        } finally {
            parentRecorder.close();
        }
    }

    private static ToolCallParam param(
            String message, String childSessionId, RuntimeContext parent) {
        Map<String, Object> input =
                childSessionId == null
                        ? Map.of("message", message)
                        : Map.of("message", message, "session_id", childSessionId);
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(parent)
                .emitter(mock(ToolEmitter.class))
                .build();
    }
}
