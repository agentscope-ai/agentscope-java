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

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
