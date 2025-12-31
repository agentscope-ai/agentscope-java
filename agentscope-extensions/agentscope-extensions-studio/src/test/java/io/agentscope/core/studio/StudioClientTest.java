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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("StudioClient Tests")
class StudioClientTest {

    private MockWebServer mockServer;
    private StudioClient client;
    private StudioConfig config;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        config =
                StudioConfig.builder()
                        .studioUrl(baseUrl)
                        .project("TestProject")
                        .runName("test_run")
                        .runId("test-run-123")
                        .maxRetries(0) // Disable retries for faster tests
                        .build();

        client = new StudioClient(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.shutdown();
        }
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    @DisplayName("registerRun should send POST request with correct payload")
    void testRegisterRunSuccess() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        Mono<Void> result = client.registerRun();

        StepVerifier.create(result).verifyComplete();

        // Verify request
        RecordedRequest request = mockServer.takeRequest();
        assertNotNull(request);
        assertEquals("/trpc/registerRun", request.getPath());
        assertEquals("POST", request.getMethod());
        assertTrue(request.getBody().size() > 0, "Request body should not be empty");
    }

    @Test
    @DisplayName("registerRun should retry on failure when retries are enabled")
    void testRegisterRunWithRetries() {
        // Create client with retries enabled
        StudioConfig configWithRetries =
                StudioConfig.builder()
                        .studioUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .project("TestProject")
                        .runName("test_run")
                        .maxRetries(2)
                        .build();

        StudioClient retryClient = new StudioClient(configWithRetries);

        // First two requests fail, third succeeds
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        Mono<Void> result = retryClient.registerRun();

        StepVerifier.create(result).verifyComplete();

        retryClient.shutdown();
    }

    @Test
    @DisplayName("pushMessage should send message to Studio")
    void testPushMessageSuccess() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello Studio!").build())
                        .build();

        Mono<Void> result = client.pushMessage(msg);

        StepVerifier.create(result).verifyComplete();

        // Verify request
        RecordedRequest request = mockServer.takeRequest();
        assertNotNull(request);
        assertNotNull(request.getPath());
    }

    @Test
    @DisplayName("pushMessage should fail when server returns error")
    void testPushMessageFailure() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello Studio!").build())
                        .build();

        Mono<Void> result = client.pushMessage(msg);

        // With retries disabled, expect RetryExhaustedException wrapping IOException
        StepVerifier.create(result).expectError().verify();
    }

    @Test
    @DisplayName("requestUserInput should send request and return request ID")
    void testRequestUserInputSuccess() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        Mono<String> result = client.requestUserInput("agent-123", "TestAgent", null);

        StepVerifier.create(result)
                .assertNext(requestId -> assertNotNull(requestId))
                .verifyComplete();

        // Verify request
        RecordedRequest request = mockServer.takeRequest();
        assertNotNull(request);
    }

    @Test
    @DisplayName("requestUserInput with schema should work")
    void testRequestUserInputWithSchema() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // Use a Map instead of Object for JSON serialization
        java.util.Map<String, Object> schema =
                java.util.Map.of("type", "object", "properties", java.util.Map.of());

        Mono<String> result = client.requestUserInput("agent-123", "TestAgent", schema);

        StepVerifier.create(result)
                .assertNext(requestId -> assertNotNull(requestId))
                .verifyComplete();
    }

    @Test
    @DisplayName("shutdown should clean up resources")
    void testShutdown() {
        // Should not throw
        client.shutdown();
    }

    @Test
    @DisplayName("Client should be creatable with config")
    void testConstructor() {
        StudioClient testClient = new StudioClient(config);
        assertNotNull(testClient);
        testClient.shutdown();
    }

    @Test
    @DisplayName("Multiple operations should work in sequence")
    void testMultipleOperations() throws Exception {
        // Register run
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        StepVerifier.create(client.registerRun()).verifyComplete();

        // Push message
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test").build())
                        .build();
        StepVerifier.create(client.pushMessage(msg)).verifyComplete();

        // Request input
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        StepVerifier.create(client.requestUserInput("agent-1", "Agent", null))
                .assertNext(id -> assertNotNull(id))
                .verifyComplete();
    }
}
