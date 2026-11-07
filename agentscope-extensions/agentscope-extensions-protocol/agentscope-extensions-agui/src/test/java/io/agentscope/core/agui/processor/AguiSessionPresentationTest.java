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
package io.agentscope.core.agui.processor;

import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.THREAD_ID;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.append;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.message;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.modelChunk;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.textBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.thinkingBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolResult;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolResultBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolUseBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.writer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Replay projection tests for SessionLog to AG-UI deltas. */
class AguiSessionPresentationTest {

    @Test
    void replaysTextAsStartAndContent() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("Hel")));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("lo")));

            List<AguiEvent> events = replay(log, AguiAdapterConfig.defaultConfig());

            assertEquals(3, events.size());
            AguiEvent.TextMessageStart start =
                    assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(0));
            assertEquals("agui:call-1-text", start.messageId());
            AguiEvent.TextMessageContent first =
                    assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(1));
            assertEquals("Hel", first.delta());
            AguiEvent.TextMessageContent second =
                    assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(2));
            assertEquals("lo", second.delta());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void replaysToolCallAsStartAndArguments() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(
                    log,
                    writer,
                    "model/chunk",
                    modelChunk(
                            "call-1",
                            "chat-1",
                            toolUseBlock("tool-1", "search", Map.of("q", "x"), "{\"q\":")));
            append(
                    log,
                    writer,
                    "model/chunk",
                    modelChunk(
                            "call-1",
                            "chat-1",
                            toolUseBlock("tool-1", "search", Map.of("q", "x"), "\"x\"}")));

            List<AguiEvent> events = replay(log, AguiAdapterConfig.defaultConfig());

            assertEquals(3, events.size());
            AguiEvent.ToolCallStart start =
                    assertInstanceOf(AguiEvent.ToolCallStart.class, events.get(0));
            assertEquals("tool-1", start.toolCallId());
            assertEquals("search", start.toolCallName());
            AguiEvent.ToolCallArgs first =
                    assertInstanceOf(AguiEvent.ToolCallArgs.class, events.get(1));
            assertEquals("{\"q\":", first.delta());
            AguiEvent.ToolCallArgs second =
                    assertInstanceOf(AguiEvent.ToolCallArgs.class, events.get(2));
            assertEquals("\"x\"}", second.delta());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void modelEndClosesStartedTextAndToolCalls() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
            append(
                    log,
                    writer,
                    "model/chunk",
                    modelChunk(
                            "call-1",
                            "chat-1",
                            toolUseBlock("tool-1", "search", Map.of("q", "x"), "{\"q\":\"x\"}")));
            append(
                    log,
                    writer,
                    "model/end",
                    Map.of("modelCallId", "call-1", "status", "completed"));

            List<AguiEvent> events = replay(log, AguiAdapterConfig.defaultConfig());

            assertTrue(events.stream().anyMatch(AguiEvent.TextMessageEnd.class::isInstance));
            assertTrue(events.stream().anyMatch(AguiEvent.ToolCallEnd.class::isInstance));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void toolResultForHistoryToolCallDoesNotRestartToolCall() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(
                    log,
                    writer,
                    "tool/result",
                    message(toolResult("r1", toolResultBlock("tool-1", "search", "ok"))));

            List<AguiEvent> events = replay(log, AguiAdapterConfig.defaultConfig());

            assertEquals(1, events.size());
            AguiEvent.ToolCallResult result =
                    assertInstanceOf(AguiEvent.ToolCallResult.class, events.get(0));
            assertEquals("tool-1", result.toolCallId());
            assertEquals("ok", result.content());
            assertTrue(
                    events.stream().noneMatch(AguiEvent.ToolCallStart.class::isInstance),
                    "a tool call from history must not be restarted");
        } finally {
            log.release(writer);
        }
    }

    @Test
    void toolResultForStartedToolCallClosesAndEmitsResult() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(
                    log,
                    writer,
                    "model/chunk",
                    modelChunk(
                            "call-1",
                            "chat-1",
                            toolUseBlock("tool-1", "search", Map.of("q", "x"), "{\"q\":\"x\"}")));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_result");
            result.put("id", "tool-1");
            result.put("name", "search");
            result.put("output", List.of(textBlock("ok")));
            result.put("state", "success");
            append(log, writer, "action/end", Map.of("toolCallId", "tool-1", "result", result));

            List<AguiEvent> events = replay(log, AguiAdapterConfig.defaultConfig());

            assertTrue(events.stream().anyMatch(AguiEvent.ToolCallEnd.class::isInstance));
            assertTrue(events.stream().anyMatch(AguiEvent.ToolCallResult.class::isInstance));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void replaysPendingInteractionsAndDropsResolvedOnes() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("requestId", "tool-1");
            request.put("kind", "confirmation");
            request.put(
                    "call", toolUseBlock("tool-1", "search", Map.of("q", "x"), "{\"q\":\"x\"}"));
            append(log, writer, "interaction/requested", request);

            AguiSessionPresentation presentation = presentation(log);
            assertEquals(1, presentation.interrupts().size());
            assertEquals("tool-1", presentation.interrupts().get(0).id());

            append(log, writer, "interaction/resolved", Map.of("requestId", "tool-1"));
            assertTrue(presentation(log).interrupts().isEmpty());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void reasoningIsOmittedUnlessEnabled() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(
                    log,
                    writer,
                    "model/chunk",
                    modelChunk("call-1", "chat-1", thinkingBlock("think")));

            assertTrue(replay(log, AguiAdapterConfig.defaultConfig()).isEmpty());

            List<AguiEvent> events =
                    replay(log, AguiAdapterConfig.builder().enableReasoning(true).build());
            assertEquals(2, events.size());
            assertInstanceOf(AguiEvent.ReasoningMessageStart.class, events.get(0));
            AguiEvent.ReasoningMessageContent content =
                    assertInstanceOf(AguiEvent.ReasoningMessageContent.class, events.get(1));
            assertEquals("think", content.delta());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void ignoresSummaryModelCalls() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", "chat-1");
            chunk.put("content", List.of(textBlock("summary")));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("modelCallId", "call-1");
            data.put("purpose", "SUMMARY");
            data.put("chunk", chunk);
            append(log, writer, "model/chunk", data);

            assertTrue(replay(log, AguiAdapterConfig.defaultConfig()).isEmpty());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void tracksActiveRunsFromLifecycleEvents() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            assertFalse(presentation(log).activeRuns().isEmpty());

            append(log, writer, "run/end", Map.of("status", "completed"));
            assertTrue(presentation(log).activeRuns().isEmpty());
        } finally {
            log.release(writer);
        }
    }

    private static List<AguiEvent> replay(SessionLog log, AguiAdapterConfig config) {
        AguiSessionPresentation presentation = newPresentation(config);
        List<AguiEvent> out = new ArrayList<>();
        for (SessionEvent event : log.scan(0, log.head().seq())) {
            out.addAll(presentation.apply(event));
        }
        return out;
    }

    private static AguiSessionPresentation presentation(SessionLog log) {
        return presentation(log, AguiAdapterConfig.defaultConfig());
    }

    private static AguiSessionPresentation presentation(SessionLog log, AguiAdapterConfig config) {
        AguiSessionPresentation presentation = newPresentation(config);
        for (SessionEvent event : log.scan(0, log.head().seq())) {
            presentation.apply(event);
        }
        return presentation;
    }

    private static AguiSessionPresentation newPresentation(AguiAdapterConfig config) {
        return new AguiSessionPresentation(
                THREAD_ID,
                "run-1",
                config,
                RunAgentInput.builder().threadId(THREAD_ID).runId("run-1").build());
    }
}
