/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.agentscope.core.model.GenerateOptions;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for options application in OpenAIToolsHelper including new parameters. */
class OpenAIToolsHelperOptionsTest {

    private OpenAIToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new OpenAIToolsHelper();
    }

    @Test
    void testApplyOptionsWithSeed() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options = GenerateOptions.builder().seed(12345L).build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithAdditionalHeaders() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom-Header", "custom-value")
                        .additionalHeader("X-Request-Id", "req-123")
                        .build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithAdditionalBodyParams() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("custom_param", "value1")
                        .additionalBodyParam("nested_param", Map.of("key", "value"))
                        .build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithAdditionalQueryParams() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("api_version", "2024-01-01")
                        .additionalQueryParam("debug", "true")
                        .build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithAllNewParameters() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .seed(42L)
                        .additionalHeader("X-Api-Key", "secret")
                        .additionalBodyParam("stream", true)
                        .additionalQueryParam("version", "v1")
                        .build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsSeedFromDefaultOptions() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().seed(999L).build();

        assertDoesNotThrow(
                () ->
                        helper.applyOptions(
                                builder,
                                options,
                                defaultOptions,
                                getter -> {
                                    Object value = getter.apply(options);
                                    if (value == null && defaultOptions != null) {
                                        return getter.apply(defaultOptions);
                                    }
                                    return value;
                                }));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithTemperatureAndTopP() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).topP(0.9).maxTokens(1000).build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithFrequencyAndPresencePenalty() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options =
                GenerateOptions.builder().frequencyPenalty(0.5).presencePenalty(0.3).build();

        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithNullOptions() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.7).seed(123L).build();

        assertDoesNotThrow(
                () ->
                        helper.applyOptions(
                                builder,
                                null,
                                defaultOptions,
                                getter -> {
                                    Object value = getter.apply(null);
                                    if (value == null && defaultOptions != null) {
                                        return getter.apply(defaultOptions);
                                    }
                                    return value;
                                }));
        assertNotNull(builder);
    }

    @Test
    void testApplyOptionsWithEmptyAdditionalParams() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        // Should handle empty additional params gracefully
        assertDoesNotThrow(
                () -> helper.applyOptions(builder, options, null, getter -> getter.apply(options)));
        assertNotNull(builder);
    }
}
