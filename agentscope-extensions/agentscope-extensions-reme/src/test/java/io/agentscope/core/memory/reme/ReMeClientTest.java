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
        // Remove trailing slash
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
        String baseUrl = "http://localhost:8002";
        ReMeClient client = new ReMeClient(baseUrl);
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testConstructorWithTrailingSlash() {
        String baseUrl = "http://localhost:8002/";
        ReMeClient client = new ReMeClient(baseUrl);
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testConstructorWithCustomTimeout() {
        String baseUrl = "http://localhost:8002";
        ReMeClient client = new ReMeClient(baseUrl, Duration.ofSeconds(30));
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testAddRequestSuccess() throws Exception {
        // Mock successful response matching actual API format
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[{"
                    + "\"workspace_id\":\"task_workspace\",\"memory_id\":\"test_memory_id\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.0,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10"
                    + " 10:30:43\",\"author\":\"test-author\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\"},"
                    + "\"target\":\"user\",\"reflection_subject\":\"\"}],\"deleted_memory_ids\":[],"
                    + "\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        ReMeMessage message1 =
                ReMeMessage.builder()
                        .role("user")
                        .content("I like to drink coffee while working in the morning")
                        .build();
        ReMeMessage message2 =
                ReMeMessage.builder()
                        .role("assistant")
                        .content(
                                "I understand, you prefer to start your workday with coffee to stay"
                                        + " energized")
                        .build();

        ReMeTrajectory trajectory =
                ReMeTrajectory.builder().messages(List.of(message1, message2)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("task_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // Execute and verify
        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(1, response.getMetadata().getMemoryList().size());
                            ReMeAddResponse.MemoryItem memory =
                                    response.getMetadata().getMemoryList().get(0);
                            assertEquals("task_workspace", memory.getWorkspaceId());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memory.getContent());
                            assertNotNull(response.getMetadata().getUpdateResult());
                            assertEquals(
                                    1, response.getMetadata().getUpdateResult().getInsertedCount());
                        })
                .verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/summary_personal_memory", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));

        // Verify request body
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"task_workspace\""));
        assertTrue(requestBody.contains("\"trajectories\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"content\":\"I like to drink coffee"));
    }

    @Test
    void testAddRequestWithEmptyResponse() throws Exception {
        // Mock empty response body (should return empty object)
        mockServer.enqueue(new MockResponse().setBody("").setResponseCode(200));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // Should handle empty response gracefully
        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
    }

    @Test
    void testAddRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Bad request\"}").setResponseCode(400));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 400")
                                        && error.getMessage().contains("summary_personal_memory"))
                .verify();
    }

    @Test
    void testAddRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("invalid json").setResponseCode(200));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
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
        // Mock successful search response matching actual API format
        String responseJson =
                "{\"answer\":\"user drinks coffee in the morning while working\",\"success\":true,"
                    + "\"metadata\":{\"memory_list\":[{\"workspace_id\":\"task_workspace\","
                    + "\"memory_id\":\"test_memory_id\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.048747035796754684,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10"
                    + " 10:30:43\",\"author\":\"test-author\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\"},"
                    + "\"target\":\"user\",\"reflection_subject\":\"\"}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create request
        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("task_workspace")
                        .query("What are the user's work habits?")
                        .topK(5)
                        .build();

        // Execute and verify
        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(1, response.getMetadata().getMemoryList().size());
                            ReMeSearchResponse.MemoryItem memory =
                                    response.getMetadata().getMemoryList().get(0);
                            assertEquals("task_workspace", memory.getWorkspaceId());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memory.getContent());
                            // Test backward compatibility - getMemories() should extract content
                            assertNotNull(response.getMemories());
                            assertEquals(1, response.getMemories().size());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    response.getMemories().get(0));
                        })
                .verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/retrieve_personal_memory", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));

        // Verify request body
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"task_workspace\""));
        assertTrue(requestBody.contains("\"query\":\"What are the user's work habits?\""));
        assertTrue(requestBody.contains("\"top_k\":5"));
    }

    @Test
    void testSearchRequestEmptyResults() throws Exception {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(5)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(0, response.getMetadata().getMemoryList().size());
                            assertEquals(0, response.getMemories().size());
                        })
                .verifyComplete();
    }

    @Test
    void testSearchRequestWithDefaultTopK() throws Exception {
        String responseJson =
                "{\"answer\":\"Memory"
                    + " 1\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"Memory"
                    + " 1\"}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test")
                        .build(); // topK not set, should use default

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("Memory 1", response.getAnswer());
                        })
                .verifyComplete();

        // Verify default topK is used
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"top_k\":5")); // Default value
    }

    @Test
    void testSearchRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(5)
                        .build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 404")
                                        && error.getMessage().contains("retrieve_personal_memory"))
                .verify();
    }

    @Test
    void testSearchRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("not valid json").setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder().workspaceId("test_workspace").query("test").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("Failed to parse response")
                                        || error instanceof JsonException)
                .verify();
    }

    @Test
    void testSearchRequestWithMultipleMemories() throws Exception {
        String responseJson =
                "{\"answer\":\"First memory fragment. Second memory fragment. Third memory"
                    + " fragment\",\"success\":true,\"metadata\":{\"memory_list\":[{"
                    + "\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"First"
                    + " memory fragment\",\"score\":0.9"
                    + "},{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem2\",\"content\":\"Second"
                    + " memory fragment\",\"score\":0.8"
                    + "},{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem3\",\"content\":\"Third"
                    + " memory fragment\",\"score\":0.7}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(10)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(3, response.getMetadata().getMemoryList().size());
                            // Test backward compatibility
                            assertNotNull(response.getMemories());
                            assertEquals(3, response.getMemories().size());
                            assertEquals("First memory fragment", response.getMemories().get(0));
                            assertEquals("Second memory fragment", response.getMemories().get(1));
                            assertEquals("Third memory fragment", response.getMemories().get(2));
                        })
                .verifyComplete();
    }

    @Test
    void testShutdown() {
        ReMeClient client = new ReMeClient("http://localhost:8002");
        // Should not throw exception
        client.shutdown();
    }

    @Test
    void testRequestBodySerialization() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":0}}}")
                        .setResponseCode(200));

        ReMeMessage message1 = ReMeMessage.builder().role("user").content("Test message 1").build();
        ReMeMessage message2 =
                ReMeMessage.builder().role("assistant").content("Test response 1").build();
        ReMeMessage message3 = ReMeMessage.builder().role("user").content("Test message 2").build();

        ReMeTrajectory trajectory =
                ReMeTrajectory.builder().messages(List.of(message1, message2, message3)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request body contains expected fields
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
        assertTrue(requestBody.contains("\"trajectories\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"role\":\"assistant\""));
        assertTrue(requestBody.contains("\"content\":\"Test message 1\""));
        assertTrue(requestBody.contains("\"content\":\"Test response 1\""));
        assertTrue(requestBody.contains("\"content\":\"Test message 2\""));
    }

    @Test
    void testSearchRequestBodySerialization() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"Memory"
                                    + " 1\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"Memory"
                                    + " 1\"}]}}")
                        .setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("What are the user preferences?")
                        .topK(10)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request body contains expected fields
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
        assertTrue(requestBody.contains("\"query\":\"What are the user preferences?\""));
        assertTrue(requestBody.contains("\"top_k\":10"));
    }

    @Test
    void testHttpTimeout() {
        // Create client with very short timeout
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        ReMeClient shortTimeoutClient = new ReMeClient(baseUrl, Duration.ofMillis(1));

        // Enqueue delayed response
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\":\"success\"}")
                        .setResponseCode(200)
                        .setBodyDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // Should timeout
        StepVerifier.create(shortTimeoutClient.add(request)).expectError().verify();

        shortTimeoutClient.shutdown();
    }

    @Test
    void testMultipleTrajectoriesInAddRequest() throws Exception {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":2}}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // Create multiple trajectories
        ReMeMessage msg1 = ReMeMessage.builder().role("user").content("Message 1").build();
        ReMeMessage msg2 = ReMeMessage.builder().role("assistant").content("Response 1").build();
        ReMeTrajectory trajectory1 = ReMeTrajectory.builder().messages(List.of(msg1, msg2)).build();

        ReMeMessage msg3 = ReMeMessage.builder().role("user").content("Message 2").build();
        ReMeMessage msg4 = ReMeMessage.builder().role("assistant").content("Response 2").build();
        ReMeTrajectory trajectory2 = ReMeTrajectory.builder().messages(List.of(msg3, msg4)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory1, trajectory2))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getUpdateResult());
                            assertEquals(
                                    2, response.getMetadata().getUpdateResult().getInsertedCount());
                        })
                .verifyComplete();

        // Verify request contains both trajectories
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"trajectories\""));
        // Should contain messages from both trajectories
        assertTrue(requestBody.contains("\"content\":\"Message 1\""));
        assertTrue(requestBody.contains("\"content\":\"Message 2\""));
    }
}
