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
package io.agentscope.core.agui.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiEventEncoder.
 */
class AguiEventEncoderTest {

    private AguiEventEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new AguiEventEncoder();
    }

    @Test
    void testEncodeRunStartedEvent() {
        AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.startsWith("data: "));
        assertTrue(sse.endsWith("\n\n"));
        assertTrue(sse.contains("\"type\":\"RUN_STARTED\""));
        assertTrue(sse.contains("\"threadId\":\"thread-1\""));
        assertTrue(sse.contains("\"runId\":\"run-1\""));
    }

    @Test
    void testEncodeRunFinishedEvent() {
        AguiEvent.RunFinished event = new AguiEvent.RunFinished("thread-2", "run-2");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"RUN_FINISHED\""));
        assertTrue(sse.contains("\"threadId\":\"thread-2\""));
    }

    @Test
    void testEncodeTextMessageStartEvent() {
        AguiEvent.TextMessageStart event =
                new AguiEvent.TextMessageStart("thread-1", "run-1", "msg-1", "assistant");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_START\""));
        assertTrue(sse.contains("\"messageId\":\"msg-1\""));
        assertTrue(sse.contains("\"role\":\"assistant\""));
    }

    @Test
    void testEncodeTextMessageContentEvent() {
        AguiEvent.TextMessageContent event =
                new AguiEvent.TextMessageContent("thread-1", "run-1", "msg-1", "Hello world");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""));
        assertTrue(sse.contains("\"delta\":\"Hello world\""));
    }

    @Test
    void testEncodeTextMessageEndEvent() {
        AguiEvent.TextMessageEnd event = new AguiEvent.TextMessageEnd("thread-1", "run-1", "msg-1");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_END\""));
        assertTrue(sse.contains("\"messageId\":\"msg-1\""));
    }

    @Test
    void testEncodeToolCallStartEvent() {
        AguiEvent.ToolCallStart event =
                new AguiEvent.ToolCallStart("thread-1", "run-1", "tc-1", "get_weather");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TOOL_CALL_START\""));
        assertTrue(sse.contains("\"toolCallId\":\"tc-1\""));
        assertTrue(sse.contains("\"toolCallName\":\"get_weather\""));
    }

    @Test
    void testEncodeToolCallArgsEvent() {
        AguiEvent.ToolCallArgs event =
                new AguiEvent.ToolCallArgs("thread-1", "run-1", "tc-1", "{\"city\":\"Beijing\"}");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TOOL_CALL_ARGS\""));
        assertTrue(sse.contains("\"toolCallId\":\"tc-1\""));
        assertTrue(sse.contains("\"delta\":\"{\\\"city\\\":\\\"Beijing\\\"}\""));
    }

    @Test
    void testEncodeToolCallEndEvent() {
        AguiEvent.ToolCallEnd event =
                new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", "Success");

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TOOL_CALL_END\""));
        assertTrue(sse.contains("\"result\":\"Success\""));
    }

    @Test
    void testEncodeStateSnapshotEvent() {
        AguiEvent.StateSnapshot event =
                new AguiEvent.StateSnapshot("thread-1", "run-1", Map.of("key", "value"));

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"STATE_SNAPSHOT\""));
        assertTrue(sse.contains("\"snapshot\""));
        assertTrue(sse.contains("\"key\":\"value\""));
    }

    @Test
    void testEncodeStateDeltaEvent() {
        List<AguiEvent.JsonPatchOperation> operations =
                List.of(AguiEvent.JsonPatchOperation.add("/path", "value"));
        AguiEvent.StateDelta event = new AguiEvent.StateDelta("thread-1", "run-1", operations);

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"STATE_DELTA\""));
        assertTrue(sse.contains("\"delta\""));
        assertTrue(sse.contains("\"op\":\"add\""));
    }

    @Test
    void testEncodeRawEvent() {
        AguiEvent.Raw event =
                new AguiEvent.Raw("thread-1", "run-1", Map.of("error", "Something went wrong"));

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"RAW\""));
        assertTrue(sse.contains("\"rawEvent\""));
        assertTrue(sse.contains("\"error\":\"Something went wrong\""));
    }

    @Test
    void testEncodeToJson() {
        AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");

        String json = encoder.encodeToJson(event);

        assertNotNull(json);
        assertTrue(json.startsWith(" "), "Should start with space for SSE compatibility");
        assertTrue(json.contains("\"type\":\"RUN_STARTED\""));
        assertTrue(!json.contains("data:"), "Should not contain SSE data prefix");
        assertTrue(!json.endsWith("\n\n"), "Should not have double newline");
    }

    @Test
    void testEncodeComment() {
        String comment = encoder.encodeComment("test comment");

        assertEquals(": test comment\n\n", comment);
    }

    @Test
    void testKeepAlive() {
        String keepAlive = encoder.keepAlive();

        assertEquals(": keep-alive\n\n", keepAlive);
    }

    @Test
    void testEncodeEventWithNullResult() {
        AguiEvent.ToolCallEnd event = new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", null);

        String sse = encoder.encode(event);

        assertNotNull(sse);
        assertTrue(sse.contains("\"type\":\"TOOL_CALL_END\""));
        assertTrue(sse.contains("\"result\":null"));
    }

    @Test
    void testEncodeToJsonWithComplexEvent() throws JsonProcessingException {
        AguiEvent.StateSnapshot event =
                new AguiEvent.StateSnapshot(
                        "thread-1",
                        "run-1",
                        Map.of("nested", Map.of("key1", "value1", "key2", 42)));

        String json = encoder.encodeToJson(event);

        assertNotNull(json);
        assertTrue(json.startsWith(" "));

        // Verify it's valid JSON (without the leading space)
        AguiEvent decoded = JsonUtils.getJsonCodec().fromJson(json.trim(), AguiEvent.class);
        assertNotNull(decoded);
        assertEquals(AguiEventType.STATE_SNAPSHOT, decoded.getType());
    }
}
