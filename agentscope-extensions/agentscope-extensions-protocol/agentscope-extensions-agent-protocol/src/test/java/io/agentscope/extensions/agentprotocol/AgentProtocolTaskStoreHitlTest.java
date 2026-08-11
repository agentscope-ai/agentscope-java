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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Lightweight HITL path for {@link AgentProtocolTaskStore}: mock {@link HarnessAgent} streams a
 * {@link GenerateReason#PERMISSION_ASKING} result, then a completed reply after resume/deny.
 */
class AgentProtocolTaskStoreHitlTest {

    @TempDir Path tempDir;

    private HarnessAgent agent;
    private ProtocolTaskRepository taskRepository;
    private AgentProtocolTaskStore store;
    private final AtomicInteger streamCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        taskRepository = new WorkspaceProtocolTaskRepository(tempDir);
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);
        store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props);

        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            int n = streamCalls.incrementAndGet();
                            if (n == 1) {
                                return Flux.just(
                                        new AgentStartEvent("sess", null, "worker"),
                                        new AgentResultEvent(askingMsg()),
                                        new AgentEndEvent(null));
                            }
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });
    }

    @Test
    void submit_pausesOnPermissionAsking_andResumeDenyCompletes() throws Exception {
        store.submit("hitl-1", "worker", "please run bash", Map.of("detail", "full"));

        awaitCondition(
                () -> "awaiting_confirm".equals(store.snapshot("hitl-1").get("status")), 5_000);

        Map<String, Object> snap = store.snapshot("hitl-1");
        assertEquals("awaiting_confirm", snap.get("status"));
        assertNotNull(snap.get("pending_confirms"));
        @SuppressWarnings("unchecked")
        List<RemotePendingConfirm> pending =
                (List<RemotePendingConfirm>) snap.get("pending_confirms");
        assertEquals(1, pending.size());
        assertEquals("tc-ask", pending.get(0).getToolCallId());
        assertEquals("bash", pending.get(0).getToolName());
        // Submit context must survive awaiting_confirm so resume can reuse detail/userId.
        assertTrue(store.hasSubmitContext("hitl-1"));

        store.resume("hitl-1", List.of(new RemoteConfirmDecision("tc-ask", false)));

        awaitCondition(() -> "success".equals(store.snapshot("hitl-1").get("status")), 5_000);

        Map<String, Object> done = store.snapshot("hitl-1");
        assertEquals("success", done.get("status"));
        assertEquals("denied and done", done.get("result"));
        assertEquals(2, streamCalls.get());
        assertFalse(store.hasSubmitContext("hitl-1"));
    }

    @Test
    void submitContextClearedOnDirectSuccess() throws Exception {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new AgentStartEvent("sess", null, "worker"),
                                new AgentResultEvent(completedMsg()),
                                new AgentEndEvent(null)));

        store.submit("ok-1", "worker", "hello", Map.of("user_id", "u1", "detail", "status"));
        awaitCondition(() -> "success".equals(store.snapshot("ok-1").get("status")), 5_000);
        assertFalse(store.hasSubmitContext("ok-1"));
    }

    @Test
    void agentResultEvent_carriesPermissionAskingMsg() {
        Msg asking = askingMsg();
        assertEquals(GenerateReason.PERMISSION_ASKING, asking.getGenerateReason());
        ToolUseBlock block = asking.getContentBlocks(ToolUseBlock.class).get(0);
        assertEquals(ToolCallState.ASKING, block.getState());

        AgentEvent event = new AgentResultEvent(asking);
        assertInstanceOf(AgentResultEvent.class, event);
        assertEquals(asking, ((AgentResultEvent) event).getResult());
    }

    private static Msg askingMsg() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id("tc-ask")
                                .name("bash")
                                .input(Map.of("cmd", "rm -rf /"))
                                .state(ToolCallState.ASKING)
                                .build())
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();
    }

    private static Msg completedMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("denied and done").build();
    }

    private static void awaitCondition(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }
}
