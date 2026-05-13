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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GenerateOptions and its Builder.
 */
@DisplayName("GenerateOptions Tests")
class GenerateOptionsTest {

    @Test
    @DisplayName("Should build GenerateOptions with all parameters using builder")
    void testBuilderAllParameters() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topP(0.95)
                        .maxTokens(4096)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.4)
                        .build();

        assertNotNull(options);
        assertEquals(0.8, options.getTemperature());
        assertEquals(0.95, options.getTopP());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(0.3, options.getFrequencyPenalty());
        assertEquals(0.4, options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should build GenerateOptions with partial parameters")
    void testBuilderPartialParameters() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.5).maxTokens(1024).build();

        assertNotNull(options);
        assertEquals(0.5, options.getTemperature());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTopP());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should build GenerateOptions with no parameters")
    void testBuilderNoParameters() {
        GenerateOptions options = GenerateOptions.builder().build();

        assertNotNull(options);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getMaxTokens());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderChaining() {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        GenerateOptions options =
                builder.temperature(0.7)
                        .topP(0.9)
                        .maxTokens(2048)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.3)
                        .build();

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
    }

    @Test
    @DisplayName("Should handle edge case values")
    void testEdgeCaseValues() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.0)
                        .topP(1.0)
                        .maxTokens(1)
                        .frequencyPenalty(-2.0)
                        .presencePenalty(2.0)
                        .build();

        assertEquals(0.0, options.getTemperature());
        assertEquals(1.0, options.getTopP());
        assertEquals(1, options.getMaxTokens());
        assertEquals(-2.0, options.getFrequencyPenalty());
        assertEquals(2.0, options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should handle null values in builder")
    void testBuilderNullValues() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(null)
                        .topP(null)
                        .maxTokens(null)
                        .frequencyPenalty(null)
                        .presencePenalty(null)
                        .build();

        assertNotNull(options);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getMaxTokens());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should build GenerateOptions with execution config")
    void testBuilderWithExecutionConfig() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(5)
                        .initialBackoff(Duration.ofSeconds(2))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).executionConfig(executionConfig).build();

        assertNotNull(options);
        assertNotNull(options.getExecutionConfig());
        assertEquals(5, options.getExecutionConfig().getMaxAttempts());
        assertEquals(Duration.ofSeconds(2), options.getExecutionConfig().getInitialBackoff());
    }

    @Test
    @DisplayName("Should build GenerateOptions with execution config including timeout")
    void testBuilderWithExecutionConfigTimeout() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMinutes(2)).build();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).executionConfig(executionConfig).build();

        assertNotNull(options);
        assertNotNull(options.getExecutionConfig());
        assertEquals(Duration.ofMinutes(2), options.getExecutionConfig().getTimeout());
    }

    @Test
    @DisplayName("Should build GenerateOptions with full execution config")
    void testBuilderWithFullExecutionConfig() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder().maxAttempts(3).timeout(Duration.ofSeconds(90)).build();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.8).executionConfig(executionConfig).build();

        assertNotNull(options);
        assertNotNull(options.getExecutionConfig());
        assertEquals(3, options.getExecutionConfig().getMaxAttempts());
        assertEquals(Duration.ofSeconds(90), options.getExecutionConfig().getTimeout());
    }

    @Test
    @DisplayName("Should default execution config to null")
    void testDefaultExecutionConfigIsNull() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        assertNotNull(options);
        assertNull(options.getExecutionConfig());
    }

    @Test
    @DisplayName("Should allow null execution config explicitly")
    void testExplicitNullExecutionConfig() {
        GenerateOptions options = GenerateOptions.builder().executionConfig(null).build();

        assertNotNull(options);
        assertNull(options.getExecutionConfig());
    }

    @Test
    @DisplayName("Should support realistic production configuration with execution config")
    void testRealisticProductionConfig() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .timeout(Duration.ofMinutes(2))
                        .retryOn(error -> error instanceof ModelException)
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(4096)
                        .executionConfig(executionConfig)
                        .build();

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(3, options.getExecutionConfig().getMaxAttempts());
        assertEquals(Duration.ofMinutes(2), options.getExecutionConfig().getTimeout());
    }

    @Test
    @DisplayName("Should build GenerateOptions with topK parameter")
    void testBuilderWithTopK() {
        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        assertNotNull(options);
        assertEquals(40, options.getTopK());
    }

    @Test
    @DisplayName("Should build GenerateOptions with seed parameter")
    void testBuilderWithSeed() {
        GenerateOptions options = GenerateOptions.builder().seed(12345L).build();

        assertNotNull(options);
        assertEquals(12345L, options.getSeed());
    }

    @Test
    @DisplayName("Should build GenerateOptions with additional headers")
    void testBuilderWithAdditionalHeaders() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom-Header", "custom-value")
                        .additionalHeader("X-Request-Id", "req-123")
                        .build();

        assertNotNull(options);
        Map<String, String> headers = options.getAdditionalHeaders();
        assertNotNull(headers);
        assertEquals(2, headers.size());
        assertEquals("custom-value", headers.get("X-Custom-Header"));
        assertEquals("req-123", headers.get("X-Request-Id"));
    }

    @Test
    @DisplayName("Should build GenerateOptions with additional body params")
    void testBuilderWithAdditionalBodyParams() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("custom_param", "value1")
                        .additionalBodyParam("nested_param", Map.of("key", "value"))
                        .build();

        assertNotNull(options);
        Map<String, Object> bodyParams = options.getAdditionalBodyParams();
        assertNotNull(bodyParams);
        assertEquals(2, bodyParams.size());
        assertEquals("value1", bodyParams.get("custom_param"));
    }

    @Test
    @DisplayName("Should build GenerateOptions with additional query params")
    void testBuilderWithAdditionalQueryParams() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("api_version", "2024-01-01")
                        .additionalQueryParam("debug", "true")
                        .build();

        assertNotNull(options);
        Map<String, String> queryParams = options.getAdditionalQueryParams();
        assertNotNull(queryParams);
        assertEquals(2, queryParams.size());
        assertEquals("2024-01-01", queryParams.get("api_version"));
        assertEquals("true", queryParams.get("debug"));
    }

    @Test
    @DisplayName("Should build GenerateOptions with all new parameters")
    void testBuilderWithAllNewParameters() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .topK(50)
                        .seed(42L)
                        .additionalHeader("X-Api-Key", "secret")
                        .additionalBodyParam("stream", true)
                        .additionalQueryParam("version", "v1")
                        .build();

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
        assertEquals(50, options.getTopK());
        assertEquals(42L, options.getSeed());
        assertEquals("secret", options.getAdditionalHeaders().get("X-Api-Key"));
        assertEquals(true, options.getAdditionalBodyParams().get("stream"));
        assertEquals("v1", options.getAdditionalQueryParams().get("version"));
    }

    @Test
    @DisplayName("Should return empty map for additional params when not set")
    void testEmptyAdditionalParams() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        assertNotNull(options);
        assertTrue(options.getAdditionalHeaders().isEmpty());
        assertTrue(options.getAdditionalBodyParams().isEmpty());
        assertTrue(options.getAdditionalQueryParams().isEmpty());
    }

    @Test
    @DisplayName("Should merge options with additional params correctly")
    void testMergeOptionsWithAdditionalParams() {
        GenerateOptions primary =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .additionalHeader("X-Primary", "primary-value")
                        .additionalBodyParam("primary_param", "primary")
                        .build();

        GenerateOptions fallback =
                GenerateOptions.builder()
                        .topK(40)
                        .additionalHeader("X-Fallback", "fallback-value")
                        .additionalHeader("X-Primary", "should-be-overridden")
                        .additionalBodyParam("fallback_param", "fallback")
                        .build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertNotNull(merged);
        assertEquals(0.8, merged.getTemperature());
        assertEquals(40, merged.getTopK());
        // Primary should override fallback for same key
        assertEquals("primary-value", merged.getAdditionalHeaders().get("X-Primary"));
        assertEquals("fallback-value", merged.getAdditionalHeaders().get("X-Fallback"));
        assertEquals("primary", merged.getAdditionalBodyParams().get("primary_param"));
        assertEquals("fallback", merged.getAdditionalBodyParams().get("fallback_param"));
    }

    @Test
    @DisplayName("Should set additional headers using map")
    void testSetAdditionalHeadersMap() {
        Map<String, String> headers = Map.of("Header1", "Value1", "Header2", "Value2");
        GenerateOptions options = GenerateOptions.builder().additionalHeaders(headers).build();

        assertNotNull(options);
        assertEquals(2, options.getAdditionalHeaders().size());
        assertEquals("Value1", options.getAdditionalHeaders().get("Header1"));
    }

    @Test
    @DisplayName("Should set additional body params using map")
    void testSetAdditionalBodyParamsMap() {
        Map<String, Object> params = Map.of("param1", "value1", "param2", 123);
        GenerateOptions options = GenerateOptions.builder().additionalBodyParams(params).build();

        assertNotNull(options);
        assertEquals(2, options.getAdditionalBodyParams().size());
        assertEquals("value1", options.getAdditionalBodyParams().get("param1"));
        assertEquals(123, options.getAdditionalBodyParams().get("param2"));
    }

    @Test
    @DisplayName("Should set additional query params using map")
    void testSetAdditionalQueryParamsMap() {
        Map<String, String> params = Map.of("q1", "v1", "q2", "v2");
        GenerateOptions options = GenerateOptions.builder().additionalQueryParams(params).build();

        assertNotNull(options);
        assertEquals(2, options.getAdditionalQueryParams().size());
        assertEquals("v1", options.getAdditionalQueryParams().get("q1"));
    }

    @Test
    @DisplayName("Should build GenerateOptions with endpoint path")
    void testBuilderWithEndpointPath() {
        GenerateOptions options =
                GenerateOptions.builder().endpointPath("/v4/chat/completions").build();

        assertNotNull(options);
        assertEquals("/v4/chat/completions", options.getEndpointPath());
    }

    @Test
    @DisplayName("Should build GenerateOptions with connection-level configuration")
    void testBuilderWithConnectionLevelConfig() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .apiKey("test-api-key")
                        .baseUrl("https://custom.api.com")
                        .endpointPath("/custom/path")
                        .modelName("custom-model")
                        .stream(true)
                        .build();

        assertNotNull(options);
        assertEquals("test-api-key", options.getApiKey());
        assertEquals("https://custom.api.com", options.getBaseUrl());
        assertEquals("/custom/path", options.getEndpointPath());
        assertEquals("custom-model", options.getModelName());
        assertEquals(Boolean.TRUE, options.getStream());
    }

    @Test
    @DisplayName("Should merge endpoint path correctly")
    void testMergeOptionsWithEndpointPath() {
        GenerateOptions primary =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .endpointPath("/v4/chat/completions")
                        .build();

        GenerateOptions fallback =
                GenerateOptions.builder()
                        .temperature(0.5)
                        .endpointPath("/v1/chat/completions")
                        .build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertNotNull(merged);
        assertEquals(0.8, merged.getTemperature());
        assertEquals("/v4/chat/completions", merged.getEndpointPath());
    }

    @Test
    @DisplayName("Should use fallback endpoint path when primary is null")
    void testMergeOptionsWithNullPrimaryEndpointPath() {
        GenerateOptions primary = GenerateOptions.builder().temperature(0.8).build();

        GenerateOptions fallback =
                GenerateOptions.builder().endpointPath("/v1/chat/completions").build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertNotNull(merged);
        assertEquals(0.8, merged.getTemperature());
        assertEquals("/v1/chat/completions", merged.getEndpointPath());
    }

    @Test
    @DisplayName("Should have null endpoint path when not set")
    void testNullEndpointPathWhenNotSet() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        assertNotNull(options);
        assertNull(options.getEndpointPath());
    }

    @Test
    @DisplayName("Should handle null endpoint path explicitly")
    void testExplicitNullEndpointPath() {
        GenerateOptions options = GenerateOptions.builder().endpointPath(null).build();

        assertNotNull(options);
        assertNull(options.getEndpointPath());
    }
}
