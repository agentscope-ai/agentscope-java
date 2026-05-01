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
package io.agentscope.core.memory.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link BailianMemoryClient}. */
class BailianMemoryClientTest {

    private MockWebServer mockServer;
    private BailianMemoryClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        client =
                BailianMemoryClient.builder()
                        .apiBaseUrl(baseUrl)
                        .apiKey("test-api-key")
                        .httpTransport(OkHttpTransport.builder().build())
                        .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testBuilderWithDefaultBaseUrl() {
        BailianMemoryClient client = BailianMemoryClient.builder().apiKey("test-key").build();

        assertNotNull(client);
        client.close();
    }

    @Test
    void testBuilderWithCustomBaseUrl() {
        BailianMemoryClient client =
                BailianMemoryClient.builder()
                        .apiBaseUrl("https://custom.api.url")
                        .apiKey("test-key")
                        .build();

        assertNotNull(client);
        client.close();
    }

    @Test
    void testBuilderWithCustomHttpTransport() {
        HttpTransport httpTransport = HttpTransportFactory.getDefault();
        BailianMemoryClient client =
                BailianMemoryClient.builder()
                        .apiBaseUrl("https://api.example.com")
                        .apiKey("test-key")
                        .httpTransport(httpTransport)
                        .build();

        assertNotNull(client);
        client.close();
    }

