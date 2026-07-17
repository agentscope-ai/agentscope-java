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
package io.agentscope.core.a2a.server.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cross-module contract tests for the AgentEvent -> A2A -> Msg seam. */
class AgentEventA2aRoundTripContractTest {

    private RequestContext context;
    private AgentEmitter emitter;
    private WireArtifactState wireArtifact;
    private AgentEventA2aEncoder encoder;

    @BeforeEach
    void setUp() {
        context = mock(RequestContext.class);
        emitter = mock(AgentEmitter.class);
        wireArtifact = new WireArtifactState();
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getContextId()).thenReturn("context-1");
        when(emitter.getTaskId()).thenReturn("task-1");
        when(emitter.getContextId()).thenReturn("context-1");
        doAnswer(
                        invocation -> {
                            wireArtifact.apply(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    invocation.getArgument(3),
                                    invocation.getArgument(4),
                                    invocation.getArgument(5));
                            return null;
                        })
                .when(emitter)
                .addArtifact(any(), anyString(), anyString(), any(), anyBoolean(), anyBoolean());
        encoder =
                AgentEventA2aEncoder.streaming(
                        context, AgentExecuteProperties.builder().build(), emitter);
    }

    @Test
    void roundTripsStreamingContractAndFinalResultAcrossServerAndClient() {
        encoder.onNext(
                sourced(new TextBlockDeltaEvent("reply-1", "text-1", "Hel"), "main/researcher"));
        encoder.onNext(
                sourced(new TextBlockDeltaEvent("reply-1", "text-1", "lo"), "main/researcher"));
        encoder.onNext(
                sourced(
                        new ThinkingBlockDeltaEvent("reply-1", "thinking-1", "pla"),
                        "main/researcher"));
        encoder.onNext(
                sourced(
                        new ThinkingBlockDeltaEvent("reply-1", "thinking-1", "n"),
                        "main/researcher"));

        encoder.onNext(
                sourced(new ToolCallStartEvent("reply-1", "call-1", "lookup"), "main/tools"));
        encoder.onNext(
                new ToolCallDeltaEvent("reply-1", "call-1", "lookup", "{\"query\":\"java\"}"));
        encoder.onNext(new ToolCallEndEvent("reply-1", "call-1", "lookup"));

        DataBlock chart =
                DataBlock.builder()
                        .id("data-1")
                        .name("chart.png")
                        .source(new URLSource("https://example.test/chart.png", "image/png"))
                        .build();
        encoder.onNext(
                sourced(new ToolResultStartEvent("reply-1", "call-1", "lookup"), "main/tools"));
        encoder.onNext(new ToolResultTextDeltaEvent("reply-1", "call-1", "lookup", "created"));
        encoder.onNext(new ToolResultDataDeltaEvent("reply-1", "call-1", "lookup", chart));
        encoder.onNext(
                new ToolResultEndEvent("reply-1", "call-1", "lookup", ToolResultState.SUCCESS));

        Msg streamed = MessageConvertUtil.convertFromArtifact(wireArtifact.snapshot(), "agent");

        assertEquals(4, streamed.getContent().size());
        assertEquals(
                "Hello", assertInstanceOf(TextBlock.class, streamed.getContent().get(0)).getText());
        assertEquals(
                "plan",
                assertInstanceOf(ThinkingBlock.class, streamed.getContent().get(1)).getThinking());
        ToolUseBlock toolUse = assertInstanceOf(ToolUseBlock.class, streamed.getContent().get(2));
        assertEquals("call-1", toolUse.getId());
        assertEquals("lookup", toolUse.getName());
        assertEquals(Map.of("query", "java"), toolUse.getInput());
        assertEquals(
                "main/tools",
                toolUse.getMetadata().get(MessageConstants.EVENT_SOURCE_METADATA_KEY));
        ToolResultBlock toolResult =
                assertInstanceOf(ToolResultBlock.class, streamed.getContent().get(3));
        assertEquals(ToolResultState.SUCCESS, toolResult.getState());
        assertEquals(
                "created",
                assertInstanceOf(TextBlock.class, toolResult.getOutput().get(0)).getText());
        DataBlock decodedChart = assertInstanceOf(DataBlock.class, toolResult.getOutput().get(1));
        assertEquals("data-1", decodedChart.getId());
        assertEquals(
                "https://example.test/chart.png",
                assertInstanceOf(URLSource.class, decodedChart.getSource()).getUrl());
        assertEquals(
                "main/tools",
                toolResult.getMetadata().get(MessageConstants.EVENT_SOURCE_METADATA_KEY));

        encoder.onNext(sourced(new AgentEndEvent(null), "main/round-trip-child"));
        encoder.onNext(new AgentResultEvent(assistant("reply-1", "final answer")));
        encoder.onComplete();

        Msg completed = MessageConvertUtil.convertFromArtifact(wireArtifact.snapshot(), "agent");
        assertTrue(wireArtifact.lastChunk);
        assertEquals(1, completed.getContent().size());
        assertEquals(
                "final answer",
                assertInstanceOf(TextBlock.class, completed.getContent().get(0)).getText());
        verify(emitter, times(1)).complete();
    }

    @Test
    void roundTripsUnsourcedTopLevelDeltasAsOneClientBlock() {
        encoder.onNext(new TextBlockDeltaEvent("reply-1", "text-1", "Hel"));
        encoder.onNext(new TextBlockDeltaEvent("reply-1", "text-1", "lo"));

        Msg streamed = MessageConvertUtil.convertFromArtifact(wireArtifact.snapshot(), "agent");

        assertEquals(1, streamed.getContent().size());
        assertEquals(
                "Hello", assertInstanceOf(TextBlock.class, streamed.getContent().get(0)).getText());
    }

    private static <T extends AgentEvent> T sourced(T event, String source) {
        event.withSource(source);
        return event;
    }

    private static Msg assistant(String id, String text) {
        return Msg.builder()
                .id(id)
                .name("agent")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class WireArtifactState {

        private String artifactId;
        private String name;
        private Map<String, Object> metadata = Map.of();
        private final List<Part<?>> parts = new ArrayList<>();
        private boolean lastChunk;

        private void apply(
                List<Part<?>> emittedParts,
                String emittedArtifactId,
                String emittedName,
                Map<String, Object> emittedMetadata,
                Boolean append,
                Boolean emittedLastChunk) {
            if (!Boolean.TRUE.equals(append)) {
                parts.clear();
            }
            parts.addAll(emittedParts);
            artifactId = emittedArtifactId;
            name = emittedName;
            metadata = emittedMetadata == null ? Map.of() : new LinkedHashMap<>(emittedMetadata);
            lastChunk = Boolean.TRUE.equals(emittedLastChunk);
        }

        private Artifact snapshot() {
            return Artifact.builder()
                    .artifactId(artifactId)
                    .name(name)
                    .parts(new ArrayList<>(parts))
                    .metadata(metadata)
                    .build();
        }
    }
}
