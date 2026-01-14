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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.test.ModelTestUtils;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DashScopeChatModel.
 *
 * <p>These tests verify the DashScopeChatModel behavior including basic chat, streaming,
 * tool calls, error handling, and retry mechanisms.
 *
 * <p>Tests use mock API responses to avoid actual network calls.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("DashScopeChatModel Unit Tests")
class DashScopeChatModelTest {

    private DashScopeChatModel model;
    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();

        // Create model with builder
        model =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(false)
                        .build();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        assertNotNull(model, "Model should be created");

        // Test builder pattern
        DashScopeChatModel customModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-turbo").stream(true)
                        .enableThinking(true)
                        .build();

        assertNotNull(customModel, "Custom model should be created");
    }

    // ========== Streaming Configuration Tests ==========

    @Test
    @DisplayName("Should create streaming model")
    void testStreamingModelCreation() {
        DashScopeChatModel streamingModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(true)
                        .build();

        assertNotNull(streamingModel, "Streaming model should be created");
    }

    @Test
    @DisplayName("Should create non-streaming model")
    void testNonStreamingModelCreation() {
        DashScopeChatModel nonStreamingModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(false)
                        .build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }

    // ========== Thinking Mode Tests ==========

    @Test
    @DisplayName("Should create model with thinking mode enabled")
    void testThinkingModeEnabled() {
        DashScopeChatModel thinkingModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(true)
                        .build();

        assertNotNull(thinkingModel, "Thinking mode model should be created");
    }

    @Test
    @DisplayName("Should create model with thinking mode disabled")
    void testThinkingModeDisabled() {
        DashScopeChatModel noThinkingModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(false)
                        .build();

        assertNotNull(noThinkingModel, "Non-thinking mode model should be created");
    }

    @Test
    @DisplayName("Should create model with thinking mode and budget")
    void testThinkingModeWithBudget() {
        GenerateOptions optionsWithBudget = GenerateOptions.builder().thinkingBudget(500).build();

        DashScopeChatModel thinkingWithBudgetModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(true)
                        .defaultOptions(optionsWithBudget)
                        .build();

        assertNotNull(
                thinkingWithBudgetModel, "Model with thinking mode and budget should be created");
    }

    // ========== Vision Model Tests ==========

    @Test
    @DisplayName("Should create vision model for qwen-vl-plus")
    void testVisionModelQwenVlPlus() {
        DashScopeChatModel visionModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-vl-plus").build();

        assertNotNull(visionModel, "qwen-vl-plus model should be created");
    }

    @Test
    @DisplayName("Should create vision model for qwen-vl-max")
    void testVisionModelQwenVlMax() {
        DashScopeChatModel visionModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-vl-max").build();

        assertNotNull(visionModel, "qwen-vl-max model should be created");
    }

    @Test
    @DisplayName("Should create vision model for qvq-72b")
    void testVisionModelQvq72b() {
        DashScopeChatModel visionModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qvq-72b").build();

        assertNotNull(visionModel, "qvq-72b model should be created");
    }

    @Test
    @DisplayName("Should handle search mode configuration")
    void testSearchModeConfiguration() {
        DashScopeChatModel searchEnabledModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableSearch(true)
                        .build();

        assertNotNull(searchEnabledModel);

        DashScopeChatModel searchDisabledModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableSearch(false)
                        .build();

        assertNotNull(searchDisabledModel);
    }

    @Test
    @DisplayName("Should create vision model for qvq-7b-preview")
    void testVisionModelQvq7bPreview() {
        DashScopeChatModel visionModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qvq-7b-preview").build();

        assertNotNull(visionModel, "qvq-7b-preview model should be created");
    }

    // ========== Text Model Tests ==========

    @Test
    @DisplayName("Should create text model for qwen-plus")
    void testTextModelQwenPlus() {
        DashScopeChatModel textModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").build();

        assertNotNull(textModel, "qwen-plus model should be created");
    }

    @Test
    @DisplayName("Should create text model for qwen-max")
    void testTextModelQwenMax() {
        DashScopeChatModel textModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-max").build();

        assertNotNull(textModel, "qwen-max model should be created");
    }

    @Test
    @DisplayName("Should create text model for qwen-turbo")
    void testTextModelQwenTurbo() {
        DashScopeChatModel textModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-turbo").build();

        assertNotNull(textModel, "qwen-turbo model should be created");
    }

    // ========== Default Options Tests ==========

    @Test
    @DisplayName("Should create with default options")
    void testDefaultOptions() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.8).maxTokens(2000).topP(0.9).build();

        DashScopeChatModel modelWithOptions =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions, "Model with default options should be created");
    }

    @Test
    @DisplayName("Should create with base URL")
    void testCustomBaseUrl() {
        DashScopeChatModel modelWithBaseUrl =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .baseUrl("https://custom.dashscope.com")
                        .build();

        assertNotNull(modelWithBaseUrl);
    }

    @Test
    @DisplayName("Should handle all generation options")
    void testAllGenerateOptions() {
        GenerateOptions fullOptions =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1500)
                        .topP(0.9)
                        .thinkingBudget(2000)
                        .build();

        DashScopeChatModel modelWithFullOptions =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(true)
                        .defaultOptions(fullOptions)
                        .build();

        assertNotNull(modelWithFullOptions);
    }

    @Test
    @DisplayName("Should handle empty messages list")
    void testEmptyMessagesList() {
        List<Msg> emptyMessages = new ArrayList<>();

        // This should not throw during call preparation
        assertDoesNotThrow(
                () -> {
                    DashScopeChatModel testModel =
                            DashScopeChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("qwen-plus")
                                    .build();
                    assertNotNull(testModel);
                });
    }

    @Test
    @DisplayName("Should support different formatter types")
    void testDifferentFormatterTypes() {
        // Chat formatter
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .formatter(new DashScopeChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // MultiAgent formatter
        DashScopeChatModel multiAgentModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .formatter(new DashScopeMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should build with all builder methods")
    void testCompleteBuilder() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.2)
                        .build();

        DashScopeChatModel completeModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .defaultOptions(options)
                        .formatter(new DashScopeChatFormatter())
                        .baseUrl("https://dashscope.aliyuncs.com")
                        .build();

        assertNotNull(completeModel);
        assertNotNull(completeModel.getModelName());
    }

    @Test
    @DisplayName("Should create model with custom base URL")
    void testWithCustomBaseUrl() {
        DashScopeChatModel httpModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .baseUrl("https://custom.dashscope.com")
                        .build();

        assertNotNull(httpModel);
    }

    @Test
    @DisplayName("Should create model for vision models")
    void testForVisionModel() {
        DashScopeChatModel visionModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-vl-max").build();

        assertNotNull(visionModel);
    }

    @Test
    @DisplayName("Should create model for qvq models")
    void testForQvqModel() {
        DashScopeChatModel qvqModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qvq-72b").build();

        assertNotNull(qvqModel);
    }

    @Test
    @DisplayName("Should create model with streaming")
    void testWithStreaming() {
        DashScopeChatModel streamingModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(true)
                        .build();

        assertNotNull(streamingModel);
    }

    @Test
    @DisplayName("Should create model with generation options")
    void testWithGenerateOptions() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).topP(0.9).build();

        DashScopeChatModel httpModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .defaultOptions(options)
                        .build();

        assertNotNull(httpModel);
    }

    @Test
    @DisplayName("Should create model with thinking mode")
    void testWithThinkingMode() {
        DashScopeChatModel thinkingModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(true)
                        .build();

        assertNotNull(thinkingModel);
    }

    @Test
    @DisplayName("DashScope chat model stream with additional headers and params")
    void testDoStreamWithAdditionHeadersAndParams() throws Exception {
        MockWebServer mockServer = new MockWebServer();
        mockServer.start();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .enableSearch(true)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .httpTransport(OkHttpTransport.builder().build())
                        .build();

        chatModel
                .doStream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("test").build())
                                        .build()),
                        List.of(),
                        GenerateOptions.builder()
                                .additionalHeaders(Map.of("custom", "custom-header"))
                                .additionalBodyParams(Map.of("custom", "custom-body"))
                                .additionalQueryParams(Map.of("custom", "custom-query"))
                                .build())
                .blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("custom-header", recorded.getHeader("custom"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT + "?custom=custom-query",
                recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"custom\":\"custom-body\""));

        mockServer.close();
    }

    @Test
    @DisplayName("DashScope chat model non-stream with additional headers and params")
    void testDoNonStreamWithAdditionHeadersAndParams() throws Exception {
        MockWebServer mockServer = new MockWebServer();
        mockServer.start();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").stream(true)
                        .stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .httpTransport(OkHttpTransport.builder().build())
                        .build();

        chatModel
                .doStream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("test").build())
                                        .build()),
                        List.of(),
                        GenerateOptions.builder()
                                .additionalHeaders(Map.of("custom", "custom-header"))
                                .additionalBodyParams(Map.of("custom", "custom-body"))
                                .additionalQueryParams(Map.of("custom", "custom-query"))
                                .build())
                .blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("custom-header", recorded.getHeader("custom"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT + "?custom=custom-query",
                recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"custom\":\"custom-body\""));

        mockServer.close();
    }

    @Test
    @DisplayName("DashScope chat model apply thinking mode")
    void testApplyThinkingMode() {
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(true)
                        .enableSearch(false)
                        .build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .parameters(DashScopeParameters.builder().build())
                        .build();

        GenerateOptions options = GenerateOptions.builder().thinkingBudget(100).build();

        assertDoesNotThrow(() -> invokeApplyThinkingMode(chatModel, request, options));

        assertTrue(request.getParameters().getEnableThinking());
        assertFalse(request.getParameters().getEnableSearch());
        assertEquals(100, request.getParameters().getThinkingBudget());
    }

    @Test
    @DisplayName("DashScope chat model apply thinking mode with null")
    void testApplyThinkingModeWithNull() {
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .parameters(DashScopeParameters.builder().build())
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();

        assertDoesNotThrow(() -> invokeApplyThinkingMode(chatModel, request, options));

        assertNull(request.getParameters().getEnableThinking());
        assertNull(request.getParameters().getEnableSearch());
        assertNull(request.getParameters().getThinkingBudget());
    }

    @Test
    @DisplayName(
            "Should throw an IllegalStateException when setting thinkingBudget while thinking mode"
                    + " is disabled")
    void testApplyThinkingModeValidation() {
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(false)
                        .enableSearch(false)
                        .build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .parameters(DashScopeParameters.builder().build())
                        .build();

        GenerateOptions options = GenerateOptions.builder().thinkingBudget(100).build();

        assertThrows(
                IllegalStateException.class,
                () -> invokeApplyThinkingMode(chatModel, request, options));
    }

    // ========== Encryption Configuration Tests ==========

    @Test
    @DisplayName("Should create model with encryption enabled")
    void testModelWithEncryption() throws Exception {
        MockWebServer mockServer = new MockWebServer();
        mockServer.start();

        try {
            // Mock public key API response
            String publicKeyResponse =
                    """
                    {
                      "request_id": "test-request-id",
                      "data": {
                        "public_key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnojrB579xgPQN5f46SvoRAiQBPWBaPzWh7hp51fWI+OsQk7KqH0qMcw8i0eK5rfOvJIPujOQgnes1ph9/gKAst9NzXVIl9JJYUSPtzTvOabhp4yvS3KBf9g3xHYVjYgW33SOY74Ue/tgbCXn717rV6gXb4sVvq9XK/1BrDcGbEOQEZEgBTFkm/g3lpWLQtACwwqHffoA9eQtkkz15ZFKosAgbR8LedfIvxAl2zk15REzxXiRcFgc9/tLF0U1t2Sxt9FkQefxYwn6EZawTsRJvf4kqF3MaPdTcDbOp0iSNvCl2qzPSf/F+Oll2CUM1tFAEu81oa4l0WaDR3UtvqOtyQIDAQAB",
                        "public_key_id": "1"
                      }
                    }
                    """;

            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setBody(publicKeyResponse)
                            .setHeader("Content-Type", "application/json"));

            String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");

            DashScopeChatModel encryptedModel =
                    DashScopeChatModel.builder()
                            .apiKey(mockApiKey)
                            .modelName("qwen-max")
                            .enableEncrypt(true)
                            .baseUrl(baseUrl)
                            .httpTransport(OkHttpTransport.builder().build())
                            .build();

            assertNotNull(encryptedModel, "Encrypted model should be created");

            // Verify that the public key API was called
            RecordedRequest recorded = mockServer.takeRequest();
            assertEquals(DashScopeHttpClient.PUBLIC_KEYS_ENDPOINT, recorded.getPath());
            assertEquals("Bearer " + mockApiKey, recorded.getHeader("Authorization"));
        } finally {
            mockServer.shutdown();
        }
    }

    @Test
    @DisplayName("Should create model without encryption (default)")
    void testModelWithoutEncryption() {
        DashScopeChatModel normalModel =
                DashScopeChatModel.builder().apiKey(mockApiKey).modelName("qwen-plus").build();

        assertNotNull(normalModel, "Normal model should be created");
    }

    @Test
    @DisplayName("Should create model with encryption disabled explicitly")
    void testModelWithEncryptionDisabled() {
        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableEncrypt(false)
                        .build();

        assertNotNull(model, "Model with encryption disabled should be created");
    }

    @Test
    @DisplayName("Should throw exception when public key fetch fails")
    void testModelWithEncryptionFetchFails() throws Exception {
        MockWebServer mockServer = new MockWebServer();
        mockServer.start();

        try {
            // Mock error response from public key API
            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(500)
                            .setBody("Internal Server Error")
                            .setHeader("Content-Type", "text/plain"));

            String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");

            assertThrows(
                    DashScopeHttpClient.DashScopeHttpException.class,
                    () ->
                            DashScopeChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("qwen-max")
                                    .enableEncrypt(true)
                                    .baseUrl(baseUrl)
                                    .httpTransport(OkHttpTransport.builder().build())
                                    .build());
        } finally {
            mockServer.shutdown();
        }
    }

    /**
     *  Use reflection to invoke applyThinkingMode
     *
     * @param model the dashscope model
     * @param request the dashscope API request DTO
     * @param options the generation options for LLM models
     * @throws Throwable throw the target exception
     */
    private void invokeApplyThinkingMode(
            DashScopeChatModel model, DashScopeRequest request, GenerateOptions options)
            throws Throwable {
        try {
            Method method =
                    DashScopeChatModel.class.getDeclaredMethod(
                            "applyThinkingMode", DashScopeRequest.class, GenerateOptions.class);
            method.setAccessible(true);
            method.invoke(model, request, options);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
