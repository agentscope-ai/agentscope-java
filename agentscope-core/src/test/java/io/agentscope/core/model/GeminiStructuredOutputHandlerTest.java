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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiStructuredOutputHandler Unit Tests")
class GeminiStructuredOutputHandlerTest {

    private final GeminiStructuredOutputHandler handler = new GeminiStructuredOutputHandler();

    @Test
    @DisplayName("Should wrap tool input and set structured output metadata")
    void testEnsureStructuredOutputMetadata() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(Map.of("location", "San Francisco", "temperature", "20"))
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse)).build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);

        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured =
                (Map<String, Object>)
                        result.getMetadata().get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        assertEquals("San Francisco", structured.get("location"));

        ToolUseBlock fixedToolUse = (ToolUseBlock) result.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) fixedToolUse.getInput().get("response");
        assertNotNull(wrapped);
        assertEquals("20", wrapped.get("temperature"));
    }

    @Test
    @DisplayName("Should convert JSON text response into generate_response tool call")
    void testFixStructuredOutputResponseFromJsonText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "{\"location\":\"San Francisco\","
                                                                + "\"temperature\":\"20\","
                                                                + "\"condition\":\"Cloudy\"}")
                                                .build()))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        assertEquals(1, fixed.getContent().size());
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME, toolUse.getName());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) toolUse.getInput().get("response");
        assertNotNull(payload);
        assertEquals("San Francisco", payload.get("location"));
        assertEquals("Cloudy", payload.get("condition"));
    }

    @Test
    @DisplayName("Should preserve missing fields instead of synthesizing defaults")
    void testFixStructuredOutputResponseDoesNotSynthesizeMissingFields() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("{\"location\":\"San Francisco\"}")
                                                .build()))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) toolUse.getInput().get("response");
        assertNotNull(payload);
        assertEquals("San Francisco", payload.get("location"));
        assertFalse(payload.containsKey("temperature"));
        assertFalse(payload.containsKey("condition"));
    }

    @Test
    @DisplayName("Should return original response when not structured output request")
    void testFixStructuredOutputResponseNotStructuredRequest() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("{\"x\":\"y\"}").build()))
                        .build();

        ChatResponse result = handler.fixStructuredOutputResponse(response, null, null);

        assertSame(response, result);
    }

    @Test
    @DisplayName("Should ignore specific non-structured tool choice")
    void testFixStructuredOutputResponseIgnoresSpecificNonStructuredToolChoice() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("{\"query\":\"weather\"}")
                                                .build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("search")).build();

        ChatResponse result =
                handler.fixStructuredOutputResponse(
                        response, options, List.of(simpleTool("search"), tool()));

        assertSame(response, result);
    }

    @Test
    @DisplayName("Should return null when response is null")
    void testFixStructuredOutputResponseNullResponse() {
        ChatResponse result =
                handler.fixStructuredOutputResponse(
                        null,
                        GenerateOptions.builder()
                                .toolChoice(
                                        new ToolChoice.Specific(
                                                StructuredOutputCapableAgent
                                                        .STRUCTURED_OUTPUT_TOOL_NAME))
                                .build(),
                        List.of(tool()));
        assertNull(result);
    }

    @Test
    @DisplayName("Should create synthetic empty structured output when content is null")
    void testFixStructuredOutputResponseWithNullContent() {
        ChatResponse response = ChatResponse.builder().content(null).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        assertNotNull(fixed.getContent());
        assertEquals(1, fixed.getContent().size());
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME, toolUse.getName());
        assertEquals(true, toolUse.getMetadata().get("synthetic"));
        assertEquals(true, toolUse.getMetadata().get("empty_response"));
    }

    @Test
    @DisplayName("Should keep response when allowed non-target tool is called first")
    void testFixStructuredOutputResponseWithAllowedOtherTool() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("search")
                        .input(Map.of("q", "weather"))
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse)).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();
        List<ToolSchema> tools = List.of(tool(), simpleTool("search"));

        ChatResponse fixed = handler.fixStructuredOutputResponse(response, options, tools);

        assertSame(response, fixed);
    }

    @Test
    @DisplayName("Should rewrite hallucinated tool name to generate_response")
    void testFixStructuredOutputResponseWithHallucinatedToolCall() {
        ToolUseBlock hallucinated =
                ToolUseBlock.builder()
                        .id("call_h")
                        .name("hallucinated_tool")
                        .input(Map.of("location", "Beijing"))
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(hallucinated)).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME, toolUse.getName());
        assertEquals(true, toolUse.getMetadata().get("synthetic"));
        assertEquals("hallucinated_tool", toolUse.getMetadata().get("hallucinated_tool"));
    }

    @Test
    @DisplayName("Should parse structured output from plain text when target tool input missing")
    void testFixStructuredOutputResponseFromPlainTextWhenInputMissing() {
        ToolUseBlock emptyToolUse =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(new HashMap<>())
                        .build();
        TextBlock text =
                TextBlock.builder()
                        .text("location: Tokyo\ntemperature: 23\ncondition: Sunny")
                        .build();

        ChatResponse response = ChatResponse.builder().content(List.of(emptyToolUse, text)).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) toolUse.getInput().get("response");
        assertEquals("Tokyo", wrapped.get("location"));
        assertEquals("Sunny", wrapped.get("condition"));
    }

    @Test
    @DisplayName("Should parse JSON from markdown fenced block")
    void testFixStructuredOutputResponseFromMarkdownJsonFence() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "```json\n{\"location\":\"Paris\","
                                                                + "\"temperature\":\"19\","
                                                                + "\"condition\":\"Cloudy\"}\n```")
                                                .build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) toolUse.getInput().get("response");
        assertEquals("Paris", wrapped.get("location"));
    }

    @Test
    @DisplayName("Should not mutate when text is not json and cannot be extracted")
    void testFixStructuredOutputResponseWithUnparseableText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("No structured fields here")
                                                .build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        assertSame(response, fixed);
    }

    @Test
    @DisplayName("Should keep response when metadata already has structured output")
    void testEnsureStructuredOutputMetadataAlreadyPresent() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("x").build()))
                        .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, Map.of("k", "v")))
                        .build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);

        assertSame(response, result);
    }

    @Test
    @DisplayName("Should return same response when no generate_response tool call exists")
    void testEnsureStructuredOutputMetadataNoToolUse() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_3")
                                                .name("search")
                                                .input(Map.of("q", "weather"))
                                                .build()))
                        .build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);
        assertSame(response, result);
    }

    @Test
    @DisplayName("Should parse typed values through private extractor")
    void testExtractValueForKeyTypedParsing() throws Exception {
        Method method =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "extractValueForKey", String.class, String.class, Object.class);
        method.setAccessible(true);

        Object intValue = method.invoke(handler, "age: 42 years", "age", Map.of("type", "integer"));
        Object numValue =
                method.invoke(handler, "score: 98.5 points", "score", Map.of("type", "number"));
        Object boolValue =
                method.invoke(handler, "active: true", "active", Map.of("type", "boolean"));
        Object arrayValue =
                method.invoke(
                        handler,
                        "tags: a, b, c",
                        "tags",
                        Map.of("type", "array", "items", Map.of("type", "string")));
        Object objectValue =
                method.invoke(
                        handler, "profile: {\"city\":\"LA\"}", "profile", Map.of("type", "object"));

        assertEquals(42, intValue);
        assertEquals(98.5, (Double) numValue, 0.0001);
        assertEquals(true, boolValue);
        assertEquals(List.of("a", "b", "c"), arrayValue);
        @SuppressWarnings("unchecked")
        Map<String, Object> objectMap = (Map<String, Object>) objectValue;
        assertEquals("LA", objectMap.get("city"));
    }

    @Test
    @DisplayName("Should expose private helpers via reflection for edge cases")
    void testPrivateHelpersEdgeCases() throws Exception {
        Method isRequired =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "isRequired", Map.class, String.class);
        isRequired.setAccessible(true);

        assertEquals(false, isRequired.invoke(null, null, "response"));
        assertEquals(
                true,
                isRequired.invoke(
                        null, Map.of("required", List.of("response", "location")), "response"));

        Method defaultValue =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "getDefaultValueForSchemaType", Object.class);
        defaultValue.setAccessible(true);

        assertEquals("", defaultValue.invoke(null, Map.of("type", "string")));
        assertEquals(0, defaultValue.invoke(null, Map.of("type", "integer")));
        assertEquals(false, defaultValue.invoke(null, Map.of("type", "boolean")));
        assertTrue(defaultValue.invoke(null, Map.of("type", "array")) instanceof List);
        assertTrue(defaultValue.invoke(null, Map.of("type", "object")) instanceof Map);
    }

    @Test
    @DisplayName("Should return null when extractValueForKey input is invalid")
    void testExtractValueForKeyInvalidInput() throws Exception {
        Method method =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "extractValueForKey", String.class, String.class, Object.class);
        method.setAccessible(true);

        assertNull(method.invoke(handler, null, "k", Map.of("type", "string")));
        assertNull(method.invoke(handler, "k: v", "", Map.of("type", "string")));
        assertNull(method.invoke(handler, "k: ", "k", Map.of("type", "string")));
    }

    @Test
    @DisplayName("Should throw when reflection parsing method is missing")
    void testReflectionContractStillExists() {
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        GeminiStructuredOutputHandler.class.getDeclaredMethod(
                                "extractValueForKey", String.class, String.class));
    }

    @Test
    @DisplayName("Should detect structured output tool from tools list when tool choice is absent")
    void testFixStructuredOutputResponseUsesToolListFallback() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("{\"location\":\"Lisbon\"}")
                                                .build()))
                        .build();

        ChatResponse fixed = handler.fixStructuredOutputResponse(response, null, List.of(tool()));

        assertEquals(1, fixed.getContent().size());
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME, toolUse.getName());
    }

    @Test
    @DisplayName("Should create synthetic response when content list is empty")
    void testFixStructuredOutputResponseWithEmptyContentList() {
        ChatResponse response = ChatResponse.builder().content(new ArrayList<>()).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        assertEquals(1, fixed.getContent().size());
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(true, toolUse.getMetadata().get("empty_response"));
    }

    @Test
    @DisplayName("Should fallback to default error response when schema is unavailable")
    void testFixStructuredOutputResponseWithoutToolsSchema() {
        ChatResponse response = ChatResponse.builder().content(new ArrayList<>()).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed = handler.fixStructuredOutputResponse(response, options, null);

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) toolUse.getInput().get("response");
        assertEquals("Model returned no content", wrapped.get("error"));
    }

    @Test
    @DisplayName("Should create defaults for flat structured output schema")
    void testFixStructuredOutputResponseWithFlatSchemaDefaults() {
        ChatResponse response = ChatResponse.builder().content(new ArrayList<>()).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(
                        response, options, List.of(flatStructuredTool()));

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals("", toolUse.getInput().get("location"));
        assertEquals(0, toolUse.getInput().get("count"));
        assertEquals(false, toolUse.getInput().get("ok"));
        assertTrue(toolUse.getInput().get("tags") instanceof List);
        assertTrue(toolUse.getInput().get("extra") instanceof Map);
    }

    @Test
    @DisplayName("Should return original response when text JSON is array")
    void testFixStructuredOutputResponseWithJsonArrayText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("[1,2,3]").build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        assertSame(response, fixed);
    }

    @Test
    @DisplayName("Should return original response on invalid JSON parse error")
    void testFixStructuredOutputResponseWithInvalidJsonText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("{invalid json}").build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        assertSame(response, fixed);
    }

    @Test
    @DisplayName("Should create empty structured output when no text block is present")
    void testFixStructuredOutputResponseWithoutTextBlock() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(ThinkingBlock.builder().thinking("hidden").build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(true, toolUse.getMetadata().get("empty_response"));
    }

    @Test
    @DisplayName("Should create empty structured output when text is blank")
    void testFixStructuredOutputResponseWithBlankText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("  ").build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(true, toolUse.getMetadata().get("empty_response"));
    }

    @Test
    @DisplayName("Should fill missing response wrapper when target tool already exists")
    void testFixStructuredOutputResponseMissingResponseWrapperOnToolUse() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_5")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(Map.of("location", "Madrid"))
                        .build();
        TextBlock text =
                TextBlock.builder()
                        .text("location: Madrid\ntemperature: 22\ncondition: Sunny")
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse, text)).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        ToolUseBlock fixedTool = (ToolUseBlock) fixed.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) fixedTool.getInput().get("response");
        assertEquals("Madrid", wrapped.get("location"));
        assertEquals("Sunny", wrapped.get("condition"));
    }

    @Test
    @DisplayName(
            "Should keep original response when target tool input missing and text not extractable")
    void testFixStructuredOutputResponseMissingInputUnextractable() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_6")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(new HashMap<>())
                        .build();
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        toolUse,
                                        TextBlock.builder().text("plain text only").build()))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));
        assertSame(response, fixed);
    }

    @Test
    @DisplayName("Should convert hallucinated tool with null input into empty structured response")
    void testFixStructuredOutputResponseHallucinatedToolWithoutInput() {
        ToolUseBlock hallucinated =
                ToolUseBlock.builder().id("call_h2").name("missing_tool").input(null).build();
        ChatResponse response = ChatResponse.builder().content(List.of(hallucinated)).build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(true, toolUse.getMetadata().get("synthetic"));
        assertEquals("missing_tool", toolUse.getMetadata().get("hallucinated_tool"));
        assertTrue(toolUse.getInput().containsKey("response"));
    }

    @Test
    @DisplayName("Should preserve wrapped input and set metadata without double wrapping")
    void testEnsureStructuredOutputMetadataWithWrappedInput() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_wrapped")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(Map.of("response", Map.of("city", "Shanghai")))
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse)).build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> structured =
                (Map<String, Object>)
                        result.getMetadata().get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        assertEquals("Shanghai", structured.get("city"));
        ToolUseBlock fixedToolUse = (ToolUseBlock) result.getContent().get(0);
        assertTrue(fixedToolUse.getInput().containsKey("response"));
    }

    @Test
    @DisplayName("Should return same response when generate_response input is empty")
    void testEnsureStructuredOutputMetadataWithEmptyToolInput() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_empty")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(new HashMap<>())
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse)).build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);

        assertSame(response, result);
    }

    @Test
    @DisplayName("Should return original response when metadata is already present")
    void testEnsureStructuredOutputMetadataNullCases() {
        assertNull(handler.ensureStructuredOutputMetadata(null));

        ChatResponse response = ChatResponse.builder().content(null).build();
        assertSame(response, handler.ensureStructuredOutputMetadata(response));
    }

    @Test
    @DisplayName("Should parse numeric and boolean arrays via private extractor")
    void testExtractValueForKeyArrayTypedParsing() throws Exception {
        Method method =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "extractValueForKey", String.class, String.class, Object.class);
        method.setAccessible(true);

        Object intArray =
                method.invoke(
                        handler,
                        "scores: [1, 2, 3]",
                        "scores",
                        Map.of("type", "array", "items", Map.of("type", "integer")));
        Object numArray =
                method.invoke(
                        handler,
                        "weights: 1.1; 2.5; 3",
                        "weights",
                        Map.of("type", "array", "items", Map.of("type", "number")));
        Object boolRaw =
                method.invoke(handler, "enabled: maybe", "enabled", Map.of("type", "boolean"));

        assertEquals(List.of(1, 2, 3), intArray);
        assertEquals(List.of(1.1, 2.5, 3.0), numArray);
        assertEquals("maybe", boolRaw);
    }

    @Test
    @DisplayName("Should return raw value when numeric parse fails and null for invalid object")
    void testExtractValueForKeyFallbacks() throws Exception {
        Method method =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "extractValueForKey", String.class, String.class, Object.class);
        method.setAccessible(true);

        Object intRaw = method.invoke(handler, "age: unknown", "age", Map.of("type", "integer"));
        Object objectRaw =
                method.invoke(handler, "profile: not-json", "profile", Map.of("type", "object"));

        assertEquals("unknown", intRaw);
        assertEquals("not-json", objectRaw);
    }

    @Test
    @DisplayName("Should expose default value helper fallback type")
    void testGetDefaultValueFallbackType() throws Exception {
        Method defaultValue =
                GeminiStructuredOutputHandler.class.getDeclaredMethod(
                        "getDefaultValueForSchemaType", Object.class);
        defaultValue.setAccessible(true);

        assertEquals("", defaultValue.invoke(null, Map.of("type", "unsupported")));
        assertEquals("", defaultValue.invoke(null, "not-a-map"));
    }

    private static ToolSchema tool() {
        Map<String, Object> responseSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "location", Map.of("type", "string"),
                                "temperature", Map.of("type", "string"),
                                "condition", Map.of("type", "string")));

        Map<String, Object> parameters =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("response", responseSchema),
                        "required",
                        List.of("response"));

        return ToolSchema.builder()
                .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                .description("Structured output tool")
                .parameters(parameters)
                .build();
    }

    private static ToolSchema simpleTool(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("Simple tool")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }

    private static ToolSchema flatStructuredTool() {
        Map<String, Object> parameters =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "location",
                                Map.of("type", "string"),
                                "count",
                                Map.of("type", "integer"),
                                "ok",
                                Map.of("type", "boolean"),
                                "tags",
                                Map.of("type", "array"),
                                "extra",
                                Map.of("type", "object")));

        return ToolSchema.builder()
                .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                .description("Flat structured output tool")
                .parameters(parameters)
                .build();
    }
}
