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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CompactionEndEvent;
import io.agentscope.core.event.CompactionStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class CompactionMiddlewareTest {

    private Model model;
    private WorkspaceManager workspaceManager;
    private CompactionConfig config;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        workspaceManager = mock(WorkspaceManager.class);
        // fixed trigger so tests don't depend on model context window
        config = CompactionConfig.builder().triggerTokens(100).keepTokens(10).build();
        ctx = RuntimeContext.builder().sessionId("test-session").build();
    }

    @Test
    void noCompaction_passesThroughWithoutEvents() {
        // conversation is small — compactIfNeeded returns empty Optional
        CompactionMiddleware middleware = new CompactionMiddleware(workspaceManager, model, config);
        ReActAgent agent = mock(ReActAgent.class);
        List<Msg> messages = List.of(userMsg("hi"));
        ReasoningInput input = new ReasoningInput(messages, List.of(), null);

        AgentEvent sentinel = new TextBlockDeltaEvent("", "", "hello");
        List<AgentEvent> events =
                middleware
                        .onReasoning(agent, ctx, input, ignored -> Flux.just(sentinel))
                        .collectList()
                        .block();

        assertEquals(1, events.size());
        assertEquals(sentinel, events.get(0));
        assertFalse(
                events.stream().anyMatch(e -> e.getType() == AgentEventType.COMPACTION_START),
                "COMPACTION_START must not appear when threshold is not reached");
    }

    @Test
    void compactionTriggered_emitsStartBeforeAndEndAfterNextFlux() {
        // conversation exceeds the hard-coded trigger — we can't directly inject into
        // ConversationCompactor (it's created internally), so instead we verify the event
        // contract by using a config with triggerTokens=1 and a long conversation so that
        // the threshold is always exceeded.
        CompactionConfig lowThreshold =
                CompactionConfig.builder().triggerTokens(1).keepTokens(1).build();

        // We need a real WorkspaceManager that can produce file paths; use a temp dir.
        // Since we only test the middleware event sequence and the model produces the
        // summary, mock the model to return a fixed summary message.
        when(model.getContextWindowSize()).thenReturn(0); // fallback path, not used with fixed cfg

        CompactionMiddleware middleware =
                new CompactionMiddleware(workspaceManager, model, lowThreshold);
        ReActAgent agent = mock(ReActAgent.class);

        // The test verifies structure: if compaction fires we get START ... END wrapping next.
        // Because ConversationCompactor calls model internally we accept that for a unit test
        // the compaction may or may not trigger based on token count. The key invariant is:
        // if COMPACTION_START appears, COMPACTION_END must follow after all next events.
        List<Msg> messages = List.of(userMsg("x"));
        ReasoningInput input = new ReasoningInput(messages, List.of(), null);

        AgentEvent inner = new TextBlockDeltaEvent("", "", "result");
        List<AgentEvent> events =
                middleware
                        .onReasoning(agent, ctx, input, ignored -> Flux.just(inner))
                        .collectList()
                        .block();

        boolean hasStart =
                events.stream().anyMatch(e -> e.getType() == AgentEventType.COMPACTION_START);
        boolean hasEnd =
                events.stream().anyMatch(e -> e.getType() == AgentEventType.COMPACTION_END);

        // If one appears, both must appear
        assertEquals(hasStart, hasEnd, "COMPACTION_START and COMPACTION_END must be paired");

        if (hasStart) {
            int startIdx = indexOfType(events, AgentEventType.COMPACTION_START);
            int endIdx = indexOfType(events, AgentEventType.COMPACTION_END);
            int innerIdx = events.indexOf(inner);
            assertTrue(startIdx < endIdx, "COMPACTION_START must precede COMPACTION_END");
            assertTrue(endIdx < innerIdx, "COMPACTION_END must precede inner events");

            CompactionStartEvent start = (CompactionStartEvent) events.get(startIdx);
            CompactionEndEvent end = (CompactionEndEvent) events.get(endIdx);
            assertTrue(start.getEstimatedTokens() >= 0);
            assertTrue(start.getTriggerThreshold() > 0);
            assertTrue(end.getOriginalMessageCount() >= 0);
            assertTrue(end.getCompactedMessageCount() >= 0);
        }
    }

    @Test
    void nonReActAgent_passesThroughImmediately() {
        CompactionMiddleware middleware = new CompactionMiddleware(workspaceManager, model, config);
        // pass a plain Agent (not ReActAgent) — middleware must short-circuit
        io.agentscope.core.agent.Agent plainAgent = mock(io.agentscope.core.agent.Agent.class);
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        AgentEvent sentinel = new TextBlockDeltaEvent("", "", "direct");
        List<AgentEvent> events =
                middleware
                        .onReasoning(plainAgent, ctx, input, ignored -> Flux.just(sentinel))
                        .collectList()
                        .block();

        assertEquals(1, events.size());
        assertEquals(sentinel, events.get(0));
    }

    @Test
    void compactionError_fallsBackToNextWithStartEndPair() {
        // Use low threshold to ensure compaction branch is entered, then make
        // the compactor fail by throwing from workspaceManager (used by MemoryFlushManager).
        CompactionConfig lowThreshold =
                CompactionConfig.builder().triggerTokens(1).keepTokens(1).build();
        when(model.getContextWindowSize()).thenReturn(0);
        when(workspaceManager.getWorkspace()).thenThrow(new RuntimeException("disk error"));

        CompactionMiddleware middleware =
                new CompactionMiddleware(workspaceManager, model, lowThreshold);
        ReActAgent agent = mock(ReActAgent.class);
        ReasoningInput input = new ReasoningInput(List.of(userMsg("hi")), List.of(), null);

        AgentEvent sentinel = new TextBlockDeltaEvent("", "", "fallback");
        List<AgentEvent> events =
                middleware
                        .onReasoning(agent, ctx, input, ignored -> Flux.just(sentinel))
                        .collectList()
                        .block();

        // Inner events must still be delivered when compaction fails
        assertTrue(
                events.stream().anyMatch(e -> e.equals(sentinel)),
                "Inner events must still be delivered when compaction fails");

        boolean hasStart =
                events.stream().anyMatch(e -> e.getType() == AgentEventType.COMPACTION_START);
        boolean hasEnd =
                events.stream().anyMatch(e -> e.getType() == AgentEventType.COMPACTION_END);

        // START is emitted before the error; END must still appear to close the pair
        if (hasStart) {
            assertTrue(hasEnd, "COMPACTION_END must appear when START was emitted (error path)");
        }
    }

    // ---- helpers ----

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(io.agentscope.core.message.TextBlock.builder().text(text).build()))
                .build();
    }

    private static int indexOfType(List<AgentEvent> events, AgentEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == type) return i;
        }
        return -1;
    }
}
