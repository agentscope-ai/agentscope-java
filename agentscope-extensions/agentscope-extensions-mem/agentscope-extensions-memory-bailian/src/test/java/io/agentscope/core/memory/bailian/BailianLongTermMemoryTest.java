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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link BailianLongTermMemory}. */
class BailianLongTermMemoryTest {

    private MockWebServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testBuilderWithUserId() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder().apiKey("test-key").userId("user123").build()) {
            assertNotNull(memory);
        }
    }

    @Test
    void testBuilderWithAllFields() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .profileSchema("profile_123")
                        .memoryLibraryId("lib_456")
                        .projectId("proj_789")
                        .topK(5)
                        .minScore(0.5)
                        .enableRerank(true)
                        .enableJudge(true)
                        .enableRewrite(true)
                        .build()) {
            assertNotNull(memory);
        }
    }

    @Test
    void testBuilderWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 123);

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .userId("user123")
                        .metadata(metadata)
                        .build()) {
            assertNotNull(memory);
        }
    }

    @Test
    void testBuilderRequiresApiKey() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BailianLongTermMemory.builder().userId("user123").build());

        assertEquals("apiKey cannot be null or blank", exception.getMessage());
    }

    @Test
    void testBuilderWithEmptyApiKey() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BailianLongTermMemory.builder().apiKey("").userId("user123").build());

        assertEquals("apiKey cannot be null or blank", exception.getMessage());
    }

    @Test
    void testBuilderWithBlankApiKey() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BailianLongTermMemory.builder()
                                        .apiKey("   ")
                                        .userId("user123")
                                        .build());

        assertEquals("apiKey cannot be null or blank", exception.getMessage());
    }

    @Test
    void testBuilderRequiresUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BailianLongTermMemory.builder().apiKey("test-key").build());

        assertEquals("userId cannot be null or blank", exception.getMessage());
    }

    @Test
    void testBuilderWithEmptyUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BailianLongTermMemory.builder()
                                        .apiKey("test-key")
                                        .userId("")
                                        .build());

        assertEquals("userId cannot be null or blank", exception.getMessage());
    }

    @Test
    void testBuilderWithBlankUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BailianLongTermMemory.builder()
                                        .apiKey("test-key")
                                        .userId("   ")
                                        .build());

        assertEquals("userId cannot be null or blank", exception.getMessage());
    }

    @Test
    void testRecordWithValidMessages() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_123\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("I prefer dark mode").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Noted").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"user_id\":\"user123\""));
        }
    }

    @Test
    void testRecordWithNullMessages() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {
            StepVerifier.create(memory.record(null)).verifyComplete();
        }
    }

    @Test
    void testRecordWithEmptyMessages() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {
            StepVerifier.create(memory.record(List.of())).verifyComplete();
        }
    }

    @Test
    void testRecordFiltersNullMessages() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_456\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Valid message").build())
                            .build());
            messages.add(null);
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Another valid").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();
        }
    }

    @Test
    void testRecordFiltersEmptyContentMessages() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_789\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Valid message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();
        }
    }

    @Test
    void testRecordFiltersSystemMessages() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_sys\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("User message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text("System message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Assistant message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"role\":\"user\""));
            assertTrue(requestBody.contains("\"role\":\"assistant\""));
        }
    }

    @Test
    void testRecordFiltersToolMessages() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_tool\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("User message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(TextBlock.builder().text("Tool result").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"role\":\"user\""));
        }
    }

    @Test
    void testRecordFiltersAssistantWithToolUseBlock() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_tooluse\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("User message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(ToolUseBlock.builder().name("tool_name").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"role\":\"user\""));
        }
    }

    @Test
    void testRecordFiltersCompressedHistory() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_compress\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Valid message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "<compressed_history>old"
                                                            + " conversation</compressed_history>")
                                            .build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertFalse(requestBody.contains("compressed_history"));
        }
    }

    @Test
    void testRecordWithOnlyInvalidMessages() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {
            List<Msg> messages = new ArrayList<>();
            messages.add(null);
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();
        }
    }

    @Test
    void testRecordWithMemoryLibraryId() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_lib\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .memoryLibraryId("lib_456")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"memory_library_id\":\"lib_456\""));
        }
    }

    @Test
    void testRecordWithProfileSchema() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_schema\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .profileSchema("profile_123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"profile_schema\":\"profile_123\""));
        }
    }

    @Test
    void testRecordWithProjectId() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_proj\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .projectId("proj_789")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"project_id\":\"proj_789\""));
        }
    }

    @Test
    void testRecordWithMetadata() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_meta\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 123);

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .metadata(metadata)
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"meta_data\""));
        }
    }

    @Test
    void testRetrieveWithValidQuery() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_search\",\"memory_nodes\":["
                        + "{\"memory_node_id\":\"mem_1\",\"content\":\"User prefers dark mode\"},"
                        + "{\"memory_node_id\":\"mem_2\",\"content\":\"User likes coffee\"}"
                        + "]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("What are my preferences?").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(
                            result -> {
                                assertNotNull(result);
                                assertEquals("User prefers dark mode\nUser likes coffee", result);
                            })
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithNoResults() throws Exception {
        String responseJson = "{\"request_id\":\"req_empty\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithNullMessage() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {

            StepVerifier.create(memory.retrieve(null))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {
            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithNullQuery() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .userId("user123")
                        .build()) {
            Msg query = Msg.builder().role(MsgRole.USER).build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveFiltersNullMemories() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_null\",\"memory_nodes\":["
                        + "{\"memory_node_id\":\"mem_1\",\"content\":\"Valid memory\"},"
                        + "{\"memory_node_id\":\"mem_2\",\"content\":null},"
                        + "{\"memory_node_id\":\"mem_3\",\"content\":\"Another valid\"}"
                        + "]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("Valid memory\nAnother valid", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveFiltersEmptyMemories() throws Exception {
        String responseJson =
                "{\"request_id\":\"req_blank\",\"memory_nodes\":["
                        + "{\"memory_node_id\":\"mem_1\",\"content\":\"Valid memory\"},"
                        + "{\"memory_node_id\":\"mem_2\",\"content\":\"   \"},"
                        + "{\"memory_node_id\":\"mem_3\",\"content\":\"Another valid\"}"
                        + "]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("Valid memory\nAnother valid", result))
                    .verifyComplete();
        }
    }

    @Test
    void testRetrieveWithMemoryLibraryId() throws Exception {
        String responseJson = "{\"request_id\":\"req_lib_search\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .memoryLibraryId("lib_456")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"memory_library_id\":\"lib_456\""));
        }
    }

    @Test
    void testRetrieveWithProjectId() throws Exception {
        String responseJson = "{\"request_id\":\"req_proj_search\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .projectId("proj_789")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"project_ids\":[\"proj_789\"]"));
        }
    }

    @Test
    void testRetrieveWithCustomTopK() throws Exception {
        String responseJson = "{\"request_id\":\"req_topk\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .topK(15)
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"top_k\":15"));
        }
    }

    @Test
    void testRetrieveWithCustomMinScore() throws Exception {
        String responseJson = "{\"request_id\":\"req_score\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .minScore(0.7)
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"min_score\":0.7"));
        }
    }

    @Test
    void testRetrieveWithEnableRerank() throws Exception {
        String responseJson = "{\"request_id\":\"req_rerank\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .enableRerank(true)
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"enable_rerank\":true"));
        }
    }

    @Test
    void testRetrieveWithEnableJudge() throws Exception {
        String responseJson = "{\"request_id\":\"req_judge\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .enableJudge(true)
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"enable_judge\":true"));
        }
    }

    @Test
    void testRetrieveWithEnableRewrite() throws Exception {
        String responseJson = "{\"request_id\":\"req_rewrite\",\"memory_nodes\":[]}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .enableRewrite(true)
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("test query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"enable_rewrite\":true"));
        }
    }

    @Test
    void testRoleMapping() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"request_id\":\"req_role\",\"memory_nodes\":[]}")
                        .setResponseCode(200));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("User message").build())
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Assistant message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();

            RecordedRequest recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"role\":\"user\""));
            assertTrue(requestBody.contains("\"role\":\"assistant\""));
        }
    }

    @Test
    void testCloseMethod() {
        OkHttpTransport transport = OkHttpTransport.builder().build();
        BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(transport)
                        .userId("user123")
                        .build();

        assertNotNull(memory);
        memory.close();
    }

    @Test
    void testRecordWithHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Internal error\"}").setResponseCode(500));

        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl(baseUrl)
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();
        }
    }

    @Test
    void testRecordWithNetworkError() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl("http://nonexistent-host:99999")
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Test message").build())
                            .build());

            StepVerifier.create(memory.record(messages)).verifyComplete();
        }
    }

    @Test
    void testRetrieveWithNetworkError() {
        try (BailianLongTermMemory memory =
                BailianLongTermMemory.builder()
                        .apiKey("test-key")
                        .apiBaseUrl("http://nonexistent-host:99999")
                        .httpTransport(OkHttpTransport.builder().build())
                        .userId("user123")
                        .build()) {

            Msg query =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("query").build())
                            .build();

            StepVerifier.create(memory.retrieve(query))
                    .assertNext(result -> assertEquals("", result))
                    .verifyComplete();
        }
    }
}
