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
import okhttp3.mockwebserver.RecordedRequest;
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
    void testBuilderWithSessionId() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithLegacyUserId() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("legacy-user").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithCustomTimeout() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder()
                        .sessionId("task-session")
                        .apiBaseUrl(baseUrl)
                        .timeout(Duration.ofSeconds(30))
                        .build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderRequiresSessionOrUserId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ReMeLongTermMemory.builder().apiBaseUrl(baseUrl).build());

        assertEquals("sessionId or userId is required", exception.getMessage());
    }

    @Test
    void testBuilderRequiresApiBaseUrl() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ReMeLongTermMemory.builder().sessionId("task-session").build());

        assertEquals("apiBaseUrl is required", exception.getMessage());
    }

    @Test
    void testRecordWithValidMessages() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"path\":\"daily/2026-07-04/task-session.md\",\"created\":true,\"modified\":true,\"n_messages\":2}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
                                        .text("Noted. You prefer coffee while working.")
                                        .build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/auto_memory", recordedRequest.getPath());
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"session_id\":\"task-session\""));
        assertTrue(requestBody.contains("\"messages\""));
        assertTrue(
                requestBody.contains(
                        "\"content\":\"I like to drink coffee while working in the morning\""));
        assertTrue(requestBody.contains("\"content\":\"Noted. You prefer coffee while working.\""));
    }

    @Test
    void testRecordUsesLegacyUserIdAsSessionId() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{}").setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().userId("legacy-user").apiBaseUrl(baseUrl).build();

        StepVerifier.create(
                        memory.record(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(TextBlock.builder().text("Hello").build())
                                                .build())))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertTrue(recordedRequest.getBody().readUtf8().contains("\"session_id\":\"legacy-user\""));
    }

    @Test
    void testRecordFiltersInvalidMessages() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{}").setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Valid user message").build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("System message").build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("<compressed_history>ignore").build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Valid assistant message").build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"content\":\"Valid user message\""));
        assertTrue(requestBody.contains("\"content\":\"Valid assistant message\""));
        assertTrue(!requestBody.contains("System message"));
        assertTrue(!requestBody.contains("<compressed_history>"));
    }

    @Test
    void testRecordWithNullMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(null)).verifyComplete();
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    void testRecordWithEmptyMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(new ArrayList<>())).verifyComplete();
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    void testRecordWithOnlyInvalidMessages() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        List<Msg> messages = new ArrayList<>();
        messages.add(null);
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build());

        StepVerifier.create(memory.record(messages)).verifyComplete();
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    void testRetrieveWithAnswerField() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"User prefers coffee in the"
                                        + " morning\",\"success\":true}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
    void testRetrieveWithSearchResultsFallback() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"results\":[{\"id\":\"1\",\"text\":\"User"
                                    + " prefers dark mode\"},{\"id\":\"2\",\"text\":\"User likes"
                                    + " coffee\"}]}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"results\":[],\"counts\":{\"results\":0}}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.retrieve(null))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
    void testRetrieveWithHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

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
    void testRetrieveRequestContainsNewSearchPayload() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"results\":[{\"id\":\"1\",\"text\":\"Memory\"}]}}")
                        .setResponseCode(200));

        ReMeLongTermMemory memory =
                ReMeLongTermMemory.builder().sessionId("task-session").apiBaseUrl(baseUrl).build();

        Msg query =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test query").build())
                        .build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("Memory", result))
                .verifyComplete();

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("/search", recordedRequest.getPath());
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"query\":\"test query\""));
        assertTrue(requestBody.contains("\"limit\":5"));
        assertTrue(!requestBody.contains("workspace_id"));
    }
}
