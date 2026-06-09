/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiSnapshotRequest;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ThreadSessionSnapshotProvider}. */
class ThreadSessionSnapshotProviderTest {

    @Test
    void testMessagesSnapshotFromThreadSessionMemory() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(Msg.builder().id("msg-1").role(MsgRole.USER).textContent("Hello").build());
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(registry, sessionManager, true);

        List<AguiMessage> messages = provider.messagesSnapshot(request("default", "thread-1"));

        assertEquals(1, messages.size());
        assertEquals("msg-1", messages.get(0).getId());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Hello", messages.get(0).getContent());
    }

    @Test
    void testMessagesSnapshotSplitsThinkingBlocksIntoReasoningMessages() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(
                        Msg.builder()
                                .id("msg-1")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("Need to call a weather tool")
                                                        .build(),
                                                TextBlock.builder()
                                                        .text("Beijing is rainy")
                                                        .build()))
                                .build());
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(
                        registry,
                        sessionManager,
                        true,
                        AguiAdapterConfig.builder().enableReasoning(true).build());

        List<AguiMessage> messages = provider.messagesSnapshot(request("default", "thread-1"));

        assertEquals(2, messages.size());
        assertEquals("msg-1", messages.get(0).getId());
        assertEquals("reasoning", messages.get(0).getRole());
        assertEquals("Need to call a weather tool", messages.get(0).getContent());
        assertEquals("msg-1", messages.get(1).getId());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Beijing is rainy", messages.get(1).getContent());
    }

    @Test
    void testMessagesSnapshotSkipsThinkingBlocksWhenReasoningDisabled() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(
                        Msg.builder()
                                .id("msg-1")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("Do not expose this")
                                                        .build(),
                                                TextBlock.builder().text("Visible answer").build()))
                                .build());
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(
                        registry,
                        sessionManager,
                        true,
                        AguiAdapterConfig.builder().enableReasoning(false).build());

        List<AguiMessage> messages = provider.messagesSnapshot(request("default", "thread-1"));

        assertEquals(1, messages.size());
        assertEquals("msg-1", messages.get(0).getId());
        assertEquals("assistant", messages.get(0).getRole());
        assertEquals("Visible answer", messages.get(0).getContent());
        assertFalse(messages.stream().anyMatch(AguiMessage::isReasoningMessage));
    }

    @Test
    void testMessagesSnapshotReturnsEmptyWhenAgentIdDoesNotMatchSession() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        registry.registerFactory("chat", this::createAgent);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(Msg.builder().id("msg-1").role(MsgRole.USER).textContent("Hello").build());
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(registry, sessionManager, true);

        List<AguiMessage> messages = provider.messagesSnapshot(request("chat", "thread-1"));

        assertTrue(messages.isEmpty());
    }

    @Test
    void testMessagesSnapshotReturnsEmptyWhenServerSideMemoryDisabled() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(registry, sessionManager, false);

        List<AguiMessage> messages = provider.messagesSnapshot(request("default", "thread-1"));

        assertTrue(messages.isEmpty());
    }

    @Test
    void testMessagesSnapshotReturnsEmptyWhenServerSideMemoryDisabledForMissingAgent() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(registry, new ThreadSessionManager(10, 0), false);

        List<AguiMessage> messages = provider.messagesSnapshot(request("missing", "thread-1"));

        assertTrue(messages.isEmpty());
    }

    @Test
    void testThreadSessionManagerReturnsImmutableMessageSnapshot() {
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(Msg.builder().id("msg-1").role(MsgRole.USER).textContent("Hello").build());

        List<Msg> snapshot = sessionManager.getMessages("thread-1", "default");
        agent.getAgentState()
                .contextMutable()
                .add(Msg.builder().id("msg-2").role(MsgRole.ASSISTANT).textContent("Hi").build());

        assertEquals(1, snapshot.size());
        assertEquals("msg-1", snapshot.get(0).getId());
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        snapshot.add(
                                Msg.builder()
                                        .id("msg-3")
                                        .role(MsgRole.USER)
                                        .textContent("Mutable?")
                                        .build()));
    }

    @Test
    void testMessagesSnapshotThrowsWhenAgentIsNotRegistered() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionSnapshotProvider provider =
                new ThreadSessionSnapshotProvider(registry, new ThreadSessionManager(10, 0), true);

        assertThrows(
                AguiException.AgentNotFoundException.class,
                () -> provider.messagesSnapshot(request("missing", "thread-1")));
    }

    private ReActAgent createAgent() {
        return ReActAgent.builder().name("test-agent").build();
    }

    private AguiSnapshotRequest request(String agentId, String threadId) {
        RunAgentInput input = RunAgentInput.builder().threadId(threadId).runId("run-1").build();
        return new AguiSnapshotRequest(agentId, threadId, "run-1", input);
    }
}
