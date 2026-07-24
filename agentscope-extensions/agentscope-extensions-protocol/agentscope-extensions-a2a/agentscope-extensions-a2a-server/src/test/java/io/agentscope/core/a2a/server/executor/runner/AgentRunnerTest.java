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

package io.agentscope.core.a2a.server.executor.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

@DisplayName("Agent Runner Tests")
class AgentRunnerTest {

    private AgentRequestOptions requestOptions;
    private ReActAgent.Builder mockBuilder;
    private ReActAgent mockAgent;
    private ReActAgentWithBuilderRunner runner;

    @BeforeEach
    void setUp() {
        requestOptions = new AgentRequestOptions();
        mockBuilder = mock(ReActAgent.Builder.class);
        mockAgent = mock(ReActAgent.class);
        when(mockBuilder.build()).thenReturn(mockAgent);
        runner = ReActAgentWithBuilderRunner.newInstance(mockBuilder);
    }

    @Test
    @DisplayName("Should set and get task ID")
    void testSetAndGetTaskId() {
        String taskId = "test-task-id";
        requestOptions.setTaskId(taskId);
        assertEquals(taskId, requestOptions.getTaskId());
    }

    @Test
    @DisplayName("Should set and get session ID")
    void testSetAndGetSessionId() {
        String sessionId = "test-session-id";
        requestOptions.setSessionId(sessionId);
        assertEquals(sessionId, requestOptions.getSessionId());
    }

    @Test
    @DisplayName("Should set and get user ID")
    void testSetAndGetUserId() {
        String userId = "test-user-id";
        requestOptions.setUserId(userId);
        assertEquals(userId, requestOptions.getUserId());
    }

    @Test
    @DisplayName("Should set and get agent ID")
    void testSetAndGetAgentId() {
        String agentId = "database-agent";
        requestOptions.setAgentId(agentId);
        assertEquals(agentId, requestOptions.getAgentId());
    }

    @Test
    @DisplayName("Should defensively copy metadata")
    void testSetAndGetMetadata() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("agentId", "database-agent");
        metadata.put("tenant", "middleware");

        requestOptions.setMetadata(metadata);
        metadata.put("tenant", "changed");

        assertEquals("database-agent", requestOptions.getMetadata().get("agentId"));
        assertEquals("middleware", requestOptions.getMetadata().get("tenant"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> requestOptions.getMetadata().put("new", "value"));
    }

