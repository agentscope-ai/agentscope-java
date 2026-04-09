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
package io.agentscope.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.socket.client.Socket;
import java.time.Duration;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

@DisplayName("StudioWebSocketClient Tests")
class StudioWebSocketClientTest {

    private StudioConfig config;
    private StudioWebSocketClient client;

    @BeforeEach
    void setUp() {
        config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .build();
        client = new StudioWebSocketClient(config);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Constructor should create client with config")
    void testConstructor() {
        StudioWebSocketClient testClient = new StudioWebSocketClient(config);
        assertNotNull(testClient);
        testClient.close();
    }

    @Test
    @DisplayName("isConnected should return false before connection")
    void testIsConnectedBeforeConnection() {
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("close should be safe to call before connection")
    void testCloseBeforeConnection() {
        // Should not throw
        client.close();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("close should be idempotent")
    void testCloseIdempotent() {
        client.close();
        client.close();
        client.close();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("waitForInput should create pending request")
    void testWaitForInput() {
        String requestId = "test-request-123";

        // This will timeout since no actual WebSocket connection
        StepVerifier.create(client.waitForInput(requestId).timeout(Duration.ofMillis(100)))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("Multiple waitForInput calls should create separate pending requests")
    void testMultipleWaitForInput() {
        String requestId1 = "request-1";
        String requestId2 = "request-2";

        // Both will timeout, but should not interfere with each other
        StepVerifier.create(client.waitForInput(requestId1).timeout(Duration.ofMillis(100)))
                .expectError()
                .verify();

        StepVerifier.create(client.waitForInput(requestId2).timeout(Duration.ofMillis(100)))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("UserInputData should be constructable")
    void testUserInputDataConstructor() {
        StudioWebSocketClient.UserInputData data =
                new StudioWebSocketClient.UserInputData(null, null);
        assertNotNull(data);
    }

    @Test
    @DisplayName("UserInputData getters should return values")
    void testUserInputDataGetters() {
        StudioWebSocketClient.UserInputData data =
                new StudioWebSocketClient.UserInputData(null, null);
        // Should return null since we passed null
        data.getBlocksInput();
        data.getStructuredInput();
    }

    @Test
    @DisplayName("UserInputData with actual values should return them")
    void testUserInputDataWithValues() {
        java.util.List<io.agentscope.core.message.ContentBlock> blocks =
                java.util.List.of(
                        io.agentscope.core.message.TextBlock.builder().text("test").build());
        java.util.Map<String, Object> structured = java.util.Map.of("key", "value");

        StudioWebSocketClient.UserInputData data =
                new StudioWebSocketClient.UserInputData(blocks, structured);

        assertEquals(blocks, data.getBlocksInput());
        assertEquals(structured, data.getStructuredInput());
    }

    @Test
    @DisplayName("waitForInput with same requestId twice should override")
    void testWaitForInputOverride() {
        String requestId = "same-request";

        // First wait
        client.waitForInput(requestId).subscribe();

        // Second wait with same requestId should override the first
        reactor.core.publisher.Mono<StudioWebSocketClient.UserInputData> secondWait =
                client.waitForInput(requestId);

        assertNotNull(secondWait);
    }

    @Test
    @DisplayName("close with null socket should be safe")
    void testCloseWithNullSocket() {
        StudioWebSocketClient newClient = new StudioWebSocketClient(config);
        newClient.close(); // Socket is null, should not throw
    }

    @Test
    @DisplayName("isConnected with null socket should return false")
    void testIsConnectedWithNullSocket() {
        StudioWebSocketClient newClient = new StudioWebSocketClient(config);
        assertFalse(newClient.isConnected());
        newClient.close();
    }

    @Test
    @DisplayName("handleUserInput should process valid input")
    void testHandleUserInput() throws Exception {
        String requestId = "test-request-123";

        // Set up waiting request
        reactor.core.publisher.Mono<StudioWebSocketClient.UserInputData> waitMono =
                client.waitForInput(requestId);

        // Prepare JSONArray for blocksInput
        org.json.JSONArray blocksInput = new org.json.JSONArray();
        org.json.JSONObject textBlock = new org.json.JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", "Hello");
        blocksInput.put(textBlock);

        // Prepare JSONObject for structuredInput
        org.json.JSONObject structuredInput = new org.json.JSONObject();
        structuredInput.put("field1", "value1");

        // Call handleUserInput
        Object[] args = new Object[] {requestId, blocksInput, structuredInput};
        client.handleUserInput(args);

        // Verify the result
        StepVerifier.create(waitMono.timeout(Duration.ofSeconds(1)))
                .assertNext(
                        data -> {
                            assertNotNull(data.getBlocksInput());
                            assertNotNull(data.getStructuredInput());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("handleUserInput with null structured input should work")
    void testHandleUserInputWithNullStructured() {
        String requestId = "test-request-456";

        reactor.core.publisher.Mono<StudioWebSocketClient.UserInputData> waitMono =
                client.waitForInput(requestId);

        org.json.JSONArray blocksInput = new org.json.JSONArray();
        Object[] args = new Object[] {requestId, blocksInput, null};
        client.handleUserInput(args);

        StepVerifier.create(waitMono.timeout(Duration.ofSeconds(1)))
                .assertNext(
                        data -> {
                            assertNotNull(data.getBlocksInput());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("parseContentBlocks should parse valid JSON array")
    void testParseContentBlocks() throws Exception {
        org.json.JSONArray jsonArray = new org.json.JSONArray();
        org.json.JSONObject textBlock = new org.json.JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", "Test content");
        jsonArray.put(textBlock);

        java.util.List<io.agentscope.core.message.ContentBlock> blocks =
                client.parseContentBlocks(jsonArray);

        assertNotNull(blocks);
        assertEquals(1, blocks.size());
    }

    @Test
    @DisplayName("parseContentBlocks should handle empty array")
    void testParseContentBlocksEmpty() {
        org.json.JSONArray jsonArray = new org.json.JSONArray();
        java.util.List<io.agentscope.core.message.ContentBlock> blocks =
                client.parseContentBlocks(jsonArray);

        assertNotNull(blocks);
        assertEquals(0, blocks.size());
    }

    @Test
    @DisplayName("parseContentBlocks should skip invalid blocks")
    void testParseContentBlocksInvalid() throws Exception {
        org.json.JSONArray jsonArray = new org.json.JSONArray();
        org.json.JSONObject invalidBlock = new org.json.JSONObject();
        invalidBlock.put("invalid", "data");
        jsonArray.put(invalidBlock);

        java.util.List<io.agentscope.core.message.ContentBlock> blocks =
                client.parseContentBlocks(jsonArray);

        assertNotNull(blocks);
        // Invalid block should be skipped
    }

    @Test
    @DisplayName("jsonObjectToMap should convert JSONObject to Map")
    void testJsonObjectToMap() throws Exception {
        org.json.JSONObject jsonObject = new org.json.JSONObject();
        jsonObject.put("key1", "value1");
        jsonObject.put("key2", 123);

        java.util.Map<String, Object> map = client.jsonObjectToMap(jsonObject);

        assertNotNull(map);
        assertEquals("value1", map.get("key1"));
        assertEquals(123, map.get("key2"));
    }

    @Test
    @DisplayName("jsonObjectToMap should handle empty JSONObject")
    void testJsonObjectToMapEmpty() {
        org.json.JSONObject jsonObject = new org.json.JSONObject();
        java.util.Map<String, Object> map = client.jsonObjectToMap(jsonObject);

        assertNotNull(map);
        assertEquals(0, map.size());
    }

    @Test
    @DisplayName("Package-private constructor with socket should work")
    void testConstructorWithSocket() {
        io.socket.client.Socket mockSocket =
                org.mockito.Mockito.mock(io.socket.client.Socket.class);
        org.mockito.Mockito.when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient clientWithSocket = new StudioWebSocketClient(config, mockSocket);

        assertNotNull(clientWithSocket);
        assertEquals(true, clientWithSocket.isConnected());
        clientWithSocket.close();
    }

    // ==================== sendStreamEvent Tests ====================

    @Test
    @DisplayName("sendStreamEvent should log warning when socket is null")
    void sendStreamEvent_WithNullSocket_ShouldLogWarning() {
        // Client created without socket - should not throw
        StudioWebSocketClient newClient = new StudioWebSocketClient(config);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);
        newClient.sendStreamEvent(event);
    }

    @Test
    @DisplayName("sendStreamEvent should log warning when socket is not connected")
    void sendStreamEvent_WithDisconnectedSocket_ShouldLogWarning() {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(false);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        verify(mockSocket, never()).emit(any(), any());
    }

    @Test
    @DisplayName("sendStreamEvent should return when event is null")
    void sendStreamEvent_WithNullEvent_ShouldReturn() {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);

        // Should not throw
        socketClient.sendStreamEvent(null);

        verify(mockSocket, never()).emit(any(), any());
    }

    @Test
    @DisplayName("sendStreamEvent should emit event with TextBlock content")
    void sendStreamEvent_WithTextBlock_ShouldEmit() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("TestAgent")
                        .content(TextBlock.builder().text("Hello World").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, true);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        JSONObject payload = payloadCaptor.getValue();
        assertEquals("AGENT_RESULT", payload.getString("eventType"));
        assertTrue(payload.getBoolean("isLast"));
        assertEquals("Hello World", payload.getString("text"));
        assertEquals("ASSISTANT", payload.getString("role"));
        assertEquals("TestAgent", payload.getString("name"));
    }

    @Test
    @DisplayName("sendStreamEvent should emit event with ThinkingBlock content")
    void sendStreamEvent_WithThinkingBlock_ShouldEmit() throws JSONException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Thinking process").build())
                        .build();
        Event event = new Event(EventType.REASONING, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        JSONObject payload = payloadCaptor.getValue();
        assertEquals("REASONING", payload.getString("eventType"));
        assertEquals("Thinking process", payload.getString("text"));
    }

    @Test
    @DisplayName("sendStreamEvent should emit event with ToolResultBlock content")
    void sendStreamEvent_WithToolResultBlock_ShouldEmit() throws JSONException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        ToolResultBlock toolResult = ToolResultBlock.text("Tool output");
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolResult).build();
        Event event = new Event(EventType.TOOL_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        JSONObject payload = payloadCaptor.getValue();
        assertEquals("TOOL_RESULT", payload.getString("eventType"));
        assertEquals("Tool output", payload.getString("text"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle event with null type")
    void sendStreamEvent_WithNullType_ShouldUseUnknown() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(null, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        assertEquals("UNKNOWN", payloadCaptor.getValue().getString("eventType"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle event with null msg")
    void sendStreamEvent_WithNullMsg_ShouldEmitWithoutMsgFields() throws JSONException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Event event = new Event(EventType.AGENT_RESULT, null, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        JSONObject payload = payloadCaptor.getValue();
        assertEquals("AGENT_RESULT", payload.getString("eventType"));
        assertFalse(payload.has("text"));
        assertFalse(payload.has("role"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle msg with null name")
    void sendStreamEvent_WithNullName_ShouldSkipName() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        assertFalse(payloadCaptor.getValue().has("name"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle TextBlock with null text")
    void sendStreamEvent_WithNullText_ShouldSkipText() {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(null).build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        assertFalse(payloadCaptor.getValue().has("text"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle TextBlock with empty text")
    void sendStreamEvent_WithEmptyText_ShouldSkipText() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        assertFalse(payloadCaptor.getValue().has("text"));
    }

    @Test
    @DisplayName("sendStreamEvent should handle multiple content blocks")
    void sendStreamEvent_WithMultipleBlocks_ShouldConcatenate() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("First").build(),
                                TextBlock.builder().text("Second").build(),
                                ThinkingBlock.builder().thinking("Thinking").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, msg, false);

        socketClient.sendStreamEvent(event);

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        String text = payloadCaptor.getValue().getString("text");
        assertTrue(text.contains("First"));
        assertTrue(text.contains("Second"));
        assertTrue(text.contains("Thinking"));
    }

    @Test
    @DisplayName("sendStreamCompleted should log warning when socket is null")
    void sendStreamCompleted_WithNullSocket_ShouldLogWarning() {
        StudioWebSocketClient newClient = new StudioWebSocketClient(config);

        // Should not throw
        newClient.sendStreamCompleted();
    }

    @Test
    @DisplayName("sendStreamCompleted should log warning when socket is not connected")
    void sendStreamCompleted_WithDisconnectedSocket_ShouldLogWarning() {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(false);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);

        // Should not throw
        socketClient.sendStreamCompleted();

        verify(mockSocket, never()).emit(any(), any());
    }

    @Test
    @DisplayName("sendStreamCompleted should emit completed event")
    void sendStreamCompleted_WithConnectedSocket_ShouldEmit() throws Exception {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);

        socketClient.sendStreamCompleted();

        ArgumentCaptor<JSONObject> payloadCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockSocket, times(1)).emit(any(), payloadCaptor.capture());

        assertEquals("completed", payloadCaptor.getValue().getString("status"));
    }

    @Test
    @DisplayName("sendStreamCompleted should handle emit exception gracefully")
    void sendStreamCompleted_WithEmitException_ShouldLogError() {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.connected()).thenReturn(true);
        when(mockSocket.emit(any(), any())).thenThrow(new RuntimeException("Emit failed"));

        StudioWebSocketClient socketClient = new StudioWebSocketClient(config, mockSocket);

        // Should not throw
        socketClient.sendStreamCompleted();
    }
}
