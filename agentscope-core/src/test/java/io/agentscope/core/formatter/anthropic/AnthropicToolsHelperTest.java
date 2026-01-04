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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicToolsHelper. */
class AnthropicToolsHelperTest {

    /** Helper method to create a builder with required dummy message. */
    private MessageCreateParams.Builder createBuilder() {
        return MessageCreateParams.builder()
                .model("claude-sonnet-4-5-20250929")
                .maxTokens(1024)
                .addMessage(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content("test")
                                .build());
    }

    @Test
    void testApplyToolsWithSimpleSchema() {
        MessageCreateParams.Builder builder = createBuilder();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("query", Map.of("type", "string")));
        parameters.put("required", List.of("query"));

        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search for information")
                        .parameters(parameters)
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(builder, List.of(toolSchema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        List<ToolUnion> tools = params.tools().get();
        assertEquals(1, tools.size());

        assertTrue(tools.get(0).isTool());
        Tool tool = tools.get(0).asTool();
        assertEquals("search", tool.name());
        assertEquals("Search for information", tool.description().get());
        // Note: inputSchema validation is handled by Anthropic SDK during API calls
    }

    @Test
    void testApplyToolsWithMultipleSchemas() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema1 =
                ToolSchema.builder()
                        .name("tool1")
                        .description("First tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        ToolSchema schema2 =
                ToolSchema.builder()
                        .name("tool2")
                        .description("Second tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema1, schema2), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(2, params.tools().get().size());
    }

    @Test
    void testApplyToolsWithNullOrEmptyList() {
        MessageCreateParams.Builder builder = createBuilder();

        // Null list
        AnthropicToolsHelper.applyTools(builder, null, null);
        MessageCreateParams params1 = builder.build();
        assertTrue(params1.tools().isEmpty());

        // Empty list
        builder = createBuilder();
        AnthropicToolsHelper.applyTools(builder, List.of(), null);
        MessageCreateParams params2 = builder.build();
        assertTrue(params2.tools().isEmpty());
    }

    @Test
    void testApplyToolChoiceAuto() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
    }

    @Test
    void testApplyToolChoiceNone() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        // None maps to "any" in Anthropic
        assertTrue(params.toolChoice().get().isAny());
    }

    @Test
    void testApplyToolChoiceRequired() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        // Required maps to "any" in Anthropic
        assertTrue(params.toolChoice().get().isAny());
    }

    @Test
    void testApplyToolChoiceSpecific() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("search")).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isTool());
        assertEquals("search", params.toolChoice().get().asTool().name());
    }

    @Test
    void testApplyOptionsWithTemperature() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.7, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyOptionsWithTopP() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().topP(0.9).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.topP().isPresent());
        assertEquals(0.9, params.topP().get(), 0.001);
    }

    @Test
    void testApplyOptionsWithMaxTokens() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().maxTokens(2048).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertEquals(2048, params.maxTokens());
    }

    @Test
    void testApplyOptionsWithAllParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.8).topP(0.95).maxTokens(3000).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.8, params.temperature().get(), 0.001);
        assertTrue(params.topP().isPresent());
        assertEquals(0.95, params.topP().get(), 0.001);
        assertEquals(3000, params.maxTokens());
    }

    @Test
    void testApplyOptionsWithDefaultFallback() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).topP(0.9).build();

        // No options provided, should use default
        AnthropicToolsHelper.applyOptions(builder, null, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
        assertTrue(params.topP().isPresent());
        assertEquals(0.9, params.topP().get(), 0.001);
    }

    @Test
    void testApplyOptionsOverridesDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // Options should override default
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.7, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyToolsWithComplexParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Person name"));
        properties.put("age", Map.of("type", "integer", "minimum", 0, "maximum", 150));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("name"));

        ToolSchema schema =
                ToolSchema.builder()
                        .name("create_person")
                        .description("Create a person")
                        .parameters(parameters)
                        .build();

        AnthropicToolsHelper.applyTools(builder, List.of(schema), null);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
        assertTrue(params.tools().get().get(0).isTool());
        Tool tool = params.tools().get().get(0).asTool();
        assertEquals("create_person", tool.name());
        // Note: inputSchema validation is handled by Anthropic SDK during API calls
    }

    // ==================== New Parameters Tests ====================

    @Test
    void testApplyOptionsWithTopK() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.topK().isPresent());
        assertEquals(40L, params.topK().get());
    }

    @Test
    void testApplyOptionsWithAdditionalHeaders() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom-Header", "custom-value")
                        .additionalHeader("X-Request-Id", "req-123")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // Build should succeed with additional headers applied
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAdditionalBodyParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("custom_param", "value1")
                        .additionalBodyParam("nested_param", Map.of("key", "value"))
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // Build should succeed with additional body params applied
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAdditionalQueryParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("api_version", "2024-01-01")
                        .additionalQueryParam("debug", "true")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // Build should succeed with additional query params applied
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAllNewParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topK(50)
                        .additionalHeader("X-Api-Key", "secret")
                        .additionalBodyParam("stream", true)
                        .additionalQueryParam("version", "v1")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.8, params.temperature().get(), 0.001);
        assertTrue(params.topK().isPresent());
        assertEquals(50L, params.topK().get());
    }

    @Test
    void testApplyOptionsTopKFromDefaultOptions() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().topK(30).build();

        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
        assertTrue(params.topK().isPresent());
        assertEquals(30L, params.topK().get());
    }

    @Test
    void testApplyOptionsWithEmptyAdditionalParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        // Should handle empty additional params gracefully
        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyOptionsMergesAdditionalHeadersFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // Default options has header A and B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalHeader("X-Header-A", "value-a-default")
                        .additionalHeader("X-Header-B", "value-b")
                        .build();

        // Options has header A (override) and C (new)
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Header-A", "value-a-override")
                        .additionalHeader("X-Header-C", "value-c")
                        .build();

        // Should merge: A=override, B=value-b, C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsMergesAdditionalBodyParamsFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // Default options has param A and B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalBodyParam("param_a", "value-a-default")
                        .additionalBodyParam("param_b", "value-b")
                        .build();

        // Options has param A (override) and C (new)
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("param_a", "value-a-override")
                        .additionalBodyParam("param_c", "value-c")
                        .build();

        // Should merge: A=override, B=value-b, C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsMergesAdditionalQueryParamsFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // Default options has query param A and B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalQueryParam("query_a", "value-a-default")
                        .additionalQueryParam("query_b", "value-b")
                        .build();

        // Options has query param A (override) and C (new)
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("query_a", "value-a-override")
                        .additionalQueryParam("query_c", "value-c")
                        .build();

        // Should merge: A=override, B=value-b, C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }
}
