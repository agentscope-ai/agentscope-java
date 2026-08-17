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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BailianKnowledgeTest {

    @Mock private BailianClient mockClient;

    private BailianKnowledge knowledge;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockClient.getWorkspaceId()).thenReturn("test-workspace");
        knowledge = BailianKnowledge.builder().client(mockClient).indexId("test-index").build();
    }

    @Test
    void testBuilderWithConfig() throws Exception {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .indexId("test-index")
                        .build();

        // Building with config should successfully create BailianKnowledge
        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        assertNotNull(knowledge);
        assertEquals("test-index", knowledge.getIndexId());
    }

    @Test
    void testBuilderWithClient() {
        BailianKnowledge knowledge =
                BailianKnowledge.builder().client(mockClient).indexId("test-index").build();

        assertNotNull(knowledge);
        assertEquals("test-index", knowledge.getIndexId());
        assertEquals(mockClient, knowledge.getClient());
    }

    @Test
    void testBuilderMissingIndexId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BailianKnowledge.builder().client(mockClient).build());
    }

    @Test
    void testBuilderMissingClientAndConfig() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BailianKnowledge.builder().indexId("test-index").build());
    }

    @Test
    void testBuilderBothClientAndConfig() {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .build();

        assertThrows(
                IllegalStateException.class,
                () ->
                        BailianKnowledge.builder()
                                .config(config)
                                .client(mockClient)
                                .indexId("test-index")
                                .build());
    }

    @Test
    void testRetrieveSuccess() {
        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();

        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Test content");
        node.setScore(0.85);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "doc123");
        metadata.put("_id", "chunk456");
        node.setMetadata(metadata);

        data.setNodes(List.of(node));
        body.setData(data);
        response.setBody(body);

        when(mockClient.retrieve(anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.just(response));

        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve("test query", config))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertEquals(1, documents.size());
                            assertEquals(0.85, documents.get(0).getScore());
                        })
                .verifyComplete();

        verify(mockClient).retrieve("test-index", "test query", 5, null);
    }

    @Test
    void testRetrieveWithScoreFiltering() {
        // Create mock response with multiple documents
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();

        // High score document
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node1 =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node1.setText("High score content");
        node1.setScore(0.85);
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("doc_id", "doc1");
        meta1.put("_id", "chunk1");
        node1.setMetadata(meta1);

        // Low score document
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node2 =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node2.setText("Low score content");
        node2.setScore(0.3);
        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("doc_id", "doc2");
        meta2.put("_id", "chunk2");
        node2.setMetadata(meta2);

        data.setNodes(List.of(node1, node2));
        body.setData(data);
        response.setBody(body);

        when(mockClient.retrieve(anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.just(response));

        RetrieveConfig config = RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve("test query", config))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertEquals(1, documents.size());
                            assertEquals(0.85, documents.get(0).getScore());
                        })
                .verifyComplete();
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve("", config))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertTrue(documents.isEmpty());
                        })
                .verifyComplete();

        verify(mockClient, never()).retrieve(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void testRetrieveWithNullQuery() {
        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve(null, config))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveWithNullConfig() {
        StepVerifier.create(knowledge.retrieve("test query", (RetrieveConfig) null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveClientError() {
        when(mockClient.retrieve(anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.error(new RuntimeException("API error")));

        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve("test query", config))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testRetrieveEmptyResult() {
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockClient.retrieve(anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.just(response));

        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        StepVerifier.create(knowledge.retrieve("test query", config))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertTrue(documents.isEmpty());
                        })
                .verifyComplete();
    }

    @Test
    void testAddDocumentsNotSupported() {
        StepVerifier.create(knowledge.addDocuments(new ArrayList<>()))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    @Test
    void testGetters() {
        assertEquals("test-index", knowledge.getIndexId());
        assertEquals(mockClient, knowledge.getClient());
    }
}