    @Test
    @DisplayName("Should return null for unset fields")
    void testReturnNullForUnsetFields() {
        assertNull(requestOptions.getTaskId());
        assertNull(requestOptions.getSessionId());
        assertNull(requestOptions.getUserId());
        assertNull(requestOptions.getAgentId());
        assertTrue(requestOptions.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty string values")
    void testHandleEmptyStringValues() {
        requestOptions.setTaskId("");
        requestOptions.setSessionId("");
        requestOptions.setUserId("");
        requestOptions.setAgentId("");

        assertEquals("", requestOptions.getTaskId());
        assertEquals("", requestOptions.getSessionId());
        assertEquals("", requestOptions.getUserId());
        assertEquals("", requestOptions.getAgentId());
    }

    // ReActAgentWithBuilderRunner Tests

    @Test
    @DisplayName("Should create new instance with builder")
    void testCreateNewInstanceWithBuilder() {
        assertNotNull(runner);
    }

    @Test
    @DisplayName("Should expose the Agent existing durable AgentStateStore without replacing it")
    void testDurableStateStore() {
        AgentStateStore stateStore = mock(AgentStateStore.class);
        when(mockAgent.getStateStore()).thenReturn(stateStore);
        ReActAgentWithBuilderRunner durableRunner =
                ReActAgentWithBuilderRunner.newInstance(mockBuilder);

        assertEquals(HitlDurabilityCapability.DURABLE, durableRunner.hitlDurabilityCapability());
        assertEquals(stateStore, durableRunner.actualAgentStateStore().orElseThrow());

        requestOptions.setTaskId("durable-task");
        Flux<AgentEvent> stream = mock(Flux.class);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(stream);
        when(stream.doFinally(any())).thenReturn(stream);
        assertDoesNotThrow(
                () -> durableRunner.streamEvents(List.of(mock(Msg.class)), requestOptions));

        verify(mockBuilder, never()).stateStore(any());
    }

    @Test
    @DisplayName("Should build agent from builder")
    void testBuildAgentFromBuilder() {
        // When
        ReActAgent agent = runner.buildReActAgent();

        // Then
        assertNotNull(agent);
        verify(mockBuilder, times(1)).build();
    }

    @Test
    @DisplayName("Should return agent name from built agent")
    void testGetAgentName() {
        // Given
        String agentName = "Test Agent";
        when(mockAgent.getName()).thenReturn(agentName);

        // When
        String name = runner.getAgentName();

        // Then
        assertEquals(agentName, name);
        verify(mockAgent, times(1)).getName();
    }

    @Test
    @DisplayName("Should return agent description from built agent")
    void testGetAgentDescription() {
        // Given
        String agentDescription = "Test Agent Description";
        when(mockAgent.getDescription()).thenReturn(agentDescription);

        // When
        String description = runner.getAgentDescription();

        // Then
        assertEquals(agentDescription, description);
        verify(mockAgent, times(1)).getDescription();
    }

    @Test
    @DisplayName("Should stream messages and cache agent")
    void testStreamMessagesAndCacheAgent() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        // When
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // Then
        assertNotNull(result);
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).streamEvents(eq(messages), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("Should pass trimmed user and session into RuntimeContext")
    void testStreamUsesRuntimeContextFromOptions() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        requestOptions.setSessionId("  session-1  ");
        requestOptions.setUserId("  user-1  ");
        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        runner.streamEvents(messages, requestOptions);

        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), contextCaptor.capture());
        RuntimeContext runtimeContext = contextCaptor.getValue();
        assertEquals("session-1", runtimeContext.getSessionId());
        assertEquals("user-1", runtimeContext.getUserId());
    }

    @Test
    @DisplayName("Should leave blank session unset for ReActAgent default fallback")
    void testBlankSessionIsNotSynthesized() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        requestOptions.setSessionId("  ");
        requestOptions.setUserId(" user-1 ");
        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        runner.streamEvents(messages, requestOptions);

        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), contextCaptor.capture());
        RuntimeContext runtimeContext = contextCaptor.getValue();
        assertNull(runtimeContext.getSessionId());
        assertEquals("user-1", runtimeContext.getUserId());
    }

    @Test
    @DisplayName("Should throw exception when agent already exists for task ID")
    void testThrowExceptionWhenAgentAlreadyExists() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        // First call to populate the cache
        runner.streamEvents(messages, requestOptions);

        // When & Then
        assertThrows(
                IllegalStateException.class, () -> runner.streamEvents(messages, requestOptions));
    }

    @Test
    @DisplayName("Should remove agent from cache when stream completes")
    void testRemoveAgentFromCacheOnComplete() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        // Setup mock flux that simulates completion
        Flux<AgentEvent> mockFlux = Flux.empty();
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);

        // When
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // Subscribe to trigger the doFinally block
        result.subscribe();

        // Give reactive stream time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<AgentEvent> secondResult = runner.streamEvents(messages, requestOptions);
        assertNotNull(secondResult);
    }

    @Test
    @DisplayName("Should stop agent and remove from cache")
    void testStopAgentAndRemoveFromCache() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);
        runner.streamEvents(messages, requestOptions);

        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), contextCaptor.capture());
        RuntimeContext runtimeContext = contextCaptor.getValue();

        runner.stop(taskId);
        verify(mockAgent, times(1)).interrupt(runtimeContext);

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle stop for non-existent task ID")
    void testStopNonExistentTaskId() {
        assertDoesNotThrow(() -> runner.stop("non-existent-task-id"));
    }

    @Test
    @DisplayName("Should persist explicit user session across new agent instances")
    void testExplicitUserSessionPersistsAcrossAgentInstances() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentRunner realRunner = new TestReActAgentRunner(() -> smokeAgent(store));

        runAgent(realRunner, "task-1", "alice", "session-1", "hello alice");
        runAgent(realRunner, "task-2", "alice", "session-1", "follow alice");
        runAgent(realRunner, "task-3", "bob", "session-1", "hello bob");

        List<String> aliceTexts = allText(requiredState(store, "alice", "session-1"));
        assertTrue(aliceTexts.contains("hello alice"));
        assertTrue(aliceTexts.contains("follow alice"));
        assertFalse(aliceTexts.contains("hello bob"));

        List<String> bobTexts = allText(requiredState(store, "bob", "session-1"));
        assertTrue(bobTexts.contains("hello bob"));
        assertFalse(bobTexts.contains("hello alice"));
    }

    @Test
    @DisplayName("Should keep blank session on the agent default session")
    void testBlankSessionUsesDefaultSessionFallback() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentRunner realRunner = new TestReActAgentRunner(() -> smokeAgent(store));

        runAgent(realRunner, "task-blank-1", "alice", "  ", "blank one");
        runAgent(realRunner, "task-blank-2", "alice", null, "blank two");

        List<String> texts = allText(requiredState(store, "alice", "smoke-agent"));
        assertTrue(texts.contains("blank one"));
        assertTrue(texts.contains("blank two"));
    }

    private static ReActAgent smokeAgent(InMemoryAgentStateStore store) {
        return ReActAgent.builder()
                .name("smoke-agent")
                .sysPrompt("hi")
                .model(new DeterministicModel())
                .stateStore(store)
                .build();
    }

    private static void runAgent(
            AgentRunner realRunner, String taskId, String userId, String sessionId, String text) {
        AgentRequestOptions options = new AgentRequestOptions();
        options.setTaskId(taskId);
        options.setUserId(userId);
        options.setSessionId(sessionId);
        realRunner
                .streamEvents(List.of(userMsg(text)), options)
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static AgentState requiredState(
            InMemoryAgentStateStore store, String userId, String sessionId) {
        return store.get(userId, sessionId, "agent_state", AgentState.class).orElseThrow();
    }

    private static List<String> allText(AgentState state) {
        List<String> texts = new ArrayList<>();
        for (Msg message : state.getContext()) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    texts.add(textBlock.getText());
                }
            }
        }
        return texts;
    }

    private static final class TestReActAgentRunner extends BaseReActAgentRunner {

        private final Supplier<ReActAgent> supplier;

        private TestReActAgentRunner(Supplier<ReActAgent> supplier) {
            this.supplier = supplier;
        }

        @Override
        protected ReActAgent buildReActAgent() {
            return supplier.get();
        }
    }

    private static final class DeterministicModel extends ChatModelBase {

        @Override
        public String getModelName() {
            return "deterministic";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }
}
