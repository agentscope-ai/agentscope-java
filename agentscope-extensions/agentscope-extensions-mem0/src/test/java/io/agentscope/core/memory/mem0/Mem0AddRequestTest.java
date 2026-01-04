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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0AddRequest}. */
class Mem0AddRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDefaultConstructor() {
        Mem0AddRequest request = new Mem0AddRequest();

        assertNotNull(request);
        // Verify default values
        assertTrue(request.getInfer());
        assertEquals("v1.1", request.getOutputFormat());
        assertTrue(request.getAsyncMode());
        assertFalse(request.getImmutable());
    }

    @Test
    void testBuilderWithMessages() {
        List<Mem0Message> messages =
                List.of(
                        Mem0Message.builder().role("user").content("Test message").build(),
                        Mem0Message.builder().role("assistant").content("Response").build());

        Mem0AddRequest request =
                Mem0AddRequest.builder().messages(messages).userId("user123").build();

        assertEquals(messages, request.getMessages());
        assertEquals("user123", request.getUserId());
    }

    @Test
    void testBuilderWithAllIdentifiers() {
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .agentId("agent1")
                        .userId("user1")
                        .appId("app1")
                        .runId("run1")
                        .build();

        assertEquals("agent1", request.getAgentId());
        assertEquals("user1", request.getUserId());
        assertEquals("app1", request.getAppId());
        assertEquals("run1", request.getRunId());
    }

    @Test
    void testBuilderWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");
        metadata.put("version", 1);

        Mem0AddRequest request =
                Mem0AddRequest.builder().metadata(metadata).userId("user1").build();

        assertEquals(metadata, request.getMetadata());
    }

    @Test
    void testBuilderWithInferAndOutputFormat() {
        Mem0AddRequest request =
                Mem0AddRequest.builder().infer(false).outputFormat("v1.0").userId("user1").build();

        assertFalse(request.getInfer());
        assertEquals("v1.0", request.getOutputFormat());
    }

    @Test
    void testBuilderWithCustomCategories() {
        Map<String, Object> categories = new HashMap<>();
        categories.put("personal", "Personal information");
        categories.put("preferences", "User preferences");

        Mem0AddRequest request =
                Mem0AddRequest.builder().customCategories(categories).userId("user1").build();

        assertEquals(categories, request.getCustomCategories());
    }

    @Test
    void testBuilderWithTimestampAndExpiration() {
        long timestamp = System.currentTimeMillis() / 1000;
        String expirationDate = "2025-12-31";

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .timestamp(timestamp)
                        .expirationDate(expirationDate)
                        .userId("user1")
                        .build();

        assertEquals(timestamp, request.getTimestamp());
        assertEquals(expirationDate, request.getExpirationDate());
    }

    @Test
    void testBuilderWithImmutableAndAsyncMode() {
        Mem0AddRequest request =
                Mem0AddRequest.builder().immutable(true).asyncMode(false).userId("user1").build();

        assertTrue(request.getImmutable());
        assertFalse(request.getAsyncMode());
    }

    @Test
    void testBuilderWithOrgAndProject() {
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .orgId("org123")
                        .projectId("project456")
                        .userId("user1")
                        .build();

        assertEquals("org123", request.getOrgId());
        assertEquals("project456", request.getProjectId());
    }

    @Test
    void testBuilderWithVersion() {
        Mem0AddRequest request = Mem0AddRequest.builder().version("v2").userId("user1").build();

        assertEquals("v2", request.getVersion());
    }

    @Test
    @SuppressWarnings("deprecation")
    void testBuilderWithDeprecatedMemoryType() {
        Mem0AddRequest request =
                Mem0AddRequest.builder().memoryType("semantic").userId("user1").build();

        assertEquals("semantic", request.getMemoryType());
    }

    @Test
    void testSettersAndGetters() {
        Mem0AddRequest request = new Mem0AddRequest();

        List<Mem0Message> messages =
                List.of(Mem0Message.builder().role("user").content("Test").build());
        request.setMessages(messages);
        request.setAgentId("agent1");
        request.setUserId("user1");
        request.setInfer(false);
        request.setOutputFormat("v1.0");
        request.setImmutable(true);
        request.setAsyncMode(false);

        assertEquals(messages, request.getMessages());
        assertEquals("agent1", request.getAgentId());
        assertEquals("user1", request.getUserId());
        assertFalse(request.getInfer());
        assertEquals("v1.0", request.getOutputFormat());
        assertTrue(request.getImmutable());
        assertFalse(request.getAsyncMode());
    }

    @Test
    void testJsonSerialization() throws Exception {
        List<Mem0Message> messages =
                List.of(Mem0Message.builder().role("user").content("Hello").build());

        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(messages)
                        .userId("user123")
                        .agentId("agent456")
                        .infer(true)
                        .build();

        String json = objectMapper.writeValueAsString(request);
        assertNotNull(json);

        // Verify key fields are present
        assertTrue(json.contains("\"messages\""));
        assertTrue(json.contains("\"user_id\""));
        assertTrue(json.contains("\"agent_id\""));
        assertTrue(json.contains("\"infer\""));
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json =
                "{"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"Test\"}],"
                        + "\"user_id\":\"user123\","
                        + "\"agent_id\":\"agent456\","
                        + "\"infer\":true,"
                        + "\"output_format\":\"v1.1\","
                        + "\"immutable\":false,"
                        + "\"async_mode\":true"
                        + "}";

        Mem0AddRequest request = objectMapper.readValue(json, Mem0AddRequest.class);

        assertNotNull(request);
        assertNotNull(request.getMessages());
        assertEquals(1, request.getMessages().size());
        assertEquals("user123", request.getUserId());
        assertEquals("agent456", request.getAgentId());
        assertTrue(request.getInfer());
        assertEquals("v1.1", request.getOutputFormat());
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        List<Mem0Message> messages =
                List.of(
                        Mem0Message.builder().role("user").content("Question").build(),
                        Mem0Message.builder().role("assistant").content("Answer").build());

        Mem0AddRequest original =
                Mem0AddRequest.builder()
                        .messages(messages)
                        .userId("user123")
                        .agentId("agent456")
                        .runId("run789")
                        .infer(true)
                        .outputFormat("v1.1")
                        .immutable(false)
                        .asyncMode(true)
                        .build();

        String json = objectMapper.writeValueAsString(original);
        Mem0AddRequest deserialized = objectMapper.readValue(json, Mem0AddRequest.class);

        assertEquals(original.getMessages().size(), deserialized.getMessages().size());
        assertEquals(original.getUserId(), deserialized.getUserId());
        assertEquals(original.getAgentId(), deserialized.getAgentId());
        assertEquals(original.getRunId(), deserialized.getRunId());
        assertEquals(original.getInfer(), deserialized.getInfer());
        assertEquals(original.getOutputFormat(), deserialized.getOutputFormat());
        assertEquals(original.getImmutable(), deserialized.getImmutable());
        assertEquals(original.getAsyncMode(), deserialized.getAsyncMode());
    }

    @Test
    void testJsonExcludesNullFields() throws Exception {
        Mem0AddRequest request = Mem0AddRequest.builder().userId("user123").build();

        String json = objectMapper.writeValueAsString(request);

        // Null fields should be excluded due to @JsonInclude(NON_NULL)
        assertFalse(json.contains("\"agent_id\""));
        assertFalse(json.contains("\"app_id\""));
        assertFalse(json.contains("\"run_id\""));
        assertFalse(json.contains("\"metadata\""));
    }

    @Test
    void testJsonIncludesNonNullFields() throws Exception {
        Mem0AddRequest request =
                Mem0AddRequest.builder().userId("user123").agentId("agent456").infer(true).build();

        String json = objectMapper.writeValueAsString(request);

        // Non-null fields should be included
        assertTrue(json.contains("\"user_id\":\"user123\""));
        assertTrue(json.contains("\"agent_id\":\"agent456\""));
        assertTrue(json.contains("\"infer\":true"));
    }

    @Test
    void testBuilderWithIncludesAndExcludes() {
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .includes("important facts")
                        .excludes("trivial details")
                        .userId("user1")
                        .build();

        assertEquals("important facts", request.getIncludes());
        assertEquals("trivial details", request.getExcludes());
    }

    @Test
    void testBuilderWithCustomInstructions() {
        String instructions = "Focus on preferences and personal information";
        Mem0AddRequest request =
                Mem0AddRequest.builder().customInstructions(instructions).userId("user1").build();

        assertEquals(instructions, request.getCustomInstructions());
    }

    @Test
    void testFieldNameMapping() throws Exception {
        // Verify that Java field names are properly mapped to JSON with snake_case
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .userId("test_user")
                        .agentId("test_agent")
                        .runId("test_run")
                        .appId("test_app")
                        .orgId("test_org")
                        .projectId("test_project")
                        .outputFormat("v1.1")
                        .asyncMode(true)
                        .expirationDate("2025-12-31")
                        .build();

        String json = objectMapper.writeValueAsString(request);

        // Verify snake_case field names in JSON
        assertTrue(json.contains("\"user_id\""));
        assertTrue(json.contains("\"agent_id\""));
        assertTrue(json.contains("\"run_id\""));
        assertTrue(json.contains("\"app_id\""));
        assertTrue(json.contains("\"org_id\""));
        assertTrue(json.contains("\"project_id\""));
        assertTrue(json.contains("\"output_format\""));
        assertTrue(json.contains("\"async_mode\""));
        assertTrue(json.contains("\"expiration_date\""));
    }

    @Test
    void testNullMessagesHandling() {
        Mem0AddRequest request = Mem0AddRequest.builder().userId("user1").build();

        assertNull(request.getMessages());
    }
}
