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
package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.JournalEventCodecRegistry;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueuedModelProtocolValidationTest {
    @Test
    void invalidJournalRecordsAreRejectedBeforeRecovery() {
        var codecs = new JournalEventCodecRegistry();
        DurableEventSchemas.register(codecs);
        for (var value :
                List.of(
                        Map.of("request", "invalid", "fallback", false, "iteration", 0),
                        Map.of("request", Map.of(), "fallback", false, "iteration", 0),
                        Map.of("request", Map.of("callId", 1), "fallback", false, "iteration", 0),
                        Map.of("request", Map.of("callId", " "), "fallback", false, "iteration", 0),
                        Map.of(
                                "request",
                                Map.of("callId", "call"),
                                "fallback",
                                "false",
                                "iteration",
                                0),
                        Map.of(
                                "request",
                                Map.of("callId", "call"),
                                "fallback",
                                false,
                                "iteration",
                                "zero"),
                        Map.of(
                                "request",
                                Map.of("callId", "call"),
                                "fallback",
                                false,
                                "iteration",
                                -1))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codecs.validate(event(DurableEventSchemas.QUEUED_MODEL, value)));
        }
        assertDoesNotThrow(
                () ->
                        codecs.validate(
                                event(
                                        DurableEventSchemas.QUEUED_MODEL,
                                        Map.of(
                                                "request",
                                                Map.of("callId", "call"),
                                                "fallback",
                                                true,
                                                "iteration",
                                                0))));
        for (Object deadline : List.of("invalid", 0L, -1L)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codecs.validate(
                                    event(
                                            DurableEventSchemas.MODEL_ACCEPTED,
                                            Map.of(
                                                    "modelCallId",
                                                    "call",
                                                    "replyDeadline",
                                                    deadline))));
        }
        assertDoesNotThrow(
                () ->
                        codecs.validate(
                                event(
                                        DurableEventSchemas.MODEL_ACCEPTED,
                                        Map.of("modelCallId", "call", "replyDeadline", 1L))));
    }

    @Test
    void replayedQueueMetadataPreservesConversation() {
        var codecs = new JournalEventCodecRegistry();
        DurableEventSchemas.register(codecs);
        var state = io.agentscope.core.state.AgentState.builder().sessionId("session").build();
        var message = new io.agentscope.core.message.UserMessage("hello");
        state.contextMutable().add(message);
        for (var record :
                List.of(
                        event(
                                DurableEventSchemas.QUEUED_MODEL,
                                Map.of(
                                        "request",
                                        Map.of("callId", "call"),
                                        "fallback",
                                        false,
                                        "iteration",
                                        0)),
                        event(
                                DurableEventSchemas.MODEL_ACCEPTED,
                                Map.of("modelCallId", "call", "replyDeadline", 10)))) {
            codecs.validate(record);
            codecs.applyExtension(state, record);
        }
        org.junit.jupiter.api.Assertions.assertEquals(List.of(message), state.getContext());
        org.junit.jupiter.api.Assertions.assertEquals("session", state.getSessionId());
    }

    @Test
    void invalidOutboundFramesFailBeforeTouchingTurn() {
        var turn = mock(DurableTurn.class);
        assertThrows(
                IllegalArgumentException.class, () -> QueuedModelRequest.begin(turn, "call", "0"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QueuedModelRequest.accept(
                                turn, new Signal("app", "x.app", DurableJson.value(Map.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> QueuedModelRequest.reply("call", 0, null, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> QueuedModelRequest.reply("call", -1, null, true));
        assertThrows(
                IllegalArgumentException.class, () -> QueuedModelRequest.failure("call", 0, " "));
        assertThrows(
                NullPointerException.class,
                () -> QueuedModelRequest.failure("call", "attempt", 0, (Throwable) null));
        verifyNoInteractions(turn);
    }

    @Test
    void emptyTerminalAndFailureFramesHaveDistinctValidShapes() {
        assertDoesNotThrow(
                () ->
                        QueuedModelRequest.validateReply(
                                QueuedModelRequest.reply("call", 0, null, true)));
        assertDoesNotThrow(
                () ->
                        QueuedModelRequest.validateReply(
                                QueuedModelRequest.failure("call", 0, "provider failed")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QueuedModelRequest.validateReply(
                                new Signal(
                                        "bad",
                                        "x.model:call:0",
                                        DurableJson.value(Map.of("error", 1)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QueuedModelRequest.validateReply(
                                new Signal(
                                        "bad",
                                        "x.model:call:0",
                                        DurableJson.value(
                                                Map.of("error", "failed", "last", true)))));
    }

    private static JournalEvent event(String type, Object value) {
        return new JournalEvent(
                2,
                "event",
                1,
                1,
                type,
                "run",
                "turn",
                true,
                JsonUtils.getJsonCodec().toJson(value));
    }
}
