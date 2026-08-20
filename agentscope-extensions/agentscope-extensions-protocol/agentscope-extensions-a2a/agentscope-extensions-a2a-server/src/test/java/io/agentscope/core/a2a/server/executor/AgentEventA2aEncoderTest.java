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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.hitl.HitlClaimRequest;
import io.agentscope.core.a2a.server.hitl.HitlEncodingContext;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.hitl.LocalHitlResumeCoordinator;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.grpc.mapper.PartMapper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentEventA2aEncoderTest {

    private RequestContext context;
    private AgentEmitter emitter;

    @BeforeEach
    void setUp() {
        context = mock(RequestContext.class);
        emitter = mock(AgentEmitter.class);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getContextId()).thenReturn("context-1");
        when(emitter.getTaskId()).thenReturn("task-1");
        when(emitter.getContextId()).thenReturn("context-1");
    }

    @Test
    void streamsTextDeltaAsMarkedTextPartWithSourceIdentity() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply-1", "block-1", "hello");
        delta.withSource("main/researcher");

        encoder.onNext(delta);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emitter)
                .addArtifact(
                        partsCaptor.capture(),
                        anyString(),
                        eq("agent-response"),
                        any(),
                        eq(false),
                        eq(false));
        TextPart part = assertInstanceOf(TextPart.class, partsCaptor.getValue().get(0));
        assertEquals("hello", part.text());
        assertEquals(Boolean.TRUE, part.metadata().get("_agentscope_stream_chunk"));
        assertEquals("reply-1", part.metadata().get(MessageConstants.MSG_ID_METADATA_KEY));
        assertEquals("block-1", part.metadata().get("_agentscope_block_id"));
        assertEquals("main/researcher", part.metadata().get("_agentscope_event_source"));
    }

    @Test
    void resultReplacesStreamingArtifactAndCompletesExactlyOnce() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        Msg result = assistant("reply-1", "final");

        encoder.onNext(new AgentResultEvent(result));
        encoder.onNext(new AgentEndEvent("reply-1"));
        encoder.onComplete();

        verify(emitter)
                .addArtifact(any(), anyString(), eq("agent-response"), any(), eq(false), eq(true));
        verify(emitter, times(1)).complete();
        verify(emitter, never()).fail(any(Message.class));
    }

    @Test
    void sourcedChildEndDoesNotTerminateParentBeforeItsResult() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        AgentEndEvent childEnd = new AgentEndEvent(null);
        childEnd.withSource("main/round-trip-child");

        encoder.onNext(childEnd);
        encoder.onNext(new AgentResultEvent(assistant("reply-1", "parent final")));
        encoder.onComplete();

        verify(emitter, times(1)).complete();
        verify(emitter, never()).fail(any(Message.class));
    }

    @Test
    void topLevelEndWithoutResultFailsExplicitly() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());

        encoder.onNext(new AgentEndEvent("reply-1"));
        encoder.onComplete();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(emitter, times(1)).fail(messageCaptor.capture());
        TextPart part = assertInstanceOf(TextPart.class, messageCaptor.getValue().parts().get(0));
        assertTrue(part.text().contains("without AgentResultEvent"));
        verify(emitter, never()).complete();
    }

    @Test
    void blockingResultSendsOneMessageWithoutTaskCompletionEvent() {
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.blocking(
                        context, AgentExecuteProperties.builder().build(), emitter);

        encoder.onNext(new AgentResultEvent(assistant("reply-1", "final")));
        encoder.onComplete();

        verify(emitter, times(1)).sendMessage(any(Message.class));
        verify(emitter, never()).complete();
        verify(emitter, never()).fail(any(Message.class));
    }

    @Test
    void completionWithoutResultOrHandoffFailsExplicitly() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());

        encoder.onComplete();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).fail(messageCaptor.capture());
        TextPart part = assertInstanceOf(TextPart.class, messageCaptor.getValue().parts().get(0));
        assertTrue(part.text().contains("without AgentResultEvent"));
    }

    @Test
    void userConfirmationBecomesFinalInputRequiredWithToolPayload() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        ToolUseBlock tool =
                ToolUseBlock.builder().id("call-1").name("delete").input(Map.of("id", 7)).build();

        encoder.onNext(new RequireUserConfirmEvent("reply-1", List.of(tool)));
        encoder.onComplete();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).requiresInput(messageCaptor.capture(), eq(true));
        assertInstanceOf(DataPart.class, messageCaptor.getValue().parts().get(0));
        verify(emitter, never()).fail(any(Message.class));
    }

    @Test
    void durableUserConfirmationEmitsCredentialFreeResolvableHandoffMetadata() {
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlExecutionKey executionKey = new HitlExecutionKey("alice", "agent-a", "context-1");
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.streaming(
                        context,
                        AgentExecuteProperties.builder().build(),
                        emitter,
                        new HitlEncodingContext(
                                coordinator,
                                executionKey,
                                "next-secret-token",
                                Duration.ofDays(7),
                                null));
        ToolUseBlock tool =
                ToolUseBlock.builder().id("call-1").name("delete").input(Map.of("id", 7)).build();

        encoder.onNext(new RequireUserConfirmEvent("reply-1", List.of(tool)));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).requiresInput(messageCaptor.capture(), eq(true));
        Map<String, Object> metadata = messageCaptor.getValue().metadata();
        assertEquals("user-confirm", metadata.get(MessageConstants.HANDOFF_TYPE_METADATA_KEY));
        assertTrue(metadata.containsKey(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertTrue(metadata.containsKey(MessageConstants.HANDOFF_EXPIRES_AT_METADATA_KEY));
        assertEquals(
                "call-1",
                ((Map<?, ?>)
                                ((List<?>)
                                                metadata.get(
                                                        MessageConstants
                                                                .PENDING_TOOLS_METADATA_KEY))
                                        .get(0))
                        .get("toolCallId"));
        assertFalse(messageCaptor.getValue().toString().contains("next-secret-token"));
        assertEquals(
                A2aHandoffType.USER_CONFIRM,
                coordinator
                        .get(String.valueOf(metadata.get(MessageConstants.HANDOFF_ID_METADATA_KEY)))
                        .orElseThrow()
                        .type());
    }

    @Test
    void resumedTurnCanPauseAgainWithAOneTimeRotatedToken() {
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlExecutionKey executionKey = new HitlExecutionKey("alice", "agent-a", "context-1");
        ToolUseBlock firstTool =
                ToolUseBlock.builder().id("call-1").name("first").input(Map.of("step", 1)).build();
        String firstHandoff =
                coordinator
                        .open(
                                new HitlOpenRequest(
                                        "task-1",
                                        "context-1",
                                        executionKey,
                                        A2aHandoffType.USER_CONFIRM,
                                        List.of(firstTool),
                                        "first-token",
                                        Duration.ofDays(7),
                                        null))
                        .handoffId();
        coordinator.claim(new HitlClaimRequest("task-1", "context-1", firstHandoff, "first-token"));
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.streaming(
                        context,
                        AgentExecuteProperties.builder().build(),
                        emitter,
                        new HitlEncodingContext(
                                coordinator,
                                executionKey,
                                "second-token",
                                Duration.ofDays(7),
                                firstHandoff));
        ToolUseBlock secondTool =
                ToolUseBlock.builder().id("call-2").name("second").input(Map.of("step", 2)).build();

        encoder.onNext(new RequireUserConfirmEvent("reply-2", List.of(secondTool)));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).requiresInput(messageCaptor.capture(), eq(true));
        String secondHandoff =
                String.valueOf(
                        messageCaptor
                                .getValue()
                                .metadata()
                                .get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertEquals(
                HitlHandoffStatus.SUPERSEDED, coordinator.get(firstHandoff).orElseThrow().status());
        assertEquals(HitlHandoffStatus.OPEN, coordinator.get(secondHandoff).orElseThrow().status());
        assertEquals(
                HitlHandoffStatus.CLAIMED,
                coordinator
                        .claim(
                                new HitlClaimRequest(
                                        "task-1", "context-1", secondHandoff, "second-token"))
                        .status());
    }

    @Test
    void resumedAgentStreamErrorRequiresRecoveryInsteadOfCommittingAReplayableFailure() {
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlExecutionKey executionKey = new HitlExecutionKey("alice", "agent-a", "context-1");
        String handoffId =
                coordinator
                        .open(
                                new HitlOpenRequest(
                                        "task-1",
                                        "context-1",
                                        executionKey,
                                        A2aHandoffType.USER_CONFIRM,
                                        List.of(
                                                new ToolUseBlock(
                                                        "call-1",
                                                        "approval_probe",
                                                        Map.of("value", 1))),
                                        "resume-token-never-log",
                                        Duration.ofDays(1),
                                        null))
                        .handoffId();
        coordinator.claim(
                new HitlClaimRequest("task-1", "context-1", handoffId, "resume-token-never-log"));
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.streaming(
                        context,
                        AgentExecuteProperties.builder().build(),
                        emitter,
                        new HitlEncodingContext(
                                coordinator,
                                executionKey,
                                "next-token-never-log",
                                Duration.ofDays(1),
                                handoffId));

        encoder.onError(new IllegalStateException("runner failed"));

        verify(emitter).fail(any(Message.class));
        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoffId).orElseThrow().status());
    }

    @Test
    void authMetadataHasNoA2aControlEffect() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        Msg result =
                Msg.builder()
                        .id("reply-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .metadata(Map.of("taskState", "auth-required"))
                        .content(TextBlock.builder().text("login").build())
                        .build();

        encoder.onNext(new AgentResultEvent(result));

        verify(emitter, never()).requiresAuth(any(Message.class), eq(true));
        verify(emitter).complete();
    }

    @Test
    void toolCallDeltasAreAggregatedIntoOneDataPart() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        encoder.onNext(new ToolCallStartEvent("reply-1", "call-1", "lookup"));
        encoder.onNext(new ToolCallDeltaEvent("reply-1", "call-1", "lookup", "{\"query\":"));
        encoder.onNext(new ToolCallDeltaEvent("reply-1", "call-1", "lookup", "\"java\"}"));

        encoder.onNext(new ToolCallEndEvent("reply-1", "call-1", "lookup"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emitter)
                .addArtifact(
                        partsCaptor.capture(),
                        anyString(),
                        eq("agent-response"),
                        any(),
                        anyBoolean(),
                        eq(false));
        DataPart part = assertInstanceOf(DataPart.class, partsCaptor.getValue().get(0));
        assertEquals(Map.of("query", "java"), part.data());
        assertEquals("call-1", part.metadata().get(MessageConstants.TOOL_CALL_ID_METADATA_KEY));
    }

    @Test
    void validToolJsonWithNullsRemainsSdkRoundTripSafe() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        encoder.onNext(new ToolCallStartEvent("reply-1", "call-1", "lookup"));
        encoder.onNext(
                new ToolCallDeltaEvent(
                        "reply-1",
                        "call-1",
                        "lookup",
                        "{\"dummy\":null,\"nested\":{\"keep\":\"yes\",\"drop\":null},"
                                + "\"values\":[\"one\",null,\"two\"]}"));

        encoder.onNext(new ToolCallEndEvent("reply-1", "call-1", "lookup"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emitter)
                .addArtifact(
                        partsCaptor.capture(),
                        anyString(),
                        eq("agent-response"),
                        any(),
                        anyBoolean(),
                        eq(false));
        DataPart part = assertInstanceOf(DataPart.class, partsCaptor.getValue().get(0));
        assertEquals(
                Map.of(
                        "nested", Map.of("keep", "yes"),
                        "values", List.of("one", "two")),
                part.data());
        Part<?> roundTripped =
                assertDoesNotThrow(
                        () -> PartMapper.INSTANCE.fromProto(PartMapper.INSTANCE.toProto(part)));
        assertEquals(part.data(), assertInstanceOf(DataPart.class, roundTripped).data());
    }

    @Test
    void invalidToolJsonIsPreservedAsRawData() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        encoder.onNext(new ToolCallStartEvent("reply-1", "call-1", "lookup"));
        encoder.onNext(new ToolCallDeltaEvent("reply-1", "call-1", "lookup", "{invalid"));

        encoder.onNext(new ToolCallEndEvent("reply-1", "call-1", "lookup"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emitter)
                .addArtifact(
                        partsCaptor.capture(),
                        anyString(),
                        eq("agent-response"),
                        any(),
                        anyBoolean(),
                        eq(false));
        DataPart part = assertInstanceOf(DataPart.class, partsCaptor.getValue().get(0));
        assertEquals("{invalid", part.data());
        assertEquals(Boolean.TRUE, part.metadata().get("_agentscope_tool_arguments_raw"));
    }

    @Test
    void toolResultContentBlocksUseProtobufSafeMapsWithoutJavaClassNames() {
        AgentEventA2aEncoder encoder = streamingEncoder(AgentExecuteProperties.builder().build());
        DataBlock chart =
                DataBlock.builder()
                        .id("data-1")
                        .name("chart.png")
                        .source(new URLSource("https://example.test/chart.png", "image/png"))
                        .build();
        encoder.onNext(new ToolResultStartEvent("reply-1", "call-1", "chart"));
        encoder.onNext(new ToolResultTextDeltaEvent("reply-1", "call-1", "chart", "created"));
        encoder.onNext(new ToolResultDataDeltaEvent("reply-1", "call-1", "chart", chart));

        encoder.onNext(
                new ToolResultEndEvent("reply-1", "call-1", "chart", ToolResultState.SUCCESS));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emitter)
                .addArtifact(
                        partsCaptor.capture(),
                        anyString(),
                        eq("agent-response"),
                        any(),
                        anyBoolean(),
                        eq(false));
        DataPart part = assertInstanceOf(DataPart.class, partsCaptor.getValue().get(0));
        Map<?, ?> data = assertInstanceOf(Map.class, part.data());
        List<?> output =
                assertInstanceOf(
                        List.class, data.get(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY));
        Map<?, ?> text = assertInstanceOf(Map.class, output.get(0));
        Map<?, ?> media = assertInstanceOf(Map.class, output.get(1));
        assertEquals("text", text.get("type"));
        assertEquals("created", text.get("text"));
        assertEquals("data", media.get("type"));
        assertEquals("data-1", media.get("id"));
        assertFalse(part.data().toString().contains("io.agentscope"));
    }

    @Test
    void stopAndMaxIterationsMapToCanceledAndFailed() {
        AgentEventA2aEncoder canceled = streamingEncoder(AgentExecuteProperties.builder().build());
        AgentEventA2aEncoder failed = streamingEncoder(AgentExecuteProperties.builder().build());

        canceled.onNext(new RequestStopEvent("budget reached"));
        failed.onNext(new ExceedMaxItersEvent("reply-1", 10, 10));

        verify(emitter).cancel(any(Message.class));
        verify(emitter).fail(any(Message.class));
    }

    @Test
    void everyGaEventTypeIsClassifiedForWireOrIntentionalSkip() {
        for (AgentEventType type : AgentEventType.values()) {
            assertTrue(
                    AgentEventA2aEncoder.isClassifiedEventType(type),
                    () -> "Unclassified AgentEventType: " + type);
        }
    }

    private AgentEventA2aEncoder streamingEncoder(AgentExecuteProperties properties) {
        return AgentEventA2aEncoder.streaming(context, properties, emitter);
    }

    private static Msg assistant(String id, String text) {
        return Msg.builder()
                .id(id)
                .name("agent")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
