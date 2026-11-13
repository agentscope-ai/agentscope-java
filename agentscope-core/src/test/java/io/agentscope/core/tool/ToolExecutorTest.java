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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ToolExecutor.
 *
 * <p>These tests verify execution paths that invoke the executor itself so regressions in
 * scheduling, ordering, timeout handling, and error propagation are detected.
 */
@Tag("unit")
@DisplayName("ToolExecutor Unit Tests")
class ToolExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());
    }

    @Test
    @DisplayName("Should execute multiple tool calls in parallel via Toolkit")
    void shouldExecuteToolsInParallel() {
        Map<String, Object> addInput = Map.of("a", 10, "b", 20);
        Map<String, Object> concatInput = Map.of("str1", "Hello", "str2", "World");

        ToolUseBlock addCall =
                ToolUseBlock.builder()
                        .id("call-add")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();
        ToolUseBlock concatCall =
                ToolUseBlock.builder()
                        .id("call-concat")
                        .name("concat")
                        .input(concatInput)
                        .content(JsonUtils.getJsonCodec().toJson(concatInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(addCall, concatCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return responses for tool calls");
        assertEquals(2, responses.size(), "All tool calls should be executed");

        Map<String, ToolResultBlock> responsesById =
                responses.stream()
                        .collect(Collectors.toMap(ToolResultBlock::getId, Function.identity()));

        ToolResultBlock addResponse = responsesById.get("call-add");
        ToolResultBlock concatResponse = responsesById.get("call-concat");

        assertNotNull(addResponse, "Add tool response should be present");
        assertEquals("30", extractFirstText(addResponse), "Add tool result mismatch");

        assertNotNull(concatResponse, "Concat tool response should be present");
        assertEquals(
                "\"HelloWorld\"", extractFirstText(concatResponse), "Concat tool result mismatch");
    }

    @Test
    @DisplayName("Should execute input-only tool call when raw content is absent")
    void shouldExecuteInputOnlyToolCallWithoutContent() {
        ToolUseBlock addCall =
                ToolUseBlock.builder()
                        .id("call-input-only")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(addCall).build())
                        .block(TIMEOUT);

        assertNotNull(response, "Executor should return a response for input-only calls");
        assertEquals(
                "30",
                extractFirstText(response),
                "Schema validation must validate the parsed input when raw content is absent");
    }

    @Test
    void shouldRejectInvalidExplicitInputDespiteValidToolBlock() {
        ValidatedInputTool tool = new ValidatedInputTool();
        toolkit.registerTool(tool);
        for (String content : new String[] {null, "{\"x\":1}"}) {
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id("conflict-invalid")
                            .name(tool.getName())
                            .input(Map.of("x", 1))
                            .content(content)
                            .build();
            ToolResultBlock result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(toolCall)
                                            .input(Map.of("x", -1))
                                            .build())
                            .block(TIMEOUT);
            assertNotNull(result);
            assertEquals("conflict-invalid", result.getId());
            assertEquals(tool.getName(), result.getName());
            assertTrue(extractFirstText(result).startsWith("Error: Parameter validation failed"));
        }
        assertEquals(0, tool.calls.get(), "Invalid selected input must never reach the tool");
    }

    @Test
    void shouldAcceptValidExplicitInputDespiteInvalidToolBlock() {
        ValidatedInputTool tool = new ValidatedInputTool();
        toolkit.registerTool(tool);
        for (String content : new String[] {null, "{\"x\":-1}"}) {
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .name(tool.getName())
                            .input(Map.of("x", -1))
                            .content(content)
                            .build();
            ToolResultBlock result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(toolCall)
                                            .input(Map.of("x", 1))
                                            .build())
                            .block(TIMEOUT);
            assertNotNull(result);
            assertEquals("x: 1", extractFirstText(result));
        }
        assertEquals(2, tool.calls.get());
    }

    @Test
    void shouldValidateSelectedToolBlockInputRatherThanConflictingRawContent() {
        ValidatedInputTool tool = new ValidatedInputTool();
        toolkit.registerTool(tool);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name(tool.getName())
                        .input(Map.of("x", -1))
                        .content("{\"x\":1}")
                        .build();
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);
        assertNotNull(result);
        assertTrue(extractFirstText(result).startsWith("Error: Parameter validation failed"));
        assertEquals(0, tool.calls.get());
    }

    @Test
    void shouldValidateVisiblePresetAndExcludeHiddenPresetFromClosedSchema() {
        ValidatedInputTool tool = new ValidatedInputTool();
        Object frameworkContext = new Object();
        Map<String, Object> presets = Map.of("x", 7, "context", frameworkContext);
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), presets))
                .apply();
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name(tool.getName())
                        .input(Map.of("x", -1, "context", "block-context"))
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(Map.of("x", -2, "context", "caller-context"))
                        .build();
        for (int i = 0; i < 2; i++) {
            ToolResultBlock result = toolkit.callTool(param).block(TIMEOUT);
            assertNotNull(result);
            assertEquals("x: 7", extractFirstText(result));
            assertSame(frameworkContext, tool.lastInput.get("context"));
        }
        assertEquals(2, tool.calls.get());
        assertEquals(-1, toolCall.getInput().get("x"));
        assertEquals(-2, param.getInput().get("x"));
        assertEquals(7, presets.get("x"));
        assertSame(frameworkContext, presets.get("context"));

        // Hidden presets are exempt, but unrelated unknown caller fields are not.
        ToolResultBlock unknown =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolCall)
                                        .input(Map.of("x", 1, "unknown", true))
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(unknown);
        assertTrue(extractFirstText(unknown).startsWith("Error: Parameter validation failed"));
        assertEquals(2, tool.calls.get());

        toolkit.updateToolPresetParameters(tool.getName(), Map.of("x", -3));
        ToolResultBlock invalidPreset = toolkit.callTool(param).block(TIMEOUT);
        assertNotNull(invalidPreset);
        assertTrue(
                extractFirstText(invalidPreset).startsWith("Error: Parameter validation failed"));
        assertEquals(2, tool.calls.get(), "Visible preset values must themselves pass validation");
    }

    @Test
    void shouldKeepMalformedRawFallbackAndExternalSuspension() {
        ValidatedInputTool tool = new ValidatedInputTool();
        toolkit.registerTool(tool);
        ToolUseBlock malformed =
                ToolUseBlock.builder()
                        .name(tool.getName())
                        .input(Map.of("x", 1))
                        .content("{\"x\":")
                        .build();
        ToolResultBlock local =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(malformed).build())
                        .block(TIMEOUT);
        assertNotNull(local);
        assertEquals("x: 1", extractFirstText(local));

        toolkit.registration()
                .agentTool(
                        new SchemaOnlyTool(
                                ToolSchema.builder()
                                        .name("external_validated")
                                        .description("External execution")
                                        .parameters(tool.getParameters())
                                        .build()))
                .presetParameters(Map.of("external_validated", Map.of("x", 7)))
                .apply();
        ToolUseBlock externalCall =
                ToolUseBlock.builder()
                        .id("external-malformed")
                        .name("external_validated")
                        .input(Map.of("x", 1))
                        .content("{\"x\":")
                        .build();
        ToolResultBlock suspended =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(externalCall)
                                        .input(Map.of("x", -1))
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(suspended);
        assertTrue(suspended.isSuspended());
        assertEquals(externalCall.getId(), suspended.getId());
        assertEquals(externalCall.getName(), suspended.getName());
        assertEquals("[Awaiting external execution]", extractFirstText(suspended));
        assertEquals(Map.of("x", 1), externalCall.getInput());
        assertEquals(1, tool.calls.get(), "External tools must not invoke a local tool");

        ToolUseBlock invalidExternal =
                ToolUseBlock.builder()
                        .name("external_validated")
                        .input(Map.of("x", -1))
                        .content("{\"x\":")
                        .build();
        ToolResultBlock rejected =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(invalidExternal).build())
                        .block(TIMEOUT);
        assertNotNull(rejected);
        assertFalse(rejected.isSuspended(), "Presets must not mask invalid external input");
        assertTrue(extractFirstText(rejected).startsWith("Error: Parameter validation failed"));
    }

    @ParameterizedTest
    @CsvSource({
        "ref,false", "ref,true", "allOf,false", "allOf,true",
        "anyOf,false", "anyOf,true", "oneOf,false", "oneOf,true",
        "anchor,false", "anchor,true"
    })
    void shouldRejectInvalidPresetExposedThroughRootSchema(String composition, boolean required) {
        ValidatedInputTool tool =
                new ValidatedInputTool(composedInputSchema(composition, required));
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), Map.of("x", -3)))
                .apply();
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("invalid-composed-preset")
                        .name(tool.getName())
                        .input(Map.of("x", 1))
                        .content("{\"x\":1}")
                        .build();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(use)
                                        .input(Map.of("x", 2))
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(result);
        assertEquals("invalid-composed-preset", result.getId());
        assertEquals(tool.getName(), result.getName());
        String expectedError = ToolValidator.validateInput("{\"x\":-3}", tool.getParameters());
        assertNotNull(expectedError, "The exposed preset violates the schema minimum");
        assertEquals(
                "Error: Parameter validation failed for tool 'validated_input': "
                        + expectedError
                        + "\nPlease correct the parameters and try again.",
                extractFirstText(result));
        assertEquals(0, tool.calls.get(), "Invalid authoritative presets must never execute");
    }

    @ParameterizedTest
    @CsvSource({
        "ref,false", "ref,true", "allOf,false", "allOf,true",
        "anyOf,false", "anyOf,true", "oneOf,false", "oneOf,true",
        "anchor,false", "anchor,true"
    })
    void shouldAcceptExposedPresetAndExcludeHiddenObjectThroughRootSchema(
            String composition, boolean required) {
        ValidatedInputTool tool =
                new ValidatedInputTool(composedInputSchema(composition, required));
        Object frameworkContext = new Object();
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(
                        Map.of(tool.getName(), Map.of("x", 7, "context", frameworkContext)))
                .apply();
        ToolUseBlock use =
                ToolUseBlock.builder().name(tool.getName()).input(Map.of("x", -1)).build();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(use)
                                        .input(Map.of("x", -2, "context", "caller-context"))
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(result);
        assertEquals("x: 7", extractFirstText(result));
        assertSame(frameworkContext, tool.lastInput.get("context"));
        assertEquals(1, tool.calls.get());
    }

    private static Map<String, Object> composedInputSchema(String composition, boolean required) {
        Map<String, Object> objectSchema = new HashMap<>();
        objectSchema.put("type", "object");
        objectSchema.put("properties", Map.of("x", Map.of("type", "integer", "minimum", 0)));
        objectSchema.put("additionalProperties", false);
        if (required) {
            objectSchema.put("required", List.of("x"));
        }
        if ("ref".equals(composition)) {
            return Map.of("$defs", Map.of("a/b~c", objectSchema), "$ref", "#/$defs/a~1b~0c");
        }
        if ("anchor".equals(composition)) {
            objectSchema.put("$anchor", "arguments");
            return Map.of("$defs", Map.of("input", objectSchema), "$ref", "#arguments");
        }
        return Map.of(
                composition,
                List.of(Map.of("$ref", "#/$defs/arguments")),
                "$defs",
                Map.of("arguments", objectSchema));
    }

    @Test
    void shouldBoundRootVisibilityTraversalAcrossCyclicReferences() throws Exception {
        Map<String, Object> schema =
                Map.of(
                        "$ref",
                        "#/$defs/loop",
                        "$defs",
                        Map.of(
                                "loop",
                                Map.of(
                                        "allOf",
                                        List.of(
                                                Map.of("$ref", "#/$defs/loop"),
                                                Map.of("$ref", "#"),
                                                Map.of(
                                                        "properties",
                                                        Map.of("x", Map.of("type", "integer")))))));
        assertEquals(Set.of("x"), visibleSchemaProperties(schema));
    }

    @ParameterizedTest
    @MethodSource("resourceScopedReferenceCases")
    void shouldValidateResourceScopedPresetsRegardlessOfReferenceMapIdentity(
            boolean shared, boolean required, int preset, boolean reversed, String execution)
            throws Exception {
        Map<String, Object> schema = resourceScopedInputSchema(shared, required, reversed);
        String before = JsonUtils.getJsonCodec().toJson(schema);
        assertEquals(
                before,
                JsonUtils.getJsonCodec()
                        .toJson(resourceScopedInputSchema(!shared, required, reversed)));
        assertNotNull(ToolValidator.validateInput("{\"x\":-3}", schema));
        assertNull(ToolValidator.validateInput("{\"x\":7}", schema));
        String expectedError = ToolValidator.validateInput("{\"x\":" + preset + "}", schema);
        toolkit =
                new Toolkit(ToolkitConfig.builder().parallel("parallel".equals(execution)).build());
        ValidatedInputTool tool = new ValidatedInputTool(schema);
        Object hiddenContext = new Object();
        Map<String, Object> presets = Map.of("x", preset, "context", hiddenContext);
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), presets))
                .apply();
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("resource-scoped-preset")
                        .name(tool.getName())
                        .input(Map.of("x", 1))
                        .content("{\"x\":1}")
                        .build();
        Map<String, Object> explicitInput = Map.of("x", 2);
        ToolResultBlock result;
        if ("direct".equals(execution)) {
            result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(use)
                                            .input(explicitInput)
                                            .build())
                            .block(TIMEOUT);
        } else {
            List<ToolResultBlock> results =
                    toolkit.callTools(List.of(use), null, null, null).block(TIMEOUT);
            assertNotNull(results);
            assertEquals(1, results.size());
            result = results.get(0);
        }
        assertNotNull(result);
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        if (preset < 0) {
            assertNotNull(expectedError);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertEquals(
                    "Error: Parameter validation failed for tool 'validated_input': "
                            + expectedError
                            + "\nPlease correct the parameters and try again.",
                    extractFirstText(result));
            assertEquals(0, tool.calls.get());
            assertNull(tool.lastInput);
        } else {
            assertNull(expectedError);
            assertEquals("x: 7", extractFirstText(result));
            assertEquals(7, tool.lastInput.get("x"));
            assertSame(hiddenContext, tool.lastInput.get("context"));
            assertEquals(1, tool.calls.get());
        }
        assertEquals(Set.of("a", "x"), visibleSchemaProperties(schema));
        assertEquals(before, JsonUtils.getJsonCodec().toJson(schema));
        assertEquals(Map.of("x", 1), use.getInput());
        assertEquals("{\"x\":1}", use.getContent());
        assertEquals(Map.of("x", 2), explicitInput);
        assertEquals(Map.of("x", preset, "context", hiddenContext), presets);
        List<?> resources = (List<?>) schema.get("allOf");
        Object firstRef = ((List<?>) ((Map<?, ?>) resources.get(0)).get("allOf")).get(0);
        Object secondRef = ((List<?>) ((Map<?, ?>) resources.get(1)).get("allOf")).get(0);
        assertEquals(shared, firstRef == secondRef);
    }

    private static List<Arguments> resourceScopedReferenceCases() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean shared : List.of(false, true)) {
            for (boolean required : List.of(false, true)) {
                for (int preset : List.of(-3, 7)) {
                    for (boolean reversed : List.of(false, true)) {
                        for (String execution : List.of("direct", "sequential", "parallel")) {
                            cases.add(Arguments.of(shared, required, preset, reversed, execution));
                        }
                    }
                }
            }
        }
        return cases;
    }

    private static Map<String, Object> resourceScopedInputSchema(
            boolean shared, boolean required, boolean reversed) {
        Map<String, Object> ref = Map.of("$ref", "#/$defs/input");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("properties", Map.of("x", Map.of("type", "integer", "minimum", 0)));
        if (required) {
            input.put("required", List.of("x"));
        }
        Map<String, Object> resourceA =
                Map.of(
                        "$id", "https://example.test/a",
                        "allOf", List.of(ref),
                        "$defs",
                                Map.of(
                                        "input",
                                        Map.of(
                                                "properties",
                                                Map.of("a", Map.of("type", "integer")))));
        Map<String, Object> resourceB =
                Map.of(
                        "$id", "https://example.test/b",
                        "allOf", List.of(shared ? ref : Map.of("$ref", "#/$defs/input")),
                        "$defs", Map.of("input", input));
        return Map.of(
                "type",
                "object",
                "allOf",
                reversed ? List.of(resourceB, resourceA) : List.of(resourceA, resourceB));
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void shouldBoundSharedReferenceTraversalAcrossCyclicResourceMaps(boolean reversed) {
        Map<String, Object> schema = resourceScopedInputSchema(true, false, reversed);
        List<?> resources = (List<?>) schema.get("allOf");
        Map<String, Object> first = new HashMap<>((Map<String, Object>) resources.get(0));
        Map<String, Object> second = new HashMap<>((Map<String, Object>) resources.get(1));
        Object ref = ((List<?>) first.get("allOf")).get(0);
        List<Object> firstBranches = new ArrayList<>(List.of(ref, second));
        firstBranches.add(first);
        firstBranches.add(firstBranches);
        first.put("allOf", firstBranches);
        second.put("allOf", List.of(ref, first, second));
        Map<String, Object> cyclic = Map.of("allOf", List.of(first, second));
        // Do not serialize or validate a cyclic Java graph: only the local visibility guard.
        assertTimeoutPreemptively(
                TIMEOUT, () -> assertEquals(Set.of("a", "x"), visibleSchemaProperties(cyclic)));
        assertSame(second, firstBranches.get(1));
        assertSame(first, firstBranches.get(2));
        assertSame(firstBranches, firstBranches.get(3));
        assertSame(ref, ((List<?>) second.get("allOf")).get(0));
        assertEquals(4, firstBranches.size());
    }

    @Test
    void shouldOnlyFollowLocalRootInstanceSchemaPaths() throws Exception {
        Map<String, Object> schema =
                Map.of(
                        "properties",
                                Map.of("outer", Map.of("properties", Map.of("nested", Map.of()))),
                        "$defs",
                                Map.of(
                                        "unused",
                                        Map.of("properties", Map.of("hidden", Map.of())),
                                        "space name",
                                        Map.of("properties", Map.of("escaped", Map.of())),
                                        "a/b~c name",
                                        Map.of("properties", Map.of("mixedPointer", Map.of()))),
                        "allOf",
                                List.of(
                                        Map.of("$ref", "https://example.invalid/schema"),
                                        Map.of("$ref", "#/$defs/missing"),
                                        Map.of("$ref", "#/$defs/space%20name"),
                                        Map.of("$ref", "#/$defs/a~1b~0c%20name"),
                                        Map.of("$ref", "#/anyOf/0")),
                        "anyOf", List.of(Map.of("properties", Map.of("arrayRef", Map.of()))));
        assertEquals(
                Set.of("outer", "escaped", "mixedPointer", "arrayRef"),
                visibleSchemaProperties(schema));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void shouldValidateNamedAnchorPresetsInBatches(boolean required, boolean parallel) {
        toolkit = new Toolkit(ToolkitConfig.builder().parallel(parallel).build());
        ValidatedInputTool tool = new ValidatedInputTool(composedInputSchema("anchor", required));
        int preset = required ? 7 : -3;
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), Map.of("x", preset)))
                .apply();
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("anchor-batch")
                        .name(tool.getName())
                        .input(Map.of("x", 1))
                        .content("{\"x\":1}")
                        .build();

        List<ToolResultBlock> results =
                toolkit.callTools(List.of(use), null, null, null).block(TIMEOUT);
        assertNotNull(results);
        assertEquals(1, results.size());
        ToolResultBlock result = results.get(0);
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        String expectedError =
                ToolValidator.validateInput("{\"x\":" + preset + "}", tool.getParameters());
        if (required) {
            assertNull(expectedError);
            assertEquals("x: 7", extractFirstText(result));
            assertEquals(7, tool.lastInput.get("x"));
            assertEquals(1, tool.calls.get());
        } else {
            assertNotNull(expectedError);
            assertEquals(
                    "Error: Parameter validation failed for tool 'validated_input': "
                            + expectedError
                            + "\nPlease correct the parameters and try again.",
                    extractFirstText(result));
            assertEquals(0, tool.calls.get());
        }
        assertEquals(1, use.getInput().get("x"));
    }

    @Test
    void shouldFindDecodedAnchorWithoutExposingNestedOrUnreferencedProperties() throws Exception {
        Map<String, Object> target =
                Map.of(
                        "$anchor",
                        "arguments",
                        "properties",
                        Map.of(
                                "x", Map.of("type", "integer"),
                                "outer", Map.of("properties", Map.of("context", Map.of()))));
        Map<String, Object> schema =
                Map.of(
                        "$ref",
                        "#%61rguments",
                        "$defs",
                        Map.of(
                                "unused", Map.of("properties", Map.of("hidden", Map.of())),
                                "wrapper", Map.of("allOf", List.of(target))));
        String before = JsonUtils.getJsonCodec().toJson(schema);
        assertEquals(Set.of("x", "outer"), visibleSchemaProperties(schema));
        assertEquals(before, JsonUtils.getJsonCodec().toJson(schema));
    }

    @Test
    void shouldBoundAnchorSearchAcrossMapAndListCyclesAndMissingAnchors() {
        Map<String, Object> schema = new HashMap<>();
        List<Object> branches = new ArrayList<>();
        schema.put("$ref", "#arguments");
        schema.put("$defs", Map.of("wrapper", Map.of("allOf", branches)));
        branches.add(schema);
        branches.add(branches);
        Map<String, Object> target =
                Map.of("$anchor", "arguments", "properties", Map.of("x", Map.of()));
        branches.add(target);
        assertTimeoutPreemptively(
                TIMEOUT, () -> assertEquals(Set.of("x"), visibleSchemaProperties(schema)));
        schema.put("$ref", "#missing");
        assertTimeoutPreemptively(
                TIMEOUT, () -> assertEquals(Set.of(), visibleSchemaProperties(schema)));
        assertSame(schema, branches.get(0));
        assertSame(branches, branches.get(1));
        assertSame(target, branches.get(2));
        assertEquals(3, branches.size());
    }

    @Test
    void shouldNotMatchAnAnchorInAnotherSchemaResource() throws Exception {
        Map<String, Object> schema =
                Map.of(
                        "$id", "https://example.invalid/root",
                        "$ref", "#arguments",
                        "$defs",
                                Map.of(
                                        "scoped",
                                        Map.of(
                                                "$id", "scoped",
                                                "$anchor", "arguments",
                                                "properties",
                                                        Map.of("x", Map.of("type", "integer")))));
        assertEquals(Set.of(), visibleSchemaProperties(schema));
        String error = ToolValidator.validateInput("{\"x\":7}", schema);
        assertNotNull(error);
        assertTrue(error.contains("Reference arguments cannot be resolved"), error);
    }

    @ParameterizedTest
    @CsvSource({"#missing", "#%61rguments"})
    void shouldPreserveExistingValidatorErrorsForUnresolvedAnchors(String ref) {
        Map<String, Object> schema = new HashMap<>(composedInputSchema("anchor", true));
        schema.put("$ref", ref);
        ValidatedInputTool tool = new ValidatedInputTool(schema);
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), Map.of("x", 7, "context", new Object())))
                .apply();
        String expectedError = ToolValidator.validateInput("{\"x\":7}", schema);
        assertNotNull(expectedError);
        assertTrue(expectedError.contains("cannot be resolved"), expectedError);
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .name(tool.getName())
                                                        .input(Map.of("x", 1))
                                                        .build())
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(result);
        assertEquals(
                "Error: Parameter validation failed for tool 'validated_input': "
                        + expectedError
                        + "\nPlease correct the parameters and try again.",
                extractFirstText(result));
        assertEquals(0, tool.calls.get());
    }

    @ParameterizedTest
    @CsvSource({"#/$defs/scoped", "#/$defs/scoped/allOf/0"})
    void shouldResolveNamedAnchorsWithinTheReferencedSchemaResource(String ref) throws Exception {
        Map<String, Object> scoped = new HashMap<>(composedInputSchema("anchor", true));
        scoped.put("$id", "scoped");
        scoped.put("allOf", List.of(Map.of("$ref", "#arguments")));
        Map<String, Object> schema =
                Map.of(
                        "$id",
                        "https://example.invalid/root",
                        "$ref",
                        ref,
                        "$defs",
                        Map.of(
                                "outer",
                                Map.of(
                                        "$anchor",
                                        "arguments",
                                        "properties",
                                        Map.of("context", Map.of())),
                                "scoped",
                                scoped));
        assertNull(ToolValidator.validateInput("{\"x\":7}", schema));
        assertNotNull(ToolValidator.validateInput("{\"x\":-3}", schema));
        assertEquals(Set.of("x"), visibleSchemaProperties(schema));
        ValidatedInputTool tool = new ValidatedInputTool(schema);
        Object context = new Object();
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of(tool.getName(), Map.of("x", 7, "context", context)))
                .apply();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .name(tool.getName())
                                                        .input(Map.of("x", -3))
                                                        .build())
                                        .build())
                        .block(TIMEOUT);
        assertNotNull(result);
        assertEquals("x: 7", extractFirstText(result));
        assertSame(context, tool.lastInput.get("context"));
        assertEquals(1, tool.calls.get());
    }

    @ParameterizedTest
    @CsvSource({
        "$defs",
        "definitions",
        "properties",
        "patternProperties",
        "dependentSchemas",
        "allOf",
        "anyOf",
        "oneOf",
        "prefixItems",
        "items",
        "contains",
        "additionalProperties",
        "unevaluatedProperties",
        "unevaluatedItems",
        "propertyNames",
        "not",
        "if",
        "then",
        "else"
    })
    void shouldSearchOnlySchemaBearingAnchorLocations(String location) throws Exception {
        Map<String, Object> target =
                Map.of(
                        "$anchor",
                        "arguments",
                        "properties",
                        Map.of("x", Map.of("type", "integer", "minimum", 0)));
        Object children;
        if (Set.of("$defs", "definitions", "properties", "patternProperties", "dependentSchemas")
                .contains(location)) {
            children = Map.of("inner", target);
        } else if (Set.of("allOf", "anyOf", "oneOf", "prefixItems").contains(location)) {
            children = List.of(target);
        } else {
            children = target;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$ref", "#arguments");
        schema.put("default", Map.of("$anchor", "arguments", "properties", Map.of()));
        schema.put("$defs", Map.of("wrapper", Map.of(location, children)));
        assertEquals(Set.of("x"), visibleSchemaProperties(schema));
    }

    @ParameterizedTest
    @CsvSource({"default", "examples", "enum", "const", "x-annotation"})
    void shouldNeverDiscoverAnchorsInsideAnnotationData(String annotation) throws Exception {
        Map<String, Object> decoy =
                Map.of("$anchor", "arguments", "properties", Map.of("context", Map.of()));
        Object value = Set.of("examples", "enum").contains(annotation) ? List.of(decoy) : decoy;
        Map<String, Object> schema = Map.of("$ref", "#arguments", annotation, value);
        assertEquals(Set.of(), visibleSchemaProperties(schema));
        assertNotNull(
                ToolValidator.validateInput("{}", schema),
                "networknt must not resolve an annotation anchor");
    }

    private static Object visibleSchemaProperties(Map<String, Object> schema) throws Exception {
        // Exercise the visibility guard only: cyclic-schema validation itself is outside this
        // repair.
        var method =
                ToolExecutor.class.getDeclaredMethod(
                        "getVisibleSchemaProperties", Map.class, Set.class);
        method.setAccessible(true);
        return method.invoke(null, schema, Set.of());
    }

    private static final class ValidatedInputTool extends ToolBase {
        private final AtomicInteger calls = new AtomicInteger();
        private Map<String, Object> lastInput;

        private ValidatedInputTool() {
            this(
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("x", Map.of("type", "integer", "minimum", 0)),
                            "required",
                            List.of("x"),
                            "additionalProperties",
                            false));
        }

        private ValidatedInputTool(Map<String, Object> schema) {
            super(
                    ToolBase.builder()
                            .name("validated_input")
                            .description("Test selected input validation")
                            .inputSchema(schema));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            lastInput = param.getInput();
            return Mono.just(ToolResultBlock.text("x: " + lastInput.get("x")));
        }
    }

    @Test
    @DisplayName("Should not execute malformed-argument calls through direct Toolkit paths")
    void shouldNotExecuteMalformedCallWithPartialInput() {
        // A truncated call carries merged input beside the malformed marker — the merged args
        // can even look complete; the executor must refuse the call like the agent loop
        // does, instead of executing it from that input.
        ToolUseBlock truncated =
                ToolUseBlock.builder()
                        .id("call-malformed")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .content("{}")
                        .metadata(Map.of(ToolUseBlock.METADATA_MALFORMED_ARGUMENTS, true))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(truncated).build())
                        .block(TIMEOUT);

        assertNotNull(response);
        assertEquals(
                ToolResultState.ERROR,
                response.getState(),
                "malformed calls must be refused, not executed from merged input: "
                        + extractFirstText(response));
        // The guard must be the rejecting layer here: "{}" content would also fail schema
        // validation (add's parameters are required), so the message pins which one fired.
        assertTrue(
                extractFirstText(response).contains("malformed"),
                "expected the malformed guard, got: " + extractFirstText(response));
    }

    @Test
    @DisplayName("Should not execute malformed calls with blank content through direct Toolkit")
    void shouldNotExecuteMalformedCallWithBlankContent() {
        // Marker with no usable content must resolve to "{}" (not fall back to the merged
        // input), keeping direct Toolkit paths aligned with the agent loop's refusal.
        ToolUseBlock truncated =
                ToolUseBlock.builder()
                        .id("call-malformed-blank")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .metadata(Map.of(ToolUseBlock.METADATA_MALFORMED_ARGUMENTS, true))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(truncated).build())
                        .block(TIMEOUT);

        assertNotNull(response);
        assertEquals(
                ToolResultState.ERROR,
                response.getState(),
                "malformed calls with blank content must not execute from merged input: "
                        + extractFirstText(response));
        assertTrue(
                extractFirstText(response).contains("malformed"),
                "expected the malformed guard, got: " + extractFirstText(response));
    }

    @Test
    @DisplayName("Should not execute malformed calls with valid content through direct Toolkit")
    void shouldNotExecuteMalformedCallWithValidContent() {
        // The merged input beside a malformed marker can coincidentally form a complete,
        // schema-valid object; execution must still be refused like the agent loop does,
        // instead of running a call whose raw arguments were truncated or corrupt.
        ToolUseBlock truncated =
                ToolUseBlock.builder()
                        .id("call-malformed-valid")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .content("{\"a\":10,\"b\":20}")
                        .metadata(Map.of(ToolUseBlock.METADATA_MALFORMED_ARGUMENTS, true))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(truncated).build())
                        .block(TIMEOUT);

        assertNotNull(response);
        assertEquals(
                ToolResultState.ERROR,
                response.getState(),
                "malformed calls must not execute even when the merged input looks complete:"
                        + " "
                        + extractFirstText(response));
    }

    @Test
    @DisplayName("Should wrap tool errors inside executor response")
    void shouldReturnErrorWhenToolThrows() {
        Map<String, Object> errorInput = Map.of("message", "test failure");
        ToolUseBlock errorCall =
                ToolUseBlock.builder()
                        .id("call-error")
                        .name("error_tool")
                        .input(errorInput)
                        .content(JsonUtils.getJsonCodec().toJson(errorInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(errorCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an error response");
        assertEquals(1, responses.size(), "Single failing call should yield one response");

        String content = extractFirstText(responses.get(0));
        assertEquals(
                "Error: Tool execution failed: Tool error: test failure",
                content,
                "Error message should be wrapped by executor");
    }

    @Test
    @DisplayName("Should convert empty tool publishers to error responses")
    void shouldReturnErrorWhenToolCompletesEmpty() {
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "empty_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that completes without a result";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.empty();
                    }
                });

        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock emptyCall =
                ToolUseBlock.builder()
                        .id("call-empty")
                        .name("empty_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(emptyCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an error response");
        assertEquals(1, responses.size(), "Empty completion should still yield one response");
        assertEquals("call-empty", responses.get(0).getId(), "Response should keep tool call id");
        assertEquals("empty_tool", responses.get(0).getName(), "Response should keep tool name");
        assertEquals(
                "Error: Tool execution failed: Tool completed without returning a result",
                extractFirstText(responses.get(0)));
    }

    @Test
    @DisplayName("Should return suspended result for external tools")
    void shouldReturnSuspendedResultForExternalTools() {
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Execute API outside the agent runtime")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string"))))
                        .build());

        Map<String, Object> input = Map.of("endpoint", "/users");
        ToolUseBlock externalCall =
                ToolUseBlock.builder()
                        .id("call-external")
                        .name("external_api")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(externalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return a suspended response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals("call-external", response.getId(), "Response should keep tool call id");
        assertEquals("external_api", response.getName(), "Response should keep tool name");
        assertTrue(response.isSuspended(), "External tool should surface as suspended");
        assertEquals("[Awaiting external execution]", extractFirstText(response));
    }

    @Test
    @DisplayName("Should validate external tool input before suspension")
    void shouldValidateExternalToolInputBeforeSuspension() {
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Execute API outside the agent runtime")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string")),
                                        "required",
                                        List.of("endpoint")))
                        .build());

        Map<String, Object> input = Map.of("endpoint", 42);
        ToolUseBlock invalidExternalCall =
                ToolUseBlock.builder()
                        .id("call-invalid-external")
                        .name("external_api")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(invalidExternalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return a validation response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals(
                "call-invalid-external", response.getId(), "Response should keep tool call id");
        assertEquals("external_api", response.getName(), "Response should keep tool name");
        assertTrue(!response.isSuspended(), "Invalid external input must not suspend");

        String errorText = extractFirstText(response);
        assertTrue(
                errorText.startsWith("Error: Parameter validation failed for tool 'external_api'"),
                "External tool should fail validation before suspension: " + errorText);
    }

    @Test
    @DisplayName("Should reject inactive grouped external tools before suspension")
    void shouldRejectInactiveGroupedExternalToolsBeforeSuspension() {
        toolkit.createToolGroup("inactiveExternal", "Inactive external tools", false);
        toolkit.registration()
                .agentTool(
                        new SchemaOnlyTool(
                                ToolSchema.builder()
                                        .name("external_inactive")
                                        .description("Inactive external API")
                                        .parameters(
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of(
                                                                "endpoint",
                                                                Map.of("type", "string"))))
                                        .build()))
                .group("inactiveExternal")
                .apply();

        Map<String, Object> input = Map.of("endpoint", "/users");
        ToolUseBlock externalCall =
                ToolUseBlock.builder()
                        .id("call-inactive-external")
                        .name("external_inactive")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(externalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an authorization response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals("call-inactive-external", response.getId(), "Response should keep call id");
        assertEquals("external_inactive", response.getName(), "Response should keep tool name");
        assertTrue(!response.isSuspended(), "Inactive external tool must not suspend");
        assertEquals(
                "Error: Unauthorized tool call: 'external_inactive' is not available",
                extractFirstText(response));
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in error path")
    void testToolErrorWithoutInterruptSpecialCase() {
        // Create a tool that throws RuntimeException with InterruptedException cause
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "interrupted_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that simulates interrupted error";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(
                                new RuntimeException(
                                        "Execution error",
                                        new InterruptedException("Thread interrupted")));
                    }
                });

        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock interruptedCall =
                ToolUseBlock.builder()
                        .id("call-interrupt")
                        .name("interrupted_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(interruptedCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return error response");
        assertEquals(1, responses.size(), "Should have one response");

        String errorText = extractFirstText(responses.get(0));
        // Should be standard error format, not special interrupted result
        assertTrue(
                errorText.startsWith("Error:"), "Should use standard error format: " + errorText);
        assertTrue(
                errorText.contains("Tool execution failed")
                        || errorText.contains("Execution error"),
                "Should contain error message");
    }

    @Test
    @DisplayName("Should handle concurrent tool execution with errors")
    void testConcurrentToolExecutionWithErrors() {
        // Register a tool that sometimes fails (thread-safe counter)
        AtomicInteger callCount = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "flaky_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that fails on first call";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        int count = callCount.getAndIncrement();
                        if (count == 0) {
                            return Mono.error(new RuntimeException("First call failed"));
                        }
                        return Mono.just(
                                ToolResultBlock.of(
                                        TextBlock.builder()
                                                .text("Call " + count + " succeeded")
                                                .build()));
                    }
                });

        // Execute multiple calls in parallel
        Map<String, Object> emptyInput = Map.of();
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("flaky_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("flaky_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock call3 =
                ToolUseBlock.builder()
                        .id("call-3")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(call1, call2, call3), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(3, responses.size(), "Should have three responses");

        // Count how many calls succeeded vs failed
        long errorCount =
                responses.stream().filter(r -> extractFirstText(r).startsWith("Error:")).count();
        long successCount =
                responses.stream()
                        .filter(
                                r ->
                                        extractFirstText(r).contains("succeeded")
                                                || extractFirstText(r).equals("3"))
                        .count();

        // Exactly one flaky_tool call should fail (the first one to execute)
        // and two should succeed (one flaky_tool + one add)
        assertEquals(1, errorCount, "Exactly one call should fail");
        assertEquals(2, successCount, "Exactly two calls should succeed");
    }

    @Test
    @DisplayName("Should apply preset parameters after explicit ToolCallParam input")
    void shouldApplyPresetParametersAfterExplicitInput() {
        class OverrideTool {
            @Tool(description = "Test preset precedence with explicit ToolCallParam input")
            public ToolResultBlock testOverride(
                    @ToolParam(name = "param1") String param1,
                    @ToolParam(name = "param2") String param2) {
                return ToolResultBlock.text(
                        String.format("param1: %s, param2: %s", param1, param2));
            }
        }

        toolkit.registration()
                .tool(new OverrideTool())
                .presetParameters(
                        Map.of(
                                "testOverride",
                                Map.of("param1", "preset_value1", "param2", "preset_value2")))
                .apply();

        Map<String, Object> explicitInput = Map.of("param1", "agent_value1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-override")
                        .name("testOverride")
                        .input(Map.of())
                        .content("{}")
                        .build();

        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolCall)
                                        .input(explicitInput)
                                        .build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        String resultText = extractFirstText(result);
        assertTrue(
                resultText.contains("param1: preset_value1"),
                "Preset value should override explicit ToolCallParam input");
        assertTrue(resultText.contains("param2: preset_value2"), "Preset value should be used");
    }

    @Test
    @DisplayName("Should use only preset parameters when both input sources are absent")
    void shouldUseOnlyPresetParametersWhenInputsAbsent() {
        class PresetOnlyTool {
            @Tool(description = "Test preset usage when no explicit inputs are present")
            public ToolResultBlock presetOnly(@ToolParam(name = "param1") String param1) {
                return ToolResultBlock.text("param1: " + param1);
            }
        }

        toolkit.registration()
                .tool(new PresetOnlyTool())
                .presetParameters(Map.of("presetOnly", Map.of("param1", "preset_value1")))
                .apply();

        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-preset-only")
                        .name("presetOnly")
                        .content("{}")
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        assertEquals("param1: preset_value1", extractFirstText(result));
    }

    @Test
    @DisplayName("Should execute without preset parameters when registration metadata is absent")
    void shouldExecuteWhenRegisteredMetadataIsAbsent() {
        AgentTool echoTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "metadata_gap_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool for simulating a metadata lookup gap";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(
                                ToolResultBlock.text("value: " + param.getInput().get("value")));
                    }
                };

        ToolRegistry registryWithMetadataGap =
                new ToolRegistry() {
                    @Override
                    RegisteredToolFunction getRegisteredTool(String name) {
                        return null;
                    }
                };
        registryWithMetadataGap.registerTool(
                echoTool.getName(), echoTool, new RegisteredToolFunction(echoTool, null, null));
        ToolExecutor executor =
                new ToolExecutor(
                        toolkit,
                        registryWithMetadataGap,
                        new ToolGroupManager(),
                        ToolkitConfig.defaultConfig());
        Map<String, Object> input = Map.of("value", "caller_value");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-metadata-gap")
                        .name(echoTool.getName())
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                executor.execute(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        assertEquals("value: caller_value", extractFirstText(result));
    }

    @Test
    @DisplayName("Should format all error messages consistently")
    void testErrorMessageFormat() {
        // Register various failing tools
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "null_pointer_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws NPE";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(new NullPointerException("Null value encountered"));
                    }
                });

        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "illegal_arg_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws IllegalArgumentException";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(
                                new IllegalArgumentException("Invalid argument provided"));
                    }
                });

        // Execute both tools
        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock npeCall =
                ToolUseBlock.builder()
                        .id("npe")
                        .name("null_pointer_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock argCall =
                ToolUseBlock.builder()
                        .id("arg")
                        .name("illegal_arg_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(npeCall, argCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(2, responses.size(), "Should have two responses");

        // Both should follow same error format
        for (int i = 0; i < responses.size(); i++) {
            String errorText = extractFirstText(responses.get(i));
            assertTrue(
                    errorText.startsWith("Error:"),
                    "Error " + i + " should start with 'Error:': " + errorText);
            assertTrue(
                    errorText.contains("Tool execution failed")
                            || errorText.contains("encountered")
                            || errorText.contains("provided"),
                    "Error " + i + " should contain meaningful message: " + errorText);
        }
    }

    private String extractFirstText(ToolResultBlock response) {
        assertTrue(
                ToolTestUtils.isValidToolResultBlock(response),
                "Tool response should contain content");
        List<ContentBlock> outputs = response.getOutput();
        if (outputs.isEmpty()) return "";
        return ((TextBlock) outputs.get(0)).getText();
    }
}