    @Test
    void testBuilderWithNullBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BailianMemoryClient.builder().apiBaseUrl(null).apiKey("test-key").build());
    }

    @Test
    void testBuilderWithEmptyBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BailianMemoryClient.builder().apiBaseUrl("").apiKey("test-key").build());
    }

    @Test
    void testBuilderWithBlankBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BailianMemoryClient.builder().apiBaseUrl("   ").apiKey("test-key").build());
    }

    @Test
    void testBuilderWithNullApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianMemoryClient.builder()
                                .apiBaseUrl("https://api.example.com")
                                .apiKey(null)
                                .build());
    }

    @Test
    void testBuilderWithEmptyApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianMemoryClient.builder()
                                .apiBaseUrl("https://api.example.com")
                                .apiKey("")
                                .build());
    }

    @Test
    void testBuilderWithBlankApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianMemoryClient.builder()
                                .apiBaseUrl("https://api.example.com")
                                .apiKey("   ")
                                .build());
    }

    @Test
    void testBuilderWithNullHttpTransport() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianMemoryClient.builder()
                                .apiBaseUrl("https://api.example.com")
                                .apiKey("test-key")
                                .httpTransport(null)
                                .build());
    }

    @Test
    void testAddRequestSuccess() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_123\",\"memory_nodes\":[{\"memory_node_id\":\"mem_1\",\"content\":\"User"
                    + " prefers dark mode\",\"event\":\"ADD\"}]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("I prefer dark mode")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals("req_123", response.getRequestId());
                            assertNotNull(response.getMemoryNodes());
                            assertEquals(1, response.getMemoryNodes().size());
                            assertEquals(
                                    "mem_1", response.getMemoryNodes().get(0).getMemoryNodeId());
                            assertEquals(
                                    "User prefers dark mode",
                                    response.getMemoryNodes().get(0).getContent());
                            assertEquals("ADD", response.getMemoryNodes().get(0).getEvent());
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/api/v2/apps/memory/add"));
        assertEquals("Bearer test-api-key", recordedRequest.getHeader("Authorization"));
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void testAddRequestWithAllFields() throws Exception {
        String responseJson = "{\"request_id\":\"req_456\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test message")
                                                .build()))
                        .profileSchema("profile_123")
                        .memoryLibraryId("lib_456")
                        .projectId("proj_789")
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals("req_456", response.getRequestId());
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        assertTrue(requestBody.contains("\"profile_schema\":\"profile_123\""));
        assertTrue(requestBody.contains("\"memory_library_id\":\"lib_456\""));
        assertTrue(requestBody.contains("\"project_id\":\"proj_789\""));
    }

    @Test
    void testAddRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Bad request\"}").setResponseCode(400));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 400")
                                        && error.getMessage().contains("add memory"))
                .verify();
    }

    @Test
    void testAddRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("invalid json").setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request)).expectError().verify();
    }

    @Test
    void testSearchRequestSuccess() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_789\",\"memory_nodes\":[{\"memory_node_id\":\"mem_1\",\"content\":\"User"
                    + " prefers dark"
                    + " mode\",\"event\":\"ADD\"},{\"memory_node_id\":\"mem_2\",\"content\":\"User"
                    + " likes coffee\",\"event\":\"ADD\"}]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("What are my preferences?")
                                                .build()))
                        .topK(5)
                        .minScore(0.5)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals("req_789", response.getRequestId());
                            assertNotNull(response.getMemoryNodes());
                            assertEquals(2, response.getMemoryNodes().size());
                            assertEquals(
                                    "User prefers dark mode",
                                    response.getMemoryNodes().get(0).getContent());
                            assertEquals(
                                    "User likes coffee",
                                    response.getMemoryNodes().get(1).getContent());
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("/api/v2/apps/memory/memory_nodes/search"));
    }

    @Test
    void testSearchRequestWithAllFields() throws Exception {
        String responseJson = "{\"request_id\":\"req_999\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test query")
                                                .build()))
                        .memoryLibraryId("lib_456")
                        .projectIds(List.of("proj_1", "proj_2"))
                        .topK(10)
                        .minScore(0.3)
                        .enableRerank(true)
                        .enableJudge(true)
                        .enableRewrite(true)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals("req_999", response.getRequestId());
                        })
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        assertTrue(requestBody.contains("\"memory_library_id\":\"lib_456\""));
        assertTrue(requestBody.contains("\"project_ids\":[\"proj_1\",\"proj_2\"]"));
        assertTrue(requestBody.contains("\"top_k\":10"));
        assertTrue(requestBody.contains("\"min_score\":0.3"));
        assertTrue(requestBody.contains("\"enable_rerank\":true"));
        assertTrue(requestBody.contains("\"enable_judge\":true"));
        assertTrue(requestBody.contains("\"enable_rewrite\":true"));
    }

    @Test
    void testSearchRequestEmptyResults() throws Exception {
        String responseJson = "{\"request_id\":\"req_empty\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("test")
                                                .build()))
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getMemoryNodes());
                            assertEquals(0, response.getMemoryNodes().size());
                        })
                .verifyComplete();
    }

    @Test
    void testSearchRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("test")
                                                .build()))
                        .build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 404")
                                        && error.getMessage().contains("search memory"))
                .verify();
    }

    @Test
    void testSearchRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("not json").setResponseCode(200));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("test")
                                                .build()))
                        .build();

        StepVerifier.create(client.search(request)).expectError().verify();
    }

    @Test
    void testClose() {
        BailianMemoryClient client =
                BailianMemoryClient.builder()
                        .apiBaseUrl("https://api.example.com")
                        .apiKey("test-key")
                        .build();

        client.close();
    }

    @Test
    void testCloseMultipleTimes() {
        BailianMemoryClient client =
                BailianMemoryClient.builder()
                        .apiBaseUrl("https://api.example.com")
                        .apiKey("test-key")
                        .build();

        client.close();
        client.close();
    }

    @Test
    void testRequestBodySerializationForAdd() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_test\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test message")
                                                .build(),
                                        BailianMessage.builder()
                                                .role("assistant")
                                                .content("Assistant response")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"content\":\"Test message\""));
        assertTrue(requestBody.contains("\"role\":\"assistant\""));
        assertTrue(requestBody.contains("\"content\":\"Assistant response\""));
    }

    @Test
    void testRequestBodySerializationForSearch() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_test\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Search query")
                                                .build()))
                        .topK(5)
                        .minScore(0.7)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"content\":\"Search query\""));
        assertTrue(requestBody.contains("\"top_k\":5"));
        assertTrue(requestBody.contains("\"min_score\":0.7"));
    }

    @Test
    void testAuthorizationHeader() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_auth\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("Bearer test-api-key", recordedRequest.getHeader("Authorization"));
    }

    @Test
    void testContentTypeHeader() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_content\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String contentType = recordedRequest.getHeader("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.contains("application/json"));
    }

    @Test
    void testAddResponseWithMultipleMemoryNodes() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_multi\",\"memory_nodes\":[{\"memory_node_id\":\"mem_1\",\"content\":\"First"
                    + " memory\",\"event\":\"ADD\"},{\"memory_node_id\":\"mem_2\",\"content\":\"Second"
                    + " memory\",\"event\":\"UPDATE\",\"old_content\":\"Old"
                    + " content\"},{\"memory_node_id\":\"mem_3\",\"content\":\"Third"
                    + " memory\",\"event\":\"DELETE\"}]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(
                                List.of(
                                        BailianMessage.builder()
                                                .role("user")
                                                .content("Test")
                                                .build()))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(3, response.getMemoryNodes().size());
                            assertEquals(
                                    "mem_1", response.getMemoryNodes().get(0).getMemoryNodeId());
                            assertEquals("ADD", response.getMemoryNodes().get(0).getEvent());
                            assertEquals(
                                    "mem_2", response.getMemoryNodes().get(1).getMemoryNodeId());
                            assertEquals("UPDATE", response.getMemoryNodes().get(1).getEvent());
                            assertEquals(
                                    "Old content",
                                    response.getMemoryNodes().get(1).getOldContent());
                            assertEquals(
                                    "mem_3", response.getMemoryNodes().get(2).getMemoryNodeId());
                            assertEquals("DELETE", response.getMemoryNodes().get(2).getEvent());
                        })
                .verifyComplete();
    }
}
