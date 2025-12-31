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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** Unit tests for {@link Mem0LongTermMemory}. */
class Mem0LongTermMemoryTest {

    private MockWebServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testBuilderWithAgentName() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().agentName("Assistant").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithUserName() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithRunName() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().runName("run456").apiBaseUrl(baseUrl).build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderWithAllIdentifiers() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder()
                        .agentName("Assistant")
                        .userId("user123")
                        .runName("run456")
                        .apiBaseUrl(baseUrl)
                        .apiKey("test-key")
                        .build();

        assertNotNull(memory);
    }

    @Test
    void testBuilderRequiresAtLeastOneIdentifier() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Mem0LongTermMemory.builder().apiBaseUrl(baseUrl).build());

        assertEquals(
                "At least one of agentName, userName, or runName must be provided",
                exception.getMessage());
    }

    @Test
    void testBuilderRequiresApiBaseUrl() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Mem0LongTermMemory.builder().userId("user123").build());

        assertEquals("apiBaseUrl is required", exception.getMessage());
    }

    @Test
    void testBuilderWithEmptyApiBaseUrl() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                Mem0LongTermMemory.builder()
                                        .userId("user123")
                                        .apiBaseUrl("")
                                        .build());

        assertEquals("apiBaseUrl is required", exception.getMessage());
    }

    @Test
    void testBuilderWithCustomTimeout() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder()
                        .userId("user123")
                        .apiBaseUrl(baseUrl)
                        .timeout(Duration.ofSeconds(30))
                        .build();

        assertNotNull(memory);
    }

    @Test
    void testRecordWithValidMessages() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
    }

    @Test
    void testRecordWithNullMessages() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(null)).verifyComplete();
    }

    @Test
    void testRecordWithEmptyMessages() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.record(new ArrayList<>())).verifyComplete();
    }

    @Test
    void testRecordFiltersNullMessages() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
        String responseJson =
                "["
                        + "{\"id\":\"mem_1\",\"memory\":\"User prefers dark mode\",\"score\":0.95},"
                        + "{\"id\":\"mem_2\",\"memory\":\"User likes coffee\",\"score\":0.85}"
                        + "]";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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

    @Test
    void testRetrieveWithNoResults() {
        mockServer.enqueue(new MockResponse().setBody("[]").setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

        StepVerifier.create(memory.retrieve(null))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

        Msg query = Msg.builder().role(MsgRole.USER).build();

        StepVerifier.create(memory.retrieve(query))
                .assertNext(result -> assertEquals("", result))
                .verifyComplete();
    }

    @Test
    void testRetrieveWithHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
                "["
                        + "{\"id\":\"mem_1\",\"memory\":\"Valid memory\",\"score\":0.95},"
                        + "{\"id\":\"mem_2\",\"memory\":null,\"score\":0.85},"
                        + "{\"id\":\"mem_3\",\"memory\":\"Another valid\",\"score\":0.75}"
                        + "]";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
                        .setBody("{\"results\":[],\"message\":\"Success\"}")
                        .setResponseCode(200));

        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder().userId("user123").apiBaseUrl(baseUrl).build();

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
    }

    @Test
    void testBuilderWithNullApiKey() {
        Mem0LongTermMemory memory =
                Mem0LongTermMemory.builder()
                        .userId("user123")
                        .apiBaseUrl(baseUrl)
                        .apiKey(null)
                        .build();

        assertNotNull(memory);
    }
}
