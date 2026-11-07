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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** P0 spike: verify the SessionLog seq reaches the model block events. */
class SessionSeqPropagationTest {

    private static final class ChunkModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "chunked";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() > 0) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(TextBlock.builder().text("").build()))
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ThinkingBlock.builder().thinking("think").build(),
                                            TextBlock.builder().text("he").build()))
                            .build(),
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("llo").build()))
                            .build());
        }
    }

    private static final class ToolModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "tool-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("tool-1")
                                                        .name("echo")
                                                        .input(Map.of("text", "hi"))
                                                        .build()))
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    /** A trivial tool whose result lets the test observe the tool/result boundary. */
    public static final class EchoTool {
        @Tool(name = "echo", description = "Echo the input")
        public String echo(@ToolParam(name = "text", description = "text") String text) {
            return text;
        }
    }

    private static ReActAgent agent(InMemorySessionLogStore store) {
        return ReActAgent.builder()
                .name("seq")
                .model(new ChunkModel())
                .sessionLogStore(store)
                .build();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").runId("run-seq").build();
    }

    @Test
    void modelBlocksCarrySessionLogSourceSeq() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        ReActAgent agent = agent(store);

        List<AgentEvent> deltas =
                agent.streamEvents(List.of(new UserMessage("hi")), context())
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(deltas);
        assertFalse(deltas.isEmpty(), "text deltas must carry a session source seq");
        for (AgentEvent delta : deltas) {
            assertTrue(
                    delta.getMetadata().containsKey(SessionRecorder.SESSION_SOURCE_SEQ_KEY),
                    "every streamed event carries its source seq");
            assertFalse(
                    delta.getMetadata().containsKey(SessionRecorder.SESSION_SEQ_KEY),
                    "streamed chunks are not replay boundaries and must not advance the cursor");
        }
    }

    @Test
    void blocksFromOneChunkShareTheSameSeq() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        ReActAgent agent = agent(store);

        List<Long> allSeqs =
                agent.streamEvents(List.of(new UserMessage("hi")), context())
                        .filter(
                                event ->
                                        event instanceof TextBlockDeltaEvent
                                                || event instanceof ThinkingBlockDeltaEvent)
                        .map(
                                event ->
                                        event.getMetadata()
                                                .get(SessionRecorder.SESSION_SOURCE_SEQ_KEY))
                        .map(Long.class::cast)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(allSeqs);
        assertTrue(
                allSeqs.stream().distinct().count() < allSeqs.size(),
                "blocks from one chunk must share a seq: " + allSeqs);
    }

    @Test
    void onlyCompletedEventsCarryTheCursor() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        ReActAgent agent = agent(store);

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi")), context())
                        .filter(
                                event ->
                                        event instanceof TextBlockDeltaEvent
                                                || event instanceof ThinkingBlockDeltaEvent
                                                || event instanceof ModelCallEndEvent)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(events);
        for (AgentEvent event : events) {
            if (event instanceof ModelCallEndEvent) {
                continue;
            }
            assertFalse(
                    event.getMetadata().containsKey(SessionRecorder.SESSION_SEQ_KEY),
                    "streamed chunks must not advance the replay cursor");
        }
        AgentEvent end = events.get(events.size() - 1);
        assertInstanceOf(ModelCallEndEvent.class, end);
        assertTrue(
                end.getMetadata().containsKey(SessionRecorder.SESSION_SEQ_KEY),
                "the model call end is a replay boundary and carries the cursor");
        assertEquals(
                end.getMetadata().get(SessionRecorder.SESSION_SEQ_KEY),
                end.getMetadata().get(SessionRecorder.SESSION_SOURCE_SEQ_KEY));
    }

    @Test
    void agentEndCarriesTheRunEndCursor() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        ReActAgent agent = agent(store);
        RuntimeContext rc = context();

        List<AgentEvent> ends =
                agent.streamEvents(List.of(new UserMessage("hi")), rc)
                        .filter(AgentEndEvent.class::isInstance)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(ends);
        assertEquals(1, ends.size());
        AgentEvent end = ends.get(0);
        assertTrue(
                end.getMetadata().containsKey(SessionRecorder.SESSION_SEQ_KEY),
                "the agent end is the run/end boundary and must advance the cursor");
        assertEquals(
                end.getMetadata().get(SessionRecorder.SESSION_SEQ_KEY),
                end.getMetadata().get(SessionRecorder.SESSION_SOURCE_SEQ_KEY));

        List<SessionEvent> committed =
                store.open(new SessionKey("u", "seq", "s"), rc).readAfter(0, 1000);
        SessionEvent runEnd = committed.get(committed.size() - 1);
        assertEquals("run/end", runEnd.type());
        assertEquals(runEnd.seq(), end.getMetadata().get(SessionRecorder.SESSION_SEQ_KEY));
    }

    @Test
    void toolResultEndCarriesItsOwnCursorWithoutDuplicatingTheRecord() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTool());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("seq")
                        .model(new ToolModel())
                        .toolkit(toolkit)
                        .sessionLogStore(store)
                        .build();
        RuntimeContext rc = context();

        List<AgentEvent> ends =
                agent.streamEvents(List.of(new UserMessage("hi")), rc)
                        .filter(ToolResultEndEvent.class::isInstance)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(ends);
        assertEquals(1, ends.size());
        AgentEvent end = ends.get(0);
        long seq = (Long) end.getMetadata().get(SessionRecorder.SESSION_SEQ_KEY);
        assertEquals(seq, end.getMetadata().get(SessionRecorder.SESSION_SOURCE_SEQ_KEY));
        List<SessionEvent> committed =
                store.open(new SessionKey("u", "seq", "s"), rc).readAfter(0, 1000);
        List<SessionEvent> results =
                committed.stream().filter(event -> event.type().equals("tool/result")).toList();
        assertEquals(1, results.size(), "the result message must be recorded exactly once");
        assertEquals(seq, results.get(0).seq());
        int resultIndex = committed.indexOf(results.get(0));
        assertTrue(
                committed.subList(0, resultIndex).stream()
                        .anyMatch(event -> event.type().equals("message/assistant")),
                "the assistant tool-call message must precede its result in the log");
    }

    @Test
    void postActingRewriteIsTheOnlyReplayedToolResult() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTool());
        Hook rewritingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent post) {
                            post.setToolResultMsg(
                                    Msg.builder()
                                            .name("assistant")
                                            .role(MsgRole.TOOL)
                                            .content(
                                                    ToolResultBlock.text("rewritten")
                                                            .withIdAndName("tool-1", "echo"))
                                            .build());
                        }
                        return Mono.just(event);
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("seq")
                        .model(new ToolModel())
                        .toolkit(toolkit)
                        .hook(rewritingHook)
                        .sessionLogStore(store)
                        .build();
        RuntimeContext rc = context();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi")), rc)
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events);
        AgentEvent resultEnd =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertTrue(resultEnd.getMetadata().containsKey(SessionRecorder.SESSION_SOURCE_SEQ_KEY));
        assertFalse(
                resultEnd.getMetadata().containsKey(SessionRecorder.SESSION_SEQ_KEY),
                "a provisional result must not advance the client cursor");

        List<SessionEvent> committed =
                store.open(new SessionKey("u", "seq", "s"), rc).readAfter(0, 1000);
        List<SessionEvent> results =
                committed.stream().filter(event -> event.type().equals("tool/result")).toList();
        assertEquals(1, results.size(), "only the post-hook result is conversation history");
        assertTrue(
                String.valueOf(results.get(0).data().get("message")).contains("rewritten"),
                "the recorded result must be the one the hook finalized");
        assertEquals(
                1,
                committed.stream()
                        .filter(event -> event.type().equals("tool/result/provisional"))
                        .count(),
                "the pre-hook result is a provisional cursor record");
    }

    @Test
    void duplicateRecordMessageReturnsTheOriginalSequence() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext rc = context();
        SessionLog log = store.open(new SessionKey("u", "seq", "s"), rc);
        SessionRecorder recorder = new SessionRecorder(log, "turn-1", "run-dup", null);
        try {
            Msg message =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(ToolResultBlock.text("x").withIdAndName("tool-1", "echo"))
                            .build();
            long first = recorder.recordMessage(message);
            recorder.append("step/start", Map.of("stepId", "step-1", "iteration", 1));

            assertEquals(first, recorder.recordMessage(message));
        } finally {
            recorder.close();
        }
    }

    @Test
    void deltaSeqsMatchModelChunkSeqs() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        ReActAgent agent = agent(store);
        RuntimeContext rc = context();

        List<Long> deltaSeqs =
                agent.streamEvents(List.of(new UserMessage("hi")), rc)
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .cast(TextBlockDeltaEvent.class)
                        .map(
                                event ->
                                        event.getMetadata()
                                                .get(SessionRecorder.SESSION_SOURCE_SEQ_KEY))
                        .map(Long.class::cast)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        List<Long> committed =
                store.open(new SessionKey("u", "seq", "s"), rc).readAfter(0, 1000).stream()
                        .filter(event -> event.type().equals("model/chunk"))
                        .map(SessionEvent::seq)
                        .collect(Collectors.toList());

        assertNotNull(deltaSeqs);
        assertEquals(committed, deltaSeqs);
    }
}
