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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link ReMeLongTermMemory}. */
class ReMeLongTermMemoryTest {

    private MockWebServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testBuilderWithUserId() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithCustomTimeout() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder()
                        .userId("task_workspace")
                        .apiBaseUrl(baseUrl)
                        .timeout(Duration.ofSeconds(30))
                        .build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderRequiresUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ReMeLongTermMemory.builder().apiBaseUrl(baseUrl).build());

        assertEquals("userId is required", exception.getMessage());
    }

    @Test
    void testBuilderRequiresApiBaseUrl() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ReMeLongTermMemory.builder().userId("task_workspace").build());

        assertEquals("apiBaseUrl is required", exception.getMessage());
    }

    @Test
    void testBuilderWithEmptyUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ReMeLongTermMemory.builder().userId("").apiBaseUrl(baseUrl).build());

        assertEquals("userId is required", exception.getMessage());
    }

    @Test
    void testBuilderWithEmptyApiBaseUrl() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ReMeLongTermMemory.builder()
                                        .userId("task_workspace")
                                        .apiBaseUrl("")
                                        .build());

        assertEquals("apiBaseUrl is required", exception.getMessage());
    }

    @Test
    void testRecordWithValidMessages() {
        // Mock response with complete ReMeAddResponse structure
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[{"
                    + "\"workspace_id\":\"task_workspace\","
                    + "\"memory_id\":\"688e9ef5904e4c8b8d60ef6ffff77c75\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.048747035796754684,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10 10:30:43\","
                    + "\"author\":\"qwen3-30b-a3b-thinking-2507\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\","
                    + "\"match_event_flag\":\"0\",\"match_msg_flag\":\"0\"},\"target\":\"user\","
                    + "\"reflection_subject\":\"\"}],\"deleted_memory_ids\":[],\"update_result\":{"
                    + "\"deleted_count\":0,\"inserted_count\":1}}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("I like to drink coffee while working in the morning")
                                        .build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "I understand, you prefer to start your workday"
                                                        + " with coffee to stay energized")
                                        .build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();

        // Verify response parsing by directly testing ReMeClient
        ReMeClient client = new ReMeClient(baseUrl);
        ReMeMessage remeMsg1 =
                ReMeMessage.builder()
                        .role("user")
                        .content("I like to drink coffee while working in the morning")
                        .build();
        ReMeMessage remeMsg2 =
                ReMeMessage.builder()
                        .role("assistant")
                        .content(
                                "I understand, you prefer to start your workday with coffee to stay"
                                        + " energized")
                        .build();
        ReMeTrajectory trajectory =
                ReMeTrajectory.builder().messages(List.of(remeMsg1, remeMsg2)).build();
        ReMeAddRequest addRequest =
                ReMeAddRequest.builder()
                        .workspaceId("task_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        StepVerifier.create(client.add(addRequest))
                .assertNext(
                        response -> {
                            // Verify top-level fields
                            assertNotNull(response);
                            assertEquals("", response.getAnswer());
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());

                            // Verify metadata fields
                            ReMeAddResponse.Metadata metadata = response.getMetadata();
                            assertNotNull(metadata.getMemoryList());
                            assertEquals(1, metadata.getMemoryList().size());
                            assertNotNull(metadata.getDeletedMemoryIds());
                            assertEquals(0, metadata.getDeletedMemoryIds().size());
                            assertNotNull(metadata.getUpdateResult());

                            // Verify update_result
                            ReMeAddResponse.UpdateResult updateResult = metadata.getUpdateResult();
                            assertEquals(0, updateResult.getDeletedCount());
                            assertEquals(1, updateResult.getInsertedCount());

                            // Verify memory_list item
                            ReMeAddResponse.MemoryItem memoryItem = metadata.getMemoryList().get(0);
                            assertEquals("task_workspace", memoryItem.getWorkspaceId());
                            assertEquals(
                                    "688e9ef5904e4c8b8d60ef6ffff77c75", memoryItem.getMemoryId());
                            assertEquals("personal", memoryItem.getMemoryType());
                            assertEquals("coffee, morning, work", memoryItem.getWhenToUse());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memoryItem.getContent());
                            assertEquals(0.048747035796754684, memoryItem.getScore());
                            assertEquals("2025-12-10 10:30:43", memoryItem.getTimeCreated());
                            assertEquals("2025-12-10 10:30:43", memoryItem.getTimeModified());
                            assertEquals("qwen3-30b-a3b-thinking-2507", memoryItem.getAuthor());
                            assertEquals("user", memoryItem.getTarget());
                            assertEquals("", memoryItem.getReflectionSubject());

                            // Verify nested metadata
                            assertNotNull(memoryItem.getMetadata());
                            assertEquals(
                                    "coffee, morning, work",
                                    memoryItem.getMetadata().get("keywords"));
                            assertEquals("", memoryItem.getMetadata().get("time_info"));
                            assertEquals(
                                    "I like to drink coffee while working in the morning",
                                    memoryItem.getMetadata().get("source_message"));
                            assertEquals(
                                    "personal_info_with_time",
                                    memoryItem.getMetadata().get("observation_type"));
                            assertEquals("0", memoryItem.getMetadata().get("match_event_flag"));
                            assertEquals("0", memoryItem.getMetadata().get("match_msg_flag"));
                        })
                .verifyComplete();

        client.shutdown();
    }

    @Test
    void testRecordWithNullMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(null)).verifyComplete();
    }

    @Test
    void testRecordWithEmptyMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(new ArrayList<>())).verifyComplete();
    }

    @Test
    void testRecordFiltersNullMessages() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

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

    @Test
    void testRecordFiltersEmptyContentMessages() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

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

    @Test
    void testRecordWithOnlyInvalidMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        List<Msg> messages = new ArrayList<>();
        messages.add(null);
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build());

        // Should complete without making HTTP request
        StepVerifier.create(memory.record(messages)).verifyComplete();
    }

    @Test
    void testRetrieveWithValidQuery() {
        // Mock response with complete ReMeSearchResponse structure
        String responseJson =
                "{\"answer\":\"user drinks coffee in the morning while working\",\"success\":true,"
                    + "\"metadata\":{\"memory_list\":[{\"workspace_id\":\"task_workspace\","
                    + "\"memory_id\":\"688e9ef5904e4c8b8d60ef6ffff77c75\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.048747035796754684,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10 10:30:43\","
                    + "\"author\":\"qwen3-30b-a3b-thinking-2507\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\","
                    + "\"match_event_flag\":\"0\",\"match_msg_flag\":\"0\"},\"target\":\"user\","
                    + "\"reflection_subject\":\"\"}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What are my work habits?").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(
                        result -> {
                            assertNotNull(result);
                            // Should use answer field when available
                            assertEquals("user drinks coffee in the morning while working", result);
                        })
                .verifyComplete();

        // Verify response parsing by directly testing ReMeClient
        ReMeClient client = new ReMeClient(baseUrl);
        ReMeSearchRequest searchRequest =
                ReMeSearchRequest.builder()
                        .workspaceId("task_workspace")
                        .query("What are my work habits?")
                        .topK(5)
                        .build();

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        StepVerifier.create(client.search(searchRequest))
                .assertNext(
                        response -> {
                            // Verify top-level fields
                            assertNotNull(response);
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    response.getAnswer());
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());

                            // Verify metadata
                            ReMeSearchResponse.Metadata metadata = response.getMetadata();
                            assertNotNull(metadata.getMemoryList());
                            assertEquals(1, metadata.getMemoryList().size());

                            // Verify memory_list item
                            ReMeSearchResponse.MemoryItem memoryItem =
                                    metadata.getMemoryList().get(0);
                            assertEquals("task_workspace", memoryItem.getWorkspaceId());
                            assertEquals(
                                    "688e9ef5904e4c8b8d60ef6ffff77c75", memoryItem.getMemoryId());
                            assertEquals("personal", memoryItem.getMemoryType());
                            assertEquals("coffee, morning, work", memoryItem.getWhenToUse());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memoryItem.getContent());
                            assertEquals(0.048747035796754684, memoryItem.getScore());
                            assertEquals("2025-12-10 10:30:43", memoryItem.getTimeCreated());
                            assertEquals("2025-12-10 10:30:43", memoryItem.getTimeModified());
                            assertEquals("qwen3-30b-a3b-thinking-2507", memoryItem.getAuthor());
                            assertEquals("user", memoryItem.getTarget());
                            assertEquals("", memoryItem.getReflectionSubject());

                            // Verify nested metadata
                            assertNotNull(memoryItem.getMetadata());
                            assertEquals(
                                    "coffee, morning, work",
                                    memoryItem.getMetadata().get("keywords"));
                            assertEquals("", memoryItem.getMetadata().get("time_info"));
                            assertEquals(
                                    "I like to drink coffee while working in the morning",
                                    memoryItem.getMetadata().get("source_message"));
                            assertEquals(
                                    "personal_info_with_time",
                                    memoryItem.getMetadata().get("observation_type"));
                            assertEquals("0", memoryItem.getMetadata().get("match_event_flag"));
                            assertEquals("0", memoryItem.getMetadata().get("match_msg_flag"));

                            // Verify backward compatibility - getMemories() method
                            List<String> memories = response.getMemories();
                            assertNotNull(memories);
                            assertEquals(1, memories.size());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memories.get(0));
                        })
                .verifyComplete();

        client.shutdown();
    }

    @Test
    void testRetrieveWithAnswerField() {
        String responseJson =
                "{\"answer\":\"User prefers coffee in the"
                        + " morning\",\"success\":true,\"metadata\":{\"memory_list\":[]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What are my preferences?").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("User prefers coffee in the morning", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithMemoryList() {
        // Response with empty answer but memory_list
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"task_workspace\",\"memory_id\":\"mem1\",\"content\":\"User"
                    + " prefers dark"
                    + " mode\",\"score\":0.95},{\"workspace_id\":\"task_workspace\",\"memory_id\":\"mem2\",\"content\":\"User"
                    + " likes coffee\",\"score\":0.85}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What are my preferences?").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(
                        result -> assertEquals("User prefers dark mode\nUser likes coffee", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithNoResults() {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("query").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithNullMessage() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.retrieve(null))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithNullQuery() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query = Msg.builder().role(MsgRole.USER).build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("query").build())
                        .build();

        // Should return empty string on error
        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveFiltersNullMemories() {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"task_workspace\",\"memory_id\":\"mem1\",\"content\":\"Valid"
                    + " memory\",\"score\":0.95},{\"workspace_id\":\"task_workspace\",\"memory_id\":\"mem2\",\"content\":null,\"score\":0.85},{\"workspace_id\":\"task_workspace\",\"memory_id\":\"mem3\",\"content\":\"Another"
                    + " valid\",\"score\":0.75}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("query").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("Valid memory\nAnother valid", result))
                .verifyComplete();
    }

    @Test
    void testRoleMapping() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("task_workspace").apiBaseUrl(baseUrl).build();

        // Test USER role -> "user"
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User message").build())
                        .build());

        // Test ASSISTANT role -> "assistant"
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Assistant message").build())
                        .build());

        // Test SYSTEM role -> "user"
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("System message").build())
                        .build());

        // Test TOOL role -> "assistant"
        messages.add(
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(TextBlock.builder().text("Tool message").build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();

        // Verify request body contains correct role mappings
        okhttp3.mockwebserver.RecordedRequest recordedRequest;
        try {
            recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            // Should contain "user" role for USER and SYSTEM
            assertTrue(requestBody.contains("\"role\":\"user\""));
            // Should contain "assistant" role for ASSISTANT and TOOL
            assertTrue(requestBody.contains("\"role\":\"assistant\""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testRecordRequestContainsWorkspaceId() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("test_workspace").apiBaseUrl(baseUrl).build();

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test message").build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();

        // Verify request contains workspace_id
        okhttp3.mockwebserver.RecordedRequest recordedRequest;
        try {
            recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
            assertTrue(requestBody.contains("\"trajectories\""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testRetrieveRequestContainsWorkspaceId() {
        String responseJson =
                "{\"answer\":\"test answer\",\"success\":true,\"metadata\":{\"memory_list\":[]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("test_workspace").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test query").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("test answer", result))
                .verifyComplete();

        // Verify request contains workspace_id and query
        okhttp3.mockwebserver.RecordedRequest recordedRequest;
        try {
            recordedRequest = mockServer.takeRequest();
            String requestBody = recordedRequest.getBody().readUtf8();
            assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
            assertTrue(requestBody.contains("\"query\":\"test query\""));
            assertTrue(requestBody.contains("\"top_k\":5"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
