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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.studio.pojo.PushMessageRequest;
import io.agentscope.core.studio.pojo.RegisterRunRequest;
import io.agentscope.core.studio.pojo.RequestUserInputRequest;
import io.agentscope.core.studio.pojo.UserInputMetadata;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Request POJOs Tests")
class RequestPojosTest {
    @Test
    @DisplayName("RegisterRunRequest should build and serialize correctly")
    void testRegisterRunRequest() throws Exception {
        RegisterRunRequest request =
                RegisterRunRequest.builder()
                        .id("run-123")
                        .project("MyProject")
                        .name("my_run")
                        .timestamp("2024-01-01 10:00:00.000")
                        .pid(12345L)
                        .status("running")
                        .runDir("/path/to/run")
                        .build();

        assertEquals("run-123", request.getId());
        assertEquals("MyProject", request.getProject());
        assertEquals("my_run", request.getName());
        assertEquals("2024-01-01 10:00:00.000", request.getTimestamp());
        assertEquals(12345L, request.getPid());
        assertEquals("running", request.getStatus());
        assertEquals("/path/to/run", request.getRunDir());

        // Test JSON serialization
        String json = JsonUtils.getJsonCodec().toJson(request);
        assertNotNull(json);
        Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals("run-123", map.get("id"));
        assertEquals("MyProject", map.get("project"));
        assertEquals("my_run", map.get("name"));
    }

    @Test
    @DisplayName("PushMessageRequest should build and serialize correctly")
    void testPushMessageRequest() throws Exception {
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello!").build())
                        .build();

        PushMessageRequest request =
                PushMessageRequest.builder()
                        .runId("run-123")
                        .replyId("reply-456")
                        .name("TestAgent")
                        .role("assistant")
                        .msg(msg)
                        .build();

        assertEquals("run-123", request.getRunId());
        assertEquals("reply-456", request.getReplyId());
        assertEquals("TestAgent", request.getName());
        assertEquals("assistant", request.getRole());
        assertNotNull(request.getMsg());
        assertEquals(msg, request.getMsg());

        // Test JSON serialization
        String json = JsonUtils.getJsonCodec().toJson(request);
        assertNotNull(json);
        Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals("run-123", map.get("runId"));
        assertEquals("reply-456", map.get("replyId"));
        assertEquals("TestAgent", map.get("name"));
        assertEquals("assistant", map.get("role"));
    }

    @Test
    @DisplayName("RequestUserInputRequest should build and serialize correctly")
    void testRequestUserInputRequest() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());

        RequestUserInputRequest request =
                RequestUserInputRequest.builder()
                        .requestId("req-123")
                        .runId("run-456")
                        .agentId("agent-789")
                        .agentName("UserAgent")
                        .structuredInput(schema)
                        .build();

        assertEquals("req-123", request.getRequestId());
        assertEquals("run-456", request.getRunId());
        assertEquals("agent-789", request.getAgentId());
        assertEquals("UserAgent", request.getAgentName());
        assertEquals(schema, request.getStructuredInput());

        // Test JSON serialization
        String json = JsonUtils.getJsonCodec().toJson(request);
        assertNotNull(json);
        Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals("req-123", map.get("requestId"));
        assertEquals("run-456", map.get("runId"));
        assertEquals("agent-789", map.get("agentId"));
    }

    @Test
    @DisplayName("RequestUserInputRequest with null structuredInput should use empty map")
    void testRequestUserInputRequestNullSchema() throws Exception {
        RequestUserInputRequest request =
                RequestUserInputRequest.builder()
                        .requestId("req-123")
                        .runId("run-456")
                        .agentId("agent-789")
                        .agentName("UserAgent")
                        .structuredInput(null)
                        .build();

        assertNotNull(request.getStructuredInput());
        assertEquals(Map.of(), request.getStructuredInput());
    }

    @Test
    @DisplayName("UserInputMetadata should build and serialize correctly")
    void testUserInputMetadata() throws Exception {
        Map<String, Object> structuredInput = Map.of("key1", "value1", "key2", 123);

        UserInputMetadata metadata =
                UserInputMetadata.builder()
                        .requestId("req-123")
                        .structuredInput(structuredInput)
                        .build();

        assertEquals("studio", metadata.getSource()); // Default value
        assertEquals("req-123", metadata.getRequestId());
        assertEquals(structuredInput, metadata.getStructuredInput());

        // Test JSON serialization
        String json = JsonUtils.getJsonCodec().toJson(metadata);
        assertNotNull(json);
        Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals("studio", map.get("source"));
        assertEquals("req-123", map.get("requestId"));
        assertNotNull(map.get("structuredInput"));
    }

    @Test
    @DisplayName("UserInputMetadata with null structuredInput should not include it in JSON")
    void testUserInputMetadataNullStructuredInput() throws Exception {
        UserInputMetadata metadata =
                UserInputMetadata.builder().requestId("req-123").structuredInput(null).build();

        assertNull(metadata.getStructuredInput());

        // Test JSON serialization - structuredInput should be excluded
        String json = JsonUtils.getJsonCodec().toJson(metadata);
        assertNotNull(json);
        Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals("studio", map.get("source"));
        assertEquals("req-123", map.get("requestId"));
        // structuredInput should not be in the map due to @JsonInclude(NON_NULL)
        assertEquals(false, map.containsKey("structuredInput"));
    }

    @Test
    @DisplayName("UserInputMetadata with custom source should work")
    void testUserInputMetadataCustomSource() throws Exception {
        UserInputMetadata metadata =
                UserInputMetadata.builder().source("custom-source").requestId("req-123").build();

        assertEquals("custom-source", metadata.getSource());
    }
}
