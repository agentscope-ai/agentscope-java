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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableTypes.EventInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentScopeToolEventsTest {
    private final AgentScopeMessageEvents messages = new AgentScopeMessageEvents();
    private AgentScopeToolEvents tools = new AgentScopeToolEvents();
    private long sequence;

    @Test
    void unconfirmedEffectRemainsUnknownUntilExplicitReconciliation() {
        var unknown = accept("action/end", Map.of("toolCallId", "call"));
        assertEquals("UNKNOWN", unknown.get(0).data().path("status").asText());
        tools = new AgentScopeToolEvents(tools);
        var fallback = ToolResultBlock.error("connection lost").withIdAndName("call", "job");
        assertTrue(
                accept(
                                "tool/result",
                                Map.of(
                                        "message",
                                        ToolResultMessage.builder().result(fallback).build()))
                        .isEmpty());
        assertTrue(
                accept("presentation/tool_result", Map.of("toolCallId", "call", "status", "ERROR"))
                        .isEmpty());
        assertTrue(accept("tool/chunk", Map.of("toolCallId", "call", "chunk", fallback)).isEmpty());
        var confirmed =
                ToolResultBlock.text("operation completed")
                        .withIdAndName("call", "job")
                        .withState(ToolResultState.SUCCESS);
        var resolved =
                accept(
                        "tool/result",
                        Map.of(
                                "reconciled",
                                true,
                                "message",
                                ToolResultMessage.builder().result(confirmed).build()));
        assertEquals("SUCCEEDED", resolved.get(0).data().path("status").asText());
        assertEquals(
                "operation completed",
                resolved.get(0).data().path("content").path(0).path("text").asText());
    }

    @Test
    void returnDirectPlaceholderDoesNotReplaceObservedOutputAfterRebuildingMapper() {
        var output = ToolResultBlock.text("actual output").withIdAndName("call", "job");
        var fact =
                event(
                        "action/end",
                        Map.of(
                                "toolCallId",
                                "call",
                                "result",
                                JsonUtils.getJsonCodec().convertValue(output, Map.class)));
        var result = tools.accept(fact, messages);
        assertEquals(
                "actual output",
                result.get(0).data().path("content").path(0).path("text").asText());
        tools = new AgentScopeToolEvents();
        tools.accept(fact, messages);
        var placeholder =
                ToolResultBlock.text("Result delivered directly to user")
                        .withIdAndName("call", "job")
                        .withState(ToolResultState.SUCCESS);
        assertTrue(
                accept(
                                "tool/result",
                                Map.of(
                                        "message",
                                        ToolResultMessage.builder().result(placeholder).build()))
                        .isEmpty());
        var completed =
                accept(
                        "presentation/tool_result",
                        Map.of("toolCallId", "call", "status", "SUCCESS"));
        assertEquals("SUCCEEDED", completed.get(0).data().path("status").asText());
        assertFalse(
                completed.get(0).data().has("content"),
                "The terminal fact retains the existing output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"action/end", "interaction/resolved"})
    void nativeUnknownDetailsTakePrecedenceOverNormalStreamCompletion(String type) {
        var result =
                ToolResultBlock.text("Outcome unavailable")
                        .withIdAndName("call", "job")
                        .withState(ToolResultState.SUCCESS)
                        .withExecutionDetails(
                                new ToolExecutionDetails(
                                        "process",
                                        ToolExecutionDetails.Outcome.UNKNOWN,
                                        null,
                                        false));
        var observed =
                accept(
                        type,
                        Map.of(
                                "toolCallId",
                                "call",
                                "kind",
                                "external_execution",
                                "result",
                                JsonUtils.getJsonCodec().convertValue(result, Map.class)));
        assertEquals("UNKNOWN", observed.get(0).data().path("status").asText());
        assertTrue(
                accept(
                                "presentation/tool_result",
                                Map.of("toolCallId", "call", "status", "SUCCESS"))
                        .isEmpty());
    }

    private List<EventInput> accept(String type, Map<String, Object> data) {
        var result = tools.accept(event(type, data), messages);
        result.forEach(value -> DurableProtocol.validate("eventInput", value));
        return result;
    }

    private JournalEvent event(String type, Map<String, Object> data) {
        long position = ++sequence;
        return new JournalEvent(
                JournalEvent.SCHEMA_VERSION,
                "event-" + position,
                position,
                1,
                type,
                "run",
                "turn",
                true,
                JsonUtils.getJsonCodec().toJson(data));
    }
}
