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
package io.agentscope.core.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link Mem0Client}. */
class Mem0ClientTest {

    private MockWebServer mockServer;
    private Mem0Client client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        client = new Mem0Client(baseUrl, "test-api-key");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testConstructorWithBaseUrl() {
        String baseUrl = "http://localhost:8000";
        Mem0Client client = new Mem0Client(baseUrl, "key");
        assertNotNull(client);
    }

    @Test
    void testConstructorWithTrailingSlash() {
        String baseUrl = "http://localhost:8000/";
        Mem0Client client = new Mem0Client(baseUrl, "key");
        assertNotNull(client);
    }

    @Test
    void testConstructorWithCustomTimeout() {
        String baseUrl = "http://localhost:8000";
        Mem0Client client = new Mem0Client(baseUrl, "key", Duration.ofSeconds(30));
        assertNotNull(client);
    }

    @Test
    void testConstructorWithNullApiKey() {
        String baseUrl = "http://localhost:8000";
        Mem0Client client = new Mem0Client(baseUrl, null);
        assertNotNull(client);
    }

    @Test
    void testAddRequestSuccess() throws Exception {
        // Mock successful response
        String responseJson =
                "{"
                        + "\"results\":["
                        + "{\"memory\":\"User prefers dark mode\",\"id\":\"mem_123\"}"
                        + "],"
                        + "\"message\":\"Successfully added memory\""
                        + "}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(
                                        Mem0Message.builder()
                                                .role("user")
                                                .content("I prefer dark mode")
                                                .build()))
                        .userId("user123")
                        .build();

        // Execute and verify
        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(1, response.getResults().size());
                            assertEquals("Successfully added memory", response.getMessage());
                        })
                .verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/v1/memories"));
        assertEquals("Token test-api-key", recordedRequest.getHeader("Authorization"));
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void testAddRequestWithoutApiKey() throws Exception {
        // Create client without API key
        String baseUrl = mockServer.url("/").toString();
        Mem0Client clientNoKey = new Mem0Client(baseUrl, null);

        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        StepVerifier.create(clientNoKey.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify no Authorization header
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals(null, recordedRequest.getHeader("Authorization"));

        clientNoKey.shutdown();
    }

    @Test
    void testAddRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Bad request\"}").setResponseCode(400));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 400")
                                        && error.getMessage().contains("add request"))
                .verify();
    }

    @Test
    void testAddRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("invalid json").setResponseCode(200));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(error -> error.getMessage().contains("Failed to deserialize"))
                .verify();
    }

    @Test
    void testSearchRequestSuccess() throws Exception {
        // Mock successful search response (v2 API returns array directly)
        String responseJson =
                "["
                        + "{"
                        + "\"id\":\"mem_123\","
                        + "\"memory\":\"User prefers dark mode\","
                        + "\"user_id\":\"user_456\","
                        + "\"score\":0.95"
                        + "}"
                        + "]";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query("preferences")
                        .userId("user_456")
                        .topK(10)
                        .build();

        // Execute and verify
        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(1, response.getResults().size());
                            assertEquals("mem_123", response.getResults().get(0).getId());
                            assertEquals(
                                    "User prefers dark mode",
                                    response.getResults().get(0).getMemory());
                            assertEquals(0.95, response.getResults().get(0).getScore());
                        })
                .verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/v2/memories/search"));
    }

    @Test
    void testSearchRequestEmptyResults() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("[]").setResponseCode(200));

        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").userId("user1").build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(0, response.getResults().size());
                        })
                .verifyComplete();
    }

    @Test
    void testSearchRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").userId("user1").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 404")
                                        && error.getMessage().contains("search request"))
                .verify();
    }

    @Test
    void testSearchRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("not json array").setResponseCode(200));

        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").userId("user1").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(error -> error.getMessage().contains("Failed to deserialize"))
                .verify();
    }

    @Test
    void testSearchRequestWithMultipleResults() throws Exception {
        String responseJson =
                "["
                        + "{\"id\":\"mem_1\",\"memory\":\"First memory\",\"score\":0.95},"
                        + "{\"id\":\"mem_2\",\"memory\":\"Second memory\",\"score\":0.85},"
                        + "{\"id\":\"mem_3\",\"memory\":\"Third memory\",\"score\":0.75}"
                        + "]";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").topK(3).build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response.getResults());
                            assertEquals(3, response.getResults().size());
                            assertEquals("mem_1", response.getResults().get(0).getId());
                            assertEquals("mem_2", response.getResults().get(1).getId());
                            assertEquals("mem_3", response.getResults().get(2).getId());
                        })
                .verifyComplete();
    }

    @Test
    void testShutdown() {
        Mem0Client client = new Mem0Client("http://localhost:8000", "key");
        // Should not throw exception
        client.shutdown();
    }

    @Test
    void testRequestBodySerialization() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(
                                        Mem0Message.builder()
                                                .role("user")
                                                .content("Test message")
                                                .name("TestUser")
                                                .build()))
                        .userId("user123")
                        .agentId("agent456")
                        .infer(true)
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request body contains expected fields
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        assertTrue(requestBody.contains("\"agent_id\":\"agent456\""));
        assertTrue(requestBody.contains("\"infer\":true"));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"content\":\"Test message\""));
    }

    @Test
    void testHttpTimeout() {
        // Create client with very short timeout
        String baseUrl = mockServer.url("/").toString();
        Mem0Client shortTimeoutClient = new Mem0Client(baseUrl, "key", Duration.ofMillis(1));

        // Enqueue delayed response
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[]}")
                        .setResponseCode(200)
                        .setBodyDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        // Should timeout
        StepVerifier.create(shortTimeoutClient.add(request)).expectError().verify();

        shortTimeoutClient.shutdown();
    }

    // ==================== Self-hosted Mode Tests ====================

    @Test
    void testConstructorWithSelfHostedApiType() {
        String baseUrl = "http://localhost:8000";
        Mem0Client client =
                new Mem0Client(baseUrl, "key", Mem0ApiType.SELF_HOSTED, Duration.ofSeconds(60));
        assertNotNull(client);
    }

    @Test
    void testAddRequestWithSelfHostedEndpoint() throws Exception {
        // Create self-hosted client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client selfHostedClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.SELF_HOSTED, Duration.ofSeconds(60));

        // Mock successful response
        String responseJson =
                "{"
                        + "\"results\":["
                        + "{\"memory\":\"User prefers dark mode\",\"id\":\"mem_123\"}"
                        + "],"
                        + "\"message\":\"Successfully added memory\""
                        + "}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(
                                        Mem0Message.builder()
                                                .role("user")
                                                .content("I prefer dark mode")
                                                .build()))
                        .userId("user123")
                        .build();

        // Execute and verify
        StepVerifier.create(selfHostedClient.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(1, response.getResults().size());
                            assertEquals("Successfully added memory", response.getMessage());
                        })
                .verifyComplete();

        // Verify request uses self-hosted endpoint
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/memories"));
        assertTrue(!recordedRequest.getPath().contains("/v1/memories")); // Should not use v1
        assertEquals("Token test-api-key", recordedRequest.getHeader("Authorization"));

        selfHostedClient.shutdown();
    }

    @Test
    void testSearchRequestWithSelfHostedEndpoint() throws Exception {
        // Create self-hosted client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client selfHostedClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.SELF_HOSTED, Duration.ofSeconds(60));

        // Mock self-hosted search response (wrapped in {"results": [...]})
        String responseJson =
                "{"
                        + "\"results\":["
                        + "{"
                        + "\"id\":\"mem_123\","
                        + "\"memory\":\"User prefers dark mode\","
                        + "\"user_id\":\"user_456\","
                        + "\"score\":0.95"
                        + "}"
                        + "]"
                        + "}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query("preferences")
                        .userId("user_456")
                        .topK(10)
                        .build();

        // Execute and verify
        StepVerifier.create(selfHostedClient.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(1, response.getResults().size());
                            assertEquals("mem_123", response.getResults().get(0).getId());
                            assertEquals(
                                    "User prefers dark mode",
                                    response.getResults().get(0).getMemory());
                            assertEquals(0.95, response.getResults().get(0).getScore());
                        })
                .verifyComplete();

        // Verify request uses self-hosted endpoint
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/search"));
        assertTrue(!recordedRequest.getPath().contains("/v2/memories/search")); // Should not use v2

        selfHostedClient.shutdown();
    }

    @Test
    void testSearchRequestWithSelfHostedEmptyResults() throws Exception {
        // Create self-hosted client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client selfHostedClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.SELF_HOSTED, Duration.ofSeconds(60));

        // Mock self-hosted empty response (wrapped format)
        String responseJson = "{\"results\":[]}";
        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").userId("user1").build();

        StepVerifier.create(selfHostedClient.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getResults());
                            assertEquals(0, response.getResults().size());
                        })
                .verifyComplete();

        selfHostedClient.shutdown();
    }

    @Test
    void testSearchRequestWithSelfHostedMultipleResults() throws Exception {
        // Create self-hosted client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client selfHostedClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.SELF_HOSTED, Duration.ofSeconds(60));

        // Mock self-hosted response with multiple results (wrapped format)
        String responseJson =
                "{"
                        + "\"results\":["
                        + "{\"id\":\"mem_1\",\"memory\":\"First memory\",\"score\":0.95},"
                        + "{\"id\":\"mem_2\",\"memory\":\"Second memory\",\"score\":0.85},"
                        + "{\"id\":\"mem_3\",\"memory\":\"Third memory\",\"score\":0.75}"
                        + "]"
                        + "}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").topK(3).build();

        StepVerifier.create(selfHostedClient.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response.getResults());
                            assertEquals(3, response.getResults().size());
                            assertEquals("mem_1", response.getResults().get(0).getId());
                            assertEquals("mem_2", response.getResults().get(1).getId());
                            assertEquals("mem_3", response.getResults().get(2).getId());
                        })
                .verifyComplete();

        selfHostedClient.shutdown();
    }

    @Test
    void testPlatformModeUsesCorrectEndpoints() throws Exception {
        // Explicitly create platform client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client platformClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.PLATFORM, Duration.ofSeconds(60));

        // Mock response
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        StepVerifier.create(platformClient.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request uses platform endpoint
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertTrue(recordedRequest.getPath().contains("/v1/memories"));

        platformClient.shutdown();
    }

    @Test
    void testPlatformModeSearchUsesCorrectEndpoint() throws Exception {
        // Explicitly create platform client
        String baseUrl = mockServer.url("/").toString();
        Mem0Client platformClient =
                new Mem0Client(
                        baseUrl, "test-api-key", Mem0ApiType.PLATFORM, Duration.ofSeconds(60));

        // Mock platform search response (direct array)
        String responseJson =
                "[" + "{\"id\":\"mem_1\",\"memory\":\"Test memory\",\"score\":0.9}" + "]";
        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").build();

        StepVerifier.create(platformClient.search(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request uses platform endpoint
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertTrue(recordedRequest.getPath().contains("/v2/memories/search"));

        platformClient.shutdown();
    }

    @Test
    void testDefaultModeIsPlatform() throws Exception {
        // Default constructor should use platform endpoints
        String baseUrl = mockServer.url("/").toString();
        Mem0Client defaultClient = new Mem0Client(baseUrl, "test-api-key");

        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(
                                List.of(Mem0Message.builder().role("user").content("Test").build()))
                        .userId("user1")
                        .build();

        StepVerifier.create(defaultClient.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request uses platform endpoint (default)
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertTrue(recordedRequest.getPath().contains("/v1/memories"));

        defaultClient.shutdown();
    }
}
