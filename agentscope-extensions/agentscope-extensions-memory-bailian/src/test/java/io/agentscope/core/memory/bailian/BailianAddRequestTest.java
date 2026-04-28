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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BailianAddRequest}. */
class BailianAddRequestTest {

    @Test
    void testBuilderWithUserId() {
        BailianAddRequest request = BailianAddRequest.builder().userId("user123").build();

        assertEquals("user123", request.getUserId());
    }

    @Test
    void testBuilderWithMessages() {
        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Test message").build());

        BailianAddRequest request = BailianAddRequest.builder().messages(messages).build();

        assertEquals(messages, request.getMessages());
    }

    @Test
    void testBuilderWithCustomContent() {
        BailianAddRequest request =
                BailianAddRequest.builder().customContent("Custom memory content").build();

        assertEquals("Custom memory content", request.getCustomContent());
    }

    @Test
    void testBuilderWithProfileSchema() {
        BailianAddRequest request =
                BailianAddRequest.builder().profileSchema("profile_123").build();

        assertEquals("profile_123", request.getProfileSchema());
    }

    @Test
    void testBuilderWithMemoryLibraryId() {
        BailianAddRequest request = BailianAddRequest.builder().memoryLibraryId("lib_456").build();

        assertEquals("lib_456", request.getMemoryLibraryId());
    }

    @Test
    void testBuilderWithProjectId() {
        BailianAddRequest request = BailianAddRequest.builder().projectId("proj_789").build();

        assertEquals("proj_789", request.getProjectId());
    }

    @Test
    void testBuilderWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 123);

        BailianAddRequest request = BailianAddRequest.builder().metadata(metadata).build();

        assertEquals(metadata, request.getMetadata());
    }

    @Test
    void testBuilderWithAllFields() {
        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Test message").build());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .messages(messages)
                        .customContent("Custom content")
                        .profileSchema("profile_123")
                        .memoryLibraryId("lib_456")
                        .projectId("proj_789")
                        .metadata(metadata)
                        .build();

        assertEquals("user123", request.getUserId());
        assertEquals(messages, request.getMessages());
        assertEquals("Custom content", request.getCustomContent());
        assertEquals("profile_123", request.getProfileSchema());
        assertEquals("lib_456", request.getMemoryLibraryId());
        assertEquals("proj_789", request.getProjectId());
        assertEquals(metadata, request.getMetadata());
    }

    @Test
    void testSettersAndGetters() {
        BailianAddRequest request = new BailianAddRequest();

        request.setUserId("user456");
        assertEquals("user456", request.getUserId());

        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Message").build());
        request.setMessages(messages);
        assertEquals(messages, request.getMessages());

        request.setCustomContent("Custom");
        assertEquals("Custom", request.getCustomContent());

        request.setProfileSchema("schema");
        assertEquals("schema", request.getProfileSchema());

        request.setMemoryLibraryId("library");
        assertEquals("library", request.getMemoryLibraryId());

        request.setProjectId("project");
        assertEquals("project", request.getProjectId());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        request.setMetadata(metadata);
        assertEquals(metadata, request.getMetadata());
    }

    @Test
    void testDefaultConstructor() {
        BailianAddRequest request = new BailianAddRequest();

        assertNull(request.getUserId());
        assertNull(request.getMessages());
        assertNull(request.getCustomContent());
        assertNull(request.getProfileSchema());
        assertNull(request.getMemoryLibraryId());
        assertNull(request.getProjectId());
        assertNull(request.getMetadata());
    }

    @Test
    void testBuilderChain() {
        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId("user123")
                        .profileSchema("profile_123")
                        .memoryLibraryId("lib_456")
                        .projectId("proj_789")
                        .build();

        assertNotNull(request);
        assertEquals("user123", request.getUserId());
        assertEquals("profile_123", request.getProfileSchema());
        assertEquals("lib_456", request.getMemoryLibraryId());
        assertEquals("proj_789", request.getProjectId());
    }

    @Test
    void testBuilderWithEmptyMetadata() {
        BailianAddRequest request = BailianAddRequest.builder().metadata(new HashMap<>()).build();

        assertNotNull(request.getMetadata());
        assertEquals(0, request.getMetadata().size());
    }

    @Test
    void testBuilderWithNullValues() {
        BailianAddRequest request =
                BailianAddRequest.builder()
                        .userId(null)
                        .messages(null)
                        .customContent(null)
                        .profileSchema(null)
                        .memoryLibraryId(null)
                        .projectId(null)
                        .metadata(null)
                        .build();

        assertNull(request.getUserId());
        assertNull(request.getMessages());
        assertNull(request.getCustomContent());
        assertNull(request.getProfileSchema());
        assertNull(request.getMemoryLibraryId());
        assertNull(request.getProjectId());
        assertNull(request.getMetadata());
    }
}
