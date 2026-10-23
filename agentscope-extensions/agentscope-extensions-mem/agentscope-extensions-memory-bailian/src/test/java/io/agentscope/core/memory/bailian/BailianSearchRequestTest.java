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

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BailianSearchRequest}. */
class BailianSearchRequestTest {

    @Test
    void testBuilderWithUserId() {
        BailianSearchRequest request = BailianSearchRequest.builder().userId("user123").build();

        assertEquals("user123", request.getUserId());
    }

    @Test
    void testBuilderWithMessages() {
        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Search query").build());

        BailianSearchRequest request = BailianSearchRequest.builder().messages(messages).build();

        assertEquals(messages, request.getMessages());
    }

    @Test
    void testBuilderWithMemoryLibraryId() {
        BailianSearchRequest request =
                BailianSearchRequest.builder().memoryLibraryId("lib_456").build();

        assertEquals("lib_456", request.getMemoryLibraryId());
    }

    @Test
    void testBuilderWithProjectIds() {
        List<String> projectIds = List.of("proj_1", "proj_2", "proj_3");

        BailianSearchRequest request =
                BailianSearchRequest.builder().projectIds(projectIds).build();

        assertEquals(projectIds, request.getProjectIds());
    }

    @Test
    void testBuilderWithTopK() {
        BailianSearchRequest request = BailianSearchRequest.builder().topK(15).build();

        assertEquals(15, request.getTopK());
    }

    @Test
    void testBuilderWithMinScore() {
        BailianSearchRequest request = BailianSearchRequest.builder().minScore(0.7).build();

        assertEquals(0.7, request.getMinScore());
    }

    @Test
    void testBuilderWithEnableRerank() {
        BailianSearchRequest request = BailianSearchRequest.builder().enableRerank(true).build();

        assertEquals(true, request.getEnableRerank());
    }

    @Test
    void testBuilderWithEnableJudge() {
        BailianSearchRequest request = BailianSearchRequest.builder().enableJudge(true).build();

        assertEquals(true, request.getEnableJudge());
    }

    @Test
    void testBuilderWithEnableRewrite() {
        BailianSearchRequest request = BailianSearchRequest.builder().enableRewrite(true).build();

        assertEquals(true, request.getEnableRewrite());
    }

    @Test
    void testBuilderWithAllFields() {
        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Search query").build());
        List<String> projectIds = List.of("proj_1", "proj_2");

        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .messages(messages)
                        .memoryLibraryId("lib_456")
                        .projectIds(projectIds)
                        .topK(10)
                        .minScore(0.5)
                        .enableRerank(true)
                        .enableJudge(true)
                        .enableRewrite(true)
                        .build();

        assertEquals("user123", request.getUserId());
        assertEquals(messages, request.getMessages());
        assertEquals("lib_456", request.getMemoryLibraryId());
        assertEquals(projectIds, request.getProjectIds());
        assertEquals(10, request.getTopK());
        assertEquals(0.5, request.getMinScore());
        assertEquals(true, request.getEnableRerank());
        assertEquals(true, request.getEnableJudge());
        assertEquals(true, request.getEnableRewrite());
    }

    @Test
    void testSettersAndGetters() {
        BailianSearchRequest request = new BailianSearchRequest();

        request.setUserId("user456");
        assertEquals("user456", request.getUserId());

        List<BailianMessage> messages =
                List.of(BailianMessage.builder().role("user").content("Query").build());
        request.setMessages(messages);
        assertEquals(messages, request.getMessages());

        request.setMemoryLibraryId("library");
        assertEquals("library", request.getMemoryLibraryId());

        List<String> projectIds = List.of("proj_a", "proj_b");
        request.setProjectIds(projectIds);
        assertEquals(projectIds, request.getProjectIds());

        request.setTopK(20);
        assertEquals(20, request.getTopK());

        request.setMinScore(0.8);
        assertEquals(0.8, request.getMinScore());

        request.setEnableRerank(false);
        assertEquals(false, request.getEnableRerank());

        request.setEnableJudge(false);
        assertEquals(false, request.getEnableJudge());

        request.setEnableRewrite(false);
        assertEquals(false, request.getEnableRewrite());
    }

    @Test
    void testDefaultConstructor() {
        BailianSearchRequest request = new BailianSearchRequest();

        assertNull(request.getUserId());
        assertNull(request.getMessages());
        assertNull(request.getMemoryLibraryId());
        assertNull(request.getProjectIds());
        assertNull(request.getTopK());
        assertNull(request.getMinScore());
        assertNull(request.getEnableRerank());
        assertNull(request.getEnableJudge());
        assertNull(request.getEnableRewrite());
    }

    @Test
    void testBuilderChain() {
        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId("user123")
                        .memoryLibraryId("lib_456")
                        .topK(10)
                        .minScore(0.5)
                        .enableRerank(true)
                        .build();

        assertNotNull(request);
        assertEquals("user123", request.getUserId());
        assertEquals("lib_456", request.getMemoryLibraryId());
        assertEquals(10, request.getTopK());
        assertEquals(0.5, request.getMinScore());
        assertEquals(true, request.getEnableRerank());
    }

    @Test
    void testBuilderWithNullValues() {
        BailianSearchRequest request =
                BailianSearchRequest.builder()
                        .userId(null)
                        .messages(null)
                        .memoryLibraryId(null)
                        .projectIds(null)
                        .topK(null)
                        .minScore(null)
                        .enableRerank(null)
                        .enableJudge(null)
                        .enableRewrite(null)
                        .build();

        assertNull(request.getUserId());
        assertNull(request.getMessages());
        assertNull(request.getMemoryLibraryId());
        assertNull(request.getProjectIds());
        assertNull(request.getTopK());
        assertNull(request.getMinScore());
        assertNull(request.getEnableRerank());
        assertNull(request.getEnableJudge());
        assertNull(request.getEnableRewrite());
    }

    @Test
    void testBuilderWithEmptyProjectIds() {
        BailianSearchRequest request = BailianSearchRequest.builder().projectIds(List.of()).build();

        assertNotNull(request.getProjectIds());
        assertEquals(0, request.getProjectIds().size());
    }

    @Test
    void testBuilderWithSingleProjectId() {
        BailianSearchRequest request =
                BailianSearchRequest.builder().projectIds(List.of("proj_1")).build();

        assertNotNull(request.getProjectIds());
        assertEquals(1, request.getProjectIds().size());
        assertEquals("proj_1", request.getProjectIds().get(0));
    }

    @Test
    void testBuilderWithBooleanDefaults() {
        BailianSearchRequest request = BailianSearchRequest.builder().build();

        assertNull(request.getEnableRerank());
        assertNull(request.getEnableJudge());
        assertNull(request.getEnableRewrite());
    }
}
