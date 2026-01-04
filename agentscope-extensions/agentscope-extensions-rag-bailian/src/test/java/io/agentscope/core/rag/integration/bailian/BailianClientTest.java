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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.RetrieveRequest;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

class BailianClientTest {

    private Client mockSdkClient;
    private BailianConfig testConfig;

    @BeforeEach
    void setUp() {
        mockSdkClient = mock(Client.class);
        testConfig =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .build();
    }

    @Test
    void testConstructorWithNullConfig() {
        assertThrows(IllegalArgumentException.class, () -> new BailianClient(null));
    }

    @Test
    void testConstructorWithValidConfig() throws Exception {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .build();

        BailianClient client = new BailianClient(config);

        assertNotNull(client);
        assertEquals("test-workspace", client.getWorkspaceId());
    }

    @Test
    void testTestConstructorWithNullSdkClient() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BailianClient(null, "workspace", testConfig));
    }

    @Test
    void testTestConstructorWithNullWorkspaceId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BailianClient(mockSdkClient, null, testConfig));
    }

    @Test
    void testTestConstructorWithEmptyWorkspaceId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BailianClient(mockSdkClient, "", testConfig));
    }

    @Test
    void testTestConstructorWithValidParameters() {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        assertNotNull(client);
        assertEquals("test-workspace", client.getWorkspaceId());
    }

    @Test
    void testRetrieveWithNullIndexId() {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        StepVerifier.create(client.retrieve(null, "query", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveWithEmptyIndexId() {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        StepVerifier.create(client.retrieve("", "query", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveWithNullQuery() {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        StepVerifier.create(client.retrieve("index-id", null, 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        StepVerifier.create(client.retrieve("index-id", "", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void testRetrieveSuccess() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify the SDK client was called with correct parameters
        ArgumentCaptor<String> workspaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);

        verify(mockSdkClient)
                .retrieveWithOptions(
                        workspaceCaptor.capture(),
                        requestCaptor.capture(),
                        anyMap(),
                        any(RuntimeOptions.class));

        assertEquals("test-workspace", workspaceCaptor.getValue());
        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals("test-index", capturedRequest.getIndexId());
        assertEquals("test query", capturedRequest.getQuery());
        assertEquals(5, capturedRequest.getDenseSimilarityTopK());
    }

    @Test
    void testRetrieveWithConversationHistory() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        List<QueryHistoryEntry> history = new ArrayList<>();
        history.add(QueryHistoryEntry.user("previous question"));
        history.add(QueryHistoryEntry.assistant("previous answer"));

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5, history))
                .expectNext(response)
                .verifyComplete();

        // Verify query history was passed
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertNotNull(capturedRequest.getQueryHistory());
        assertEquals(2, capturedRequest.getQueryHistory().size());
    }

    @Test
    void testRetrieveWithRerankConfig() throws Exception {
        RerankConfig rerankConfig =
                RerankConfig.builder()
                        .modelName("gte-rerank")
                        .rerankMinScore(0.5f)
                        .rerankTopN(10)
                        .build();

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .enableReranking(true)
                        .rerankConfig(rerankConfig)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify rerank config was passed
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals(true, capturedRequest.getEnableReranking());
        assertEquals(0.5f, capturedRequest.getRerankMinScore());
        assertEquals(10, capturedRequest.getRerankTopN());
        assertNotNull(capturedRequest.getRerank());
        assertEquals(1, capturedRequest.getRerank().size());
        assertEquals("gte-rerank", capturedRequest.getRerank().get(0).getModelName());
    }

    @Test
    void testRetrieveWithRewriteConfig() throws Exception {
        RewriteConfig rewriteConfig = RewriteConfig.builder().modelName("custom-rewrite").build();

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .enableRewrite(true)
                        .rewriteConfig(rewriteConfig)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify rewrite config was passed
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals(true, capturedRequest.getEnableRewrite());
        assertNotNull(capturedRequest.getRewrite());
        assertEquals(1, capturedRequest.getRewrite().size());
        assertEquals("custom-rewrite", capturedRequest.getRewrite().get(0).getModelName());
    }

    @Test
    void testRetrieveWithSearchFilters() throws Exception {
        List<Map<String, String>> filters = new ArrayList<>();
        Map<String, String> filter = new HashMap<>();
        filter.put("field", "category");
        filter.put("operator", "eq");
        filter.put("value", "tech");
        filters.add(filter);

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .searchFilters(filters)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify search filters were passed
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertNotNull(capturedRequest.getSearchFilters());
        assertEquals(1, capturedRequest.getSearchFilters().size());
    }

    @Test
    void testRetrieveWithDenseSimilarityTopK() throws Exception {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .denseSimilarityTopK(20)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", null))
                .expectNext(response)
                .verifyComplete();

        // Verify denseSimilarityTopK from config was used
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals(20, capturedRequest.getDenseSimilarityTopK());
    }

    @Test
    void testRetrieveWithSparseSimilarityTopK() throws Exception {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .sparseSimilarityTopK(15)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify sparseSimilarityTopK from config was used
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals(15, capturedRequest.getSparseSimilarityTopK());
    }

    @Test
    void testRetrieveWithSaveRetrieverHistory() throws Exception {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key")
                        .accessKeySecret("test-secret")
                        .workspaceId("test-workspace")
                        .saveRetrieverHistory(true)
                        .build();

        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", config);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();

        // Verify saveRetrieverHistory was passed
        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals(true, capturedRequest.getSaveRetrieverHistory());
    }

    @Test
    void testRetrieveNullResponse() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(null);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testRetrieveNullBody() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        RetrieveResponse response = new RetrieveResponse();
        response.setBody(null);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testRetrieveNullData() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        body.setData(null);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        StepVerifier.create(client.retrieve("test-index", "test query", 5))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void testRetrieveThreeParameterOverload() throws Exception {
        BailianClient client = new BailianClient(mockSdkClient, "test-workspace", testConfig);

        // Create mock response
        RetrieveResponse response = new RetrieveResponse();
        RetrieveResponseBody body = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(new ArrayList<>());
        body.setData(data);
        response.setBody(body);

        when(mockSdkClient.retrieveWithOptions(
                        anyString(),
                        any(RetrieveRequest.class),
                        anyMap(),
                        any(RuntimeOptions.class)))
                .thenReturn(response);

        // Test 3-parameter retrieve method
        StepVerifier.create(client.retrieve("test-index", "test query", 10))
                .expectNext(response)
                .verifyComplete();

        ArgumentCaptor<RetrieveRequest> requestCaptor =
                ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(mockSdkClient)
                .retrieveWithOptions(
                        anyString(), requestCaptor.capture(), anyMap(), any(RuntimeOptions.class));

        RetrieveRequest capturedRequest = requestCaptor.getValue();
        assertEquals("test-index", capturedRequest.getIndexId());
        assertEquals("test query", capturedRequest.getQuery());
        assertEquals(10, capturedRequest.getDenseSimilarityTopK());
    }

    @Test
    void testGetWorkspaceId() {
        BailianClient client = new BailianClient(mockSdkClient, "my-workspace", testConfig);

        assertEquals("my-workspace", client.getWorkspaceId());
    }
}
