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
package io.agentscope.core.llm.interfacesweb.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Responses DTO Tests")
class ResponsesDtoTest {

    @Test
    @DisplayName("Should expose Responses output content accessors")
    void shouldExposeResponsesOutputContentAccessors() {
        ResponsesOutputContent defaults = new ResponsesOutputContent();
        ResponsesOutputContent content = new ResponsesOutputContent("output_text", "hello");

        assertNull(defaults.getType());
        assertEquals("output_text", content.getType());
        assertEquals("hello", content.getText());

        content.setType("summary_text");
        content.setText("summary");

        assertEquals("summary_text", content.getType());
        assertEquals("summary", content.getText());
    }

    @Test
    @DisplayName("Should expose Responses output item accessors")
    void shouldExposeResponsesOutputItemAccessors() {
        ResponsesOutputContent content = new ResponsesOutputContent("output_text", "hello");
        ResponsesOutputItem item = new ResponsesOutputItem();

        item.setId("item_1");
        item.setType("message");
        item.setStatus("completed");
        item.setRole("assistant");
        item.setContent(List.of(content));
        item.setName("lookup");
        item.setArguments("{\"q\":\"weather\"}");
        item.setCallId("call_1");

        assertEquals("item_1", item.getId());
        assertEquals("message", item.getType());
        assertEquals("completed", item.getStatus());
        assertEquals("assistant", item.getRole());
        assertEquals(List.of(content), item.getContent());
        assertEquals("lookup", item.getName());
        assertEquals("{\"q\":\"weather\"}", item.getArguments());
        assertEquals("call_1", item.getCallId());
    }

    @Test
    @DisplayName("Should expose Responses response accessors")
    void shouldExposeResponsesResponseAccessors() {
        ResponsesOutputItem item = new ResponsesOutputItem();
        ResponsesUsage usage = new ResponsesUsage(3, 5);
        ResponsesResponse response = new ResponsesResponse();

        response.setId("resp_1");
        response.setObject("response");
        response.setStatus("completed");
        response.setModel("test-model");
        response.setOutput(List.of(item));
        response.setUsage(usage);
        response.setError(Map.of("type", "none"));
        response.setCreatedAt(42L);
        response.setOutputText("hello");

        assertEquals("resp_1", response.getId());
        assertEquals("response", response.getObject());
        assertEquals("completed", response.getStatus());
        assertEquals("test-model", response.getModel());
        assertEquals(List.of(item), response.getOutput());
        assertSame(usage, response.getUsage());
        assertEquals(Map.of("type", "none"), response.getError());
        assertEquals(42L, response.getCreatedAt());
        assertEquals("hello", response.getOutputText());
    }

    @Test
    @DisplayName("Should calculate Responses usage totals")
    void shouldCalculateResponsesUsageTotals() {
        ResponsesUsage defaults = new ResponsesUsage();
        ResponsesUsage both = new ResponsesUsage(3, 5);
        ResponsesUsage inputOnly = new ResponsesUsage(3, null);
        ResponsesUsage outputOnly = new ResponsesUsage(null, 5);
        ResponsesUsage empty = new ResponsesUsage(null, null);

        assertNull(defaults.getInputTokens());
        assertEquals(8, both.getTotalTokens());
        assertEquals(3, inputOnly.getTotalTokens());
        assertEquals(5, outputOnly.getTotalTokens());
        assertNull(empty.getTotalTokens());

        empty.setInputTokens(7);
        empty.setOutputTokens(11);
        empty.setTotalTokens(18);

        assertEquals(7, empty.getInputTokens());
        assertEquals(11, empty.getOutputTokens());
        assertEquals(18, empty.getTotalTokens());
    }

    @Test
    @DisplayName("Should expose Responses stream event accessors")
    void shouldExposeResponsesStreamEventAccessors() {
        ResponsesStreamEvent defaults = new ResponsesStreamEvent();
        ResponsesStreamEvent event = new ResponsesStreamEvent("response.output_text.delta");
        ResponsesResponse response = new ResponsesResponse();
        ResponsesOutputItem item = new ResponsesOutputItem();
        Map<String, Object> error = Map.of("message", "boom");

        assertNull(defaults.getType());
        event.setResponse(response);
        event.setItem(item);
        event.setDelta("hel");
        event.setError(error);
        event.setResponseId("resp_1");
        event.setOutputIndex(1);
        event.setContentIndex(2);
        event.setItemId("item_1");

        assertEquals("response.output_text.delta", event.getType());
        assertSame(response, event.getResponse());
        assertSame(item, event.getItem());
        assertEquals("hel", event.getDelta());
        assertEquals(error, event.getError());
        assertEquals("resp_1", event.getResponseId());
        assertEquals(1, event.getOutputIndex());
        assertEquals(2, event.getContentIndex());
        assertEquals("item_1", event.getItemId());

        event.setType("response.completed");
        assertEquals("response.completed", event.getType());
    }
}
