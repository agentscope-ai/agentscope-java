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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolkitCallToolIdentityTest {

    @Test
    void stampsSuccessWithoutLosingOutputMetadataOrState() {
        Toolkit toolkit = new Toolkit();
        ToolResultBlock original =
                ToolResultBlock.of(
                                List.of(TextBlock.builder().text("done").build()),
                                Map.of("key", "value"))
                        .withState(ToolResultState.SUCCESS)
                        .withIdAndName("stale-id", "stale-name");
        toolkit.registerAgentTool(tool("test", Mono.just(original)));

        ToolResultBlock result = call(toolkit, "test", "{}");

        assertSame(original.getOutput(), result.getOutput());
        assertEquals(original.getMetadata(), result.getMetadata());
        assertEquals(original.getState(), result.getState());
    }

    @Test
    void stampsToolExecutionError() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool("test", Mono.error(new IllegalStateException("boom"))));

        ToolResultBlock result = call(toolkit, "test", "{}");

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(result.getOutput().get(0) instanceof TextBlock);
    }

    @Test
    void stampsMissingToolError() {
        assertEquals(ToolResultState.ERROR, call(new Toolkit(), "missing", "{}").getState());
    }

    @Test
    void stampsValidationFailure() {
        Toolkit toolkit = new Toolkit();
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(Map.of("type", "object", "required", List.of("required")));
        toolkit.registerAgentTool(tool);

        assertEquals(ToolResultState.ERROR, call(toolkit, "test", "{}").getState());
    }

    @Test
    void preservesCallerThreadAndDoesNotApplyToolkitTimeout() {
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .executionConfig(
                                        ExecutionConfig.builder()
                                                .timeout(Duration.ofMillis(1))
                                                .build())
                                .build());
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        toolkit.registerAgentTool(
                tool(
                        "test",
                        Mono.defer(
                                () -> {
                                    invocationThread.set(Thread.currentThread());
                                    return Mono.delay(Duration.ofSeconds(1))
                                            .thenReturn(
                                                    ToolResultBlock.text("done")
                                                            .withState(ToolResultState.SUCCESS));
                                })));
        Thread caller = Thread.currentThread();
        ToolUseBlock use = ToolUseBlock.builder().id("call-id").name("test").content("{}").build();

        StepVerifier.withVirtualTime(
                        () -> toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build()))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(
                        result -> {
                            assertEquals("call-id", result.getId());
                            assertEquals("test", result.getName());
                            assertEquals(ToolResultState.SUCCESS, result.getState());
                        })
                .verifyComplete();
        assertSame(caller, invocationThread.get());
    }

    @Test
    void stampsNonJsonExplicitInputAsReactiveValidationErrorWithoutFallback() {
        Toolkit toolkit = new Toolkit();
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("x", Map.of("type", "integer"))));
        toolkit.registerAgentTool(tool);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("non-json-direct")
                        .name("test")
                        .input(Map.of("x", 1))
                        .content("{\"x\":1}")
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(use).input(Map.of("x", new Object())).build();

        Mono<ToolResultBlock> publisher = assertDoesNotThrow(() -> toolkit.callTool(param));
        StepVerifier.create(publisher)
                .assertNext(result -> assertSerializationError(result, use))
                .verifyComplete();
        verify(tool, never()).callAsync(any(ToolCallParam.class));
        assertEquals(1, use.getInput().get("x"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stampsNonJsonBatchInputAsReactiveValidationErrorWithoutRawFallback(boolean parallel) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(parallel).build());
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("x", Map.of("type", "integer"))));
        toolkit.registerAgentTool(tool);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("non-json-batch")
                        .name("test")
                        .input(Map.of("x", new Object()))
                        .content("{\"x\":1}")
                        .build();

        Mono<List<ToolResultBlock>> publisher =
                assertDoesNotThrow(() -> toolkit.callTools(List.of(use), null, null, null));
        StepVerifier.create(publisher)
                .assertNext(
                        results -> {
                            assertEquals(1, results.size());
                            assertSerializationError(results.get(0), use);
                        })
                .verifyComplete();
        verify(tool, never()).callAsync(any(ToolCallParam.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stampsNamedAnchorPresetValidationAndPreservesAuthoritativeInput(boolean required) {
        Toolkit toolkit = new Toolkit();
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("done")));
        Map<String, Object> target = new HashMap<>();
        target.put("$anchor", "arguments");
        target.put("type", "object");
        target.put("properties", Map.of("x", Map.of("type", "integer", "minimum", 0)));
        target.put("additionalProperties", false);
        if (required) {
            target.put("required", List.of("x"));
        }
        Map<String, Object> schema = Map.of("$ref", "#arguments", "$defs", Map.of("input", target));
        when(tool.getParameters()).thenReturn(schema);
        Object context = new Object();
        int preset = required ? 7 : -3;
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of("test", Map.of("x", preset, "context", context)))
                .apply();
        AtomicReference<ToolCallParam> invocation = new AtomicReference<>();
        when(tool.callAsync(any(ToolCallParam.class)))
                .thenAnswer(
                        call -> {
                            invocation.set(call.getArgument(0));
                            return Mono.just(ToolResultBlock.text("done"));
                        });

        ToolResultBlock result = call(toolkit, "test", "{\"x\":1}");
        String expectedError = ToolValidator.validateInput("{\"x\":" + preset + "}", schema);
        if (required) {
            assertNull(expectedError);
            assertEquals("done", ((TextBlock) result.getOutput().get(0)).getText());
            assertNotNull(invocation.get());
            assertEquals(7, invocation.get().getInput().get("x"));
            assertSame(context, invocation.get().getInput().get("context"));
        } else {
            assertNotNull(expectedError);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertEquals(
                    "Error: Parameter validation failed for tool 'test': "
                            + expectedError
                            + "\nPlease correct the parameters and try again.",
                    ((TextBlock) result.getOutput().get(0)).getText());
            verify(tool, never()).callAsync(any(ToolCallParam.class));
        }
    }

    @ParameterizedTest(name = "{0}, {1}, valid={2}")
    @MethodSource("presetExposureCases")
    void validatesActualPresetsAcrossDirectAndBatchCalls(
            String variant, String mode, boolean valid) {
        String key =
                variant.equals("unanchoredPattern")
                        ? "prefix_x_suffix"
                        : variant.equals("unicodePattern") ? "é" : "x";
        String hiddenKey = variant.equals("newlinePattern") ? "x\n" : "context";
        int preset = valid ? 7 : -3;
        Map<String, Object> schema = presetExposureSchema(variant, key, valid, preset);
        Map<String, Object> selected = new HashMap<>();
        selected.put(key, preset);
        if (variant.startsWith("dependent")) {
            selected.put("flag", true);
        }
        if (variant.equals("const")) {
            selected.put("$anchor", "arguments");
            selected.put("properties", Map.of());
        }
        String expectedError =
                ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(selected), schema);
        if (valid) {
            assertNull(expectedError, "The real networknt validator must accept the control");
        } else {
            assertNotNull(expectedError, "The real networknt validator must reject the bad preset");
        }

        Toolkit toolkit =
                new Toolkit(ToolkitConfig.builder().parallel(mode.equals("parallel")).build());
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("done")));
        when(tool.getParameters()).thenReturn(schema);
        Object context = new Object();
        Map<String, Object> presets = new HashMap<>(Map.of(key, preset, hiddenKey, context));
        if (variant.startsWith("dependent")) {
            presets.put("flag", true);
        }
        toolkit.registration().agentTool(tool).presetParameters(Map.of("test", presets)).apply();
        AtomicReference<ToolCallParam> invocation = new AtomicReference<>();
        when(tool.callAsync(any(ToolCallParam.class)))
                .thenAnswer(
                        call -> {
                            invocation.set(call.getArgument(0));
                            return Mono.just(ToolResultBlock.text("done"));
                        });
        Map<String, Object> caller = new HashMap<>(selected);
        caller.put(key, 1);
        if (variant.startsWith("dependent")) {
            caller.put("flag", false);
        }
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("preset-" + variant + "-" + mode)
                        .name("test")
                        .input(caller)
                        .content(JsonUtils.getJsonCodec().toJson(caller))
                        .build();
        ToolResultBlock result;
        if (mode.equals("direct")) {
            Map<String, Object> explicit = new HashMap<>(caller);
            explicit.put(key, 2);
            result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(use)
                                            .input(explicit)
                                            .build())
                            .block(Duration.ofSeconds(5));
            assertEquals(2, explicit.get(key));
        } else {
            List<ToolResultBlock> results =
                    toolkit.callTools(List.of(use), null, null, null).block(Duration.ofSeconds(5));
            assertNotNull(results);
            assertEquals(1, results.size());
            result = results.get(0);
        }
        assertNotNull(result);
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        if (valid) {
            assertEquals("done", ((TextBlock) result.getOutput().get(0)).getText());
            assertNotNull(invocation.get());
            assertEquals(preset, invocation.get().getInput().get(key));
            assertSame(context, invocation.get().getInput().get(hiddenKey));
            if (variant.startsWith("dependent")) {
                assertEquals(true, invocation.get().getInput().get("flag"));
            }
        } else {
            assertEquals(ToolResultState.ERROR, result.getState());
            assertEquals(
                    "Error: Parameter validation failed for tool 'test': "
                            + expectedError
                            + "\nPlease correct the parameters and try again.",
                    ((TextBlock) result.getOutput().get(0)).getText());
            verify(tool, never()).callAsync(any(ToolCallParam.class));
        }
        assertEquals(1, caller.get(key));
        assertEquals(1, use.getInput().get(key));
    }

    private static Stream<Arguments> presetExposureCases() {
        return Stream.of(
                        "default",
                        "examples",
                        "enum",
                        "const",
                        "annotation",
                        "dynamicAnchor",
                        "anchorPriority",
                        "anchorDefinitions",
                        "anchorProperties",
                        "anchorPatterns",
                        "anchorItems",
                        "anchorAllOf",
                        "then",
                        "else",
                        "if",
                        "not",
                        "dependentSchemas",
                        "dependentRequired",
                        "pattern",
                        "unanchoredPattern",
                        "unicodePattern",
                        "newlinePattern")
                .flatMap(
                        variant ->
                                Stream.of("direct", "sequential", "parallel")
                                        .flatMap(
                                                mode ->
                                                        Stream.of(false, true)
                                                                .map(
                                                                        valid ->
                                                                                Arguments.of(
                                                                                        variant,
                                                                                        mode,
                                                                                        valid))));
    }

    private static Map<String, Object> presetExposureSchema(
            String variant, String key, boolean required, int preset) {
        Map<String, Object> target = new HashMap<>();
        target.put(
                "properties",
                Map.of(
                        key,
                        Map.of("type", "integer", "minimum", 0),
                        "outer",
                        Map.of(
                                "properties",
                                Map.of("context", Map.of()),
                                "required",
                                List.of("context")),
                        "array",
                        Map.of("type", "array", "items", Map.of("required", List.of("context")))));
        if (required) {
            target.put("required", List.of(key));
        }
        switch (variant) {
            case "then":
                return Map.of("if", true, "then", target);
            case "else":
                return Map.of("if", false, "else", target);
            case "if":
                return Map.of("if", target, "then", true, "else", false);
            case "not":
                return Map.of(
                        "not",
                        Map.of(
                                "required",
                                List.of(key),
                                "properties",
                                Map.of(key, Map.of("maximum", -1))));
            case "dependentSchemas":
                return Map.of("dependentSchemas", Map.of("flag", target));
            case "dependentRequired":
                return Map.of(
                        "dependentRequired",
                        Map.of("flag", List.of(key)),
                        "if",
                        true,
                        "then",
                        target);
            case "pattern":
            case "unanchoredPattern":
            case "unicodePattern":
            case "newlinePattern":
                String regex =
                        switch (variant) {
                            case "pattern" -> "^x$";
                            case "unicodePattern" -> "^\\p{Letter}$";
                            case "newlinePattern" -> "x$";
                            default -> "prefix_x";
                        };
                return Map.of(
                        "type",
                        "object",
                        "patternProperties",
                        Map.of(regex, Map.of("type", "integer", "minimum", 0)),
                        "additionalProperties",
                        false);
            default:
                break;
        }
        target.put(variant.equals("dynamicAnchor") ? "$dynamicAnchor" : "$anchor", "arguments");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$ref", "#arguments");
        Map<String, Object> decoy = Map.of("$anchor", "arguments", "properties", Map.of());
        if (variant.equals("examples")) {
            schema.put("examples", List.of(decoy));
        } else if (variant.equals("enum")) {
            schema.put("enum", List.of(decoy, Map.of(key, 7), Map.of(key, -3)));
        } else if (variant.equals("const")) {
            Map<String, Object> constant = new HashMap<>(decoy);
            constant.put(key, preset);
            schema.put("const", constant);
        } else if (!variant.equals("dynamicAnchor")) {
            schema.put(variant.equals("annotation") ? "x-annotation" : "default", decoy);
        }
        switch (variant) {
            case "anchorPriority":
                Map<String, Object> definitions = new LinkedHashMap<>();
                definitions.put(
                        "dynamic", Map.of("$dynamicAnchor", "arguments", "properties", Map.of()));
                definitions.put("input", target);
                schema.put("$defs", definitions);
                break;
            case "anchorDefinitions":
                schema.put("definitions", Map.of("input", target));
                break;
            case "anchorProperties":
                schema.put("properties", Map.of("unused", target));
                break;
            case "anchorPatterns":
                schema.put("patternProperties", Map.of("^unused$", target));
                break;
            case "anchorItems":
                schema.put("items", target);
                break;
            case "anchorAllOf":
                schema.put("allOf", List.of(target));
                break;
            default:
                schema.put("$defs", Map.of("input", target));
                break;
        }
        return schema;
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "sequential", "parallel"})
    void preservesPresetsDeclaredOnlyByRequired(String mode) {
        Toolkit toolkit =
                new Toolkit(ToolkitConfig.builder().parallel(mode.equals("parallel")).build());
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("done")));
        Map<String, Object> schema = Map.of("required", List.of("x"));
        when(tool.getParameters()).thenReturn(schema);
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of("test", Map.of("x", 7)))
                .apply();
        assertNull(ToolValidator.validateInput("{\"x\":7}", schema));
        assertNotNull(ToolValidator.validateInput("{}", schema));
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("required-only")
                        .name("test")
                        .input(Map.of("x", 1))
                        .build();
        ToolResultBlock result =
                mode.equals("direct")
                        ? toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build())
                                .block(Duration.ofSeconds(5))
                        : toolkit.callTools(List.of(use), null, null, null)
                                .block(Duration.ofSeconds(5))
                                .get(0);
        assertNotNull(result);
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        assertEquals("done", ((TextBlock) result.getOutput().get(0)).getText());
        verify(tool).callAsync(any(ToolCallParam.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "sequential", "parallel"})
    void stampsVisibleNonJsonPatternPresetSerializationError(String mode) {
        Toolkit toolkit =
                new Toolkit(ToolkitConfig.builder().parallel(mode.equals("parallel")).build());
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(Map.of("patternProperties", Map.of("x", Map.of("type", "integer"))));
        toolkit.registration()
                .agentTool(tool)
                .presetParameters(Map.of("test", Map.of("prefix_x", new Object())))
                .apply();
        ToolUseBlock use = ToolUseBlock.builder().id("pattern-non-json").name("test").build();
        ToolResultBlock result =
                mode.equals("direct")
                        ? toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build())
                                .block(Duration.ofSeconds(5))
                        : toolkit.callTools(List.of(use), null, null, null)
                                .block(Duration.ofSeconds(5))
                                .get(0);
        assertNotNull(result);
        assertSerializationError(result, use);
        verify(tool, never()).callAsync(any(ToolCallParam.class));
    }

    private static void assertSerializationError(ToolResultBlock result, ToolUseBlock use) {
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        assertEquals(ToolResultState.ERROR, result.getState());
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(text.startsWith("Error: Parameter validation failed for tool 'test':"), text);
        assertTrue(text.contains("serialize"), text);
    }

    private static AgentTool tool(String name, Mono<ToolResultBlock> result) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        when(tool.callAsync(org.mockito.ArgumentMatchers.any(ToolCallParam.class)))
                .thenReturn(result);
        return tool;
    }

    private static ToolResultBlock call(Toolkit toolkit, String name, String content) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-id").name(name).content(content).build();
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build())
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals("call-id", result.getId());
        assertEquals(name, result.getName());
        return result;
    }
}
