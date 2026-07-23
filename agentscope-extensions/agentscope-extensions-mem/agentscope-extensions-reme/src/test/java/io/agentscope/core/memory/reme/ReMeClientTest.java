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
package io.agentscope.core.memory.reme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link ReMeClient}. */
class ReMeClientTest {

    private MockWebServer mockServer;
    private ReMeClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        client = new ReMeClient(baseUrl);
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
        ReMeClient baseClient = new ReMeClient("http://localhost:8002");
        assertNotNull(baseClient);
        baseClient.shutdown();
    }

    @Test
    void testConstructorWithTrailingSlash() {
        ReMeClient slashClient = new ReMeClient("http://localhost:8002/");
        assertNotNull(slashClient);
        slashClient.shutdown();
    }

    @Test
    void testConstructorWithCustomTimeout() {
        ReMeClient timeoutClient = new ReMeClient("http://localhost:8002", Duration.ofSeconds(30));
        assertNotNull(timeoutClient);
        timeoutClient.shutdown();
    }

    @Test
    void testAddRequestSuccess() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"path\":\"daily/2026-07-04/task-session.md\",\"created\":true,\"modified\":true,\"n_messages\":2,\"source_conversation\":\"session/dialog/task-session.jsonl\",\"index\":{\"updated\":true}}}")
                        .setResponseCode(200));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .sessionId("task-session")
                        .messages(
                                List.of(
                                        ReMeMessage.builder()
                                                .role("user")
                                                .content("I like coffee in the morning")
                                                .build(),
                                        ReMeMessage.builder()
                                                .role("assistant")
                                                .content("Noted. You prefer coffee while working.")
                                                .build()))
                        .memoryHint("preference memory")
                        .metadata(Map.of("tenant", "demo"))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertEquals(
                                    "daily/2026-07-04/task-session.md",
                                    response.getMetadata().getPath());
                            assertEquals(true, response.getMetadata().getCreated());
                            assertEquals(true, response.getMetadata().getModified());
                            assertEquals(2, response.getMetadata().getNMessages());
                            assertEquals(
                                    "session/dialog/task-session.jsonl",
                                    response.getMetadata().getSourceConversation());
                            assertEquals(true, response.getMetadata().getIndex().get("updated"));
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/auto_memory", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));

        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"session_id\":\"task-session\""));
        assertTrue(requestBody.contains("\"messages\""));
        assertTrue(requestBody.contains("\"memory_hint\":\"preference memory\""));
        assertTrue(requestBody.contains("\"tenant\":\"demo\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"role\":\"assistant\""));
    }

    @Test
    void testAddRequestWithLegacyWorkspaceAlias() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{}").setResponseCode(200));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("legacy-workspace")
                        .messages(
                                List.of(ReMeMessage.builder().role("user").content("Test").build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"session_id\":\"legacy-workspace\""));
    }

    @Test
    void testAddRequestWithEmptyResponse() {
        mockServer.enqueue(new MockResponse().setBody("").setResponseCode(200));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .sessionId("test-session")
                        .messages(
                                List.of(ReMeMessage.builder().role("user").content("Test").build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
    }

    @Test
    void testAddRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Bad request\"}").setResponseCode(400));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .sessionId("test-session")
                        .messages(
                                List.of(ReMeMessage.builder().role("user").content("Test").build()))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 400")
                                        && error.getMessage().contains("auto_memory"))
                .verify();
    }

    @Test
    void testAddRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("invalid json").setResponseCode(200));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .sessionId("test-session")
                        .messages(
                                List.of(ReMeMessage.builder().role("user").content("Test").build()))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("Failed to parse response")
                                        || error instanceof JsonException)
                .verify();
    }

    @Test
    void testSearchRequestSuccess() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"results\":[{\"id\":\"chunk-1\",\"text\":\"User"
                                    + " likes"
                                    + " coffee\",\"metadata\":{\"source\":\"daily\"},\"path\":\"daily/2026-07-04.md\",\"start_line\":12,\"end_line\":13,\"scores\":{\"hybrid\":0.91}}],\"counts\":{\"results\":1},\"link_expansion\":{\"enabled\":false}}}")
                        .setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .query("What are the user's preferences?")
                        .limit(5)
                        .minScore(0.2)
                        .metadata(Map.of("scope", "demo"))
                        .workspaceId("ignored-workspace")
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertEquals(1, response.getMetadata().getResults().size());
                            ReMeSearchResponse.SearchResult result =
                                    response.getMetadata().getResults().get(0);
                            assertEquals("chunk-1", result.getId());
                            assertEquals("User likes coffee", result.getText());
                            assertEquals("daily/2026-07-04.md", result.getPath());
                            assertEquals(12, result.getStartLine());
                            assertEquals(13, result.getEndLine());
                            assertEquals(0.91, result.getScores().get("hybrid"));
                            assertEquals(List.of("User likes coffee"), response.getMemories());
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/search", recordedRequest.getPath());
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"query\":\"What are the user's preferences?\""));
        assertTrue(requestBody.contains("\"limit\":5"));
        assertTrue(requestBody.contains("\"min_score\":0.2"));
        assertTrue(requestBody.contains("\"scope\":\"demo\""));
        assertTrue(!requestBody.contains("workspace_id"));
    }

    @Test
    void testSearchRequestEmptyResults() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"results\":[],\"counts\":{\"results\":0}}}")
                        .setResponseCode(200));

        ReMeSearchRequest request = ReMeSearchRequest.builder().query("test query").build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertEquals(0, response.getMetadata().getResults().size());
                            assertEquals(0, response.getMemories().size());
                        })
                .verifyComplete();
    }

    @Test
    void testSearchRequestWithDefaultLimit() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{}").setResponseCode(200));

        ReMeSearchRequest request = ReMeSearchRequest.builder().query("test").build();

        StepVerifier.create(client.search(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"limit\":5"));
        assertTrue(requestBody.contains("\"min_score\":0.0"));
    }

    @Test
    void testSearchRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        ReMeSearchRequest request = ReMeSearchRequest.builder().query("test query").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 404")
                                        && error.getMessage().contains("search"))
                .verify();
    }

    @Test
    void testSearchRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("not valid json").setResponseCode(200));

        ReMeSearchRequest request = ReMeSearchRequest.builder().query("test").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("Failed to parse response")
                                        || error instanceof JsonException)
                .verify();
    }

    @Test
    void testShutdown() {
        ReMeClient shutdownClient = new ReMeClient("http://localhost:8002");
        shutdownClient.shutdown();
    }

    @Test
    void testHttpTimeout() {
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        ReMeClient shortTimeoutClient = new ReMeClient(baseUrl, Duration.ofMillis(1));

        mockServer.enqueue(
                new MockResponse()
                        .setBody("{}")
                        .setResponseCode(200)
                        .setBodyDelay(1000, TimeUnit.MILLISECONDS));

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .sessionId("test-session")
                        .messages(
                                List.of(ReMeMessage.builder().role("user").content("Test").build()))
                        .build();

        StepVerifier.create(shortTimeoutClient.add(request)).expectError().verify();

        shortTimeoutClient.shutdown();
    }
}
