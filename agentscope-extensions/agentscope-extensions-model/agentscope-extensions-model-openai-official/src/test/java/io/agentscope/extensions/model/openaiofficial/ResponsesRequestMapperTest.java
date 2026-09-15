/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseInputItem;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ResponsesRequestMapper}, grouped by request-mapping sections.
 */
class ResponsesRequestMapperTest {

    private static final String MODEL = "gpt-4o";

    private static GenerateOptions baseOptions() {
        return GenerateOptions.builder().modelName(MODEL).stream(false).build();
    }

    private static TextBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    private static ResponseCreateParams mapWith(
            GenerateOptions options, List<ToolSchema> tools, Boolean strictTools) {
        return ResponsesRequestMapper.map(
                List.of(SystemMessage.builder().content(text("system")).build()),
                tools,
                options,
                strictTools,
                null,
                ResponsesRequestMapper::mapHistory);
    }

    private static ResponseCreateParams mapHistory(
            GenerateOptions options, List<? extends Msg> messages) {
        @SuppressWarnings("unchecked")
        List<Msg> msgs = (List<Msg>) messages;
        return ResponsesRequestMapper.map(
                msgs, null, options, null, null, ResponsesRequestMapper::mapHistory);
    }

    // ── Options mapping ──────────────────────────────────────────

    @Nested
    class OptionsMapping {

        @Test
        void temperature() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .temperature(0.7)
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals(0.7, params.temperature().orElseThrow());
        }

        @Test
        void topP() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false).topP(0.9).build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals(0.9, params.topP().orElseThrow());
        }

        @Test
        void maxOutputTokensFromMaxTokens() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .maxTokens(4096)
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals(4096L, params.maxOutputTokens().orElseThrow());
        }

        @Test
        void maxOutputTokensPriorityMaxCompletionTokens() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .maxTokens(1000)
                            .maxCompletionTokens(2000)
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals(2000L, params.maxOutputTokens().orElseThrow());
        }

        @Test
        void parallelToolCalls() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .parallelToolCalls(false)
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertFalse(params.parallelToolCalls().orElseThrow());
        }

        @Test
        void reasoningEffort() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .reasoningEffort("high")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().effort().isPresent());
            assertEquals("high", params.reasoning().get().effort().get().asString());
        }

        @Test
        void reasoningSummaryOptIn() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.summary", "auto")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().summary().isPresent());
            assertEquals("auto", params.reasoning().get().summary().get().asString());
        }

        @Test
        void noReasoningWhenAllNull() {
            ResponseCreateParams params = mapWith(baseOptions(), null, null);
            assertFalse(params.reasoning().isPresent());
        }

        @Test
        void storeAlwaysFalse() {
            ResponseCreateParams params = mapWith(baseOptions(), null, null);
            assertFalse(params.store().orElseThrow());
        }

        @Test
        void modelNameSet() {
            ResponseCreateParams params = mapWith(baseOptions(), null, null);
            assertNotNull(params.model());
        }

        @Test
        void responseFormatJsonObject() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonObject())
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.text().isPresent());
        }

        @Test
        void responseFormatJsonSchema() {
            JsonSchema schema =
                    JsonSchema.builder().name("Result").schema(Map.of("type", "object")).build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            null,
                            opts,
                            null,
                            true,
                            ResponsesRequestMapper::mapHistory);
            assertTrue(params.text().isPresent());
        }

        @Test
        void toolChoiceAuto() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .toolChoice(new ToolChoice.Auto())
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.toolChoice().isPresent());
        }

        @Test
        void toolChoiceSpecific() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .toolChoice(new ToolChoice.Specific("my_tool"))
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.toolChoice().isPresent());
        }

        @Test
        void reasoningContext() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.context", "current_turn")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().context().isPresent());
            assertEquals("current_turn", params.reasoning().get().context().get().asString());
        }

        @Test
        void reasoningMode() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.mode", "standard")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().mode().isPresent());
            assertEquals("standard", params.reasoning().get().mode().get().asString());
        }

        @Test
        void responseFormatText() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.text())
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertFalse(params.text().isPresent());
        }

        @Test
        void responseFormatUnknownTypeFailsFast() {
            ResponseFormat format = ResponseFormat.builder().type("xml").build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(format)
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void jsonSchemaNullNameFailsFast() {
            JsonSchema schema = JsonSchema.builder().schema(Map.of("type", "object")).build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            assertThrows(
                    OpenAIOfficialModelException.class,
                    () ->
                            ResponsesRequestMapper.map(
                                    List.of(SystemMessage.builder().content(text("s")).build()),
                                    null,
                                    opts,
                                    null,
                                    null,
                                    ResponsesRequestMapper::mapHistory));
        }

        @Test
        void toolChoiceNone() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .toolChoice(new ToolChoice.None())
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.toolChoice().isPresent());
        }

        @Test
        void toolChoiceRequired() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .toolChoice(new ToolChoice.Required())
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.toolChoice().isPresent());
        }
    }

    // ── AdditionalBodyParams whitelist ──────────────────────────

    @Nested
    class AdditionalBodyParams {

        @Test
        void maxToolCalls() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("max_tool_calls", "5")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals(5L, params.maxToolCalls().orElseThrow());
        }

        @Test
        void serviceTier() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("service_tier", "flex")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.serviceTier().isPresent());
        }

        @Test
        void promptCacheKey() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("prompt_cache_key", "key1")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals("key1", params.promptCacheKey().orElseThrow());
        }

        @Test
        void safetyIdentifier() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("safety_identifier", "sid-123")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals("sid-123", params.safetyIdentifier().orElseThrow());
        }

        @Test
        void nonWhitelistKeyFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("unknown_param", "value")
                            .build();
            OpenAIOfficialModelException ex =
                    assertThrows(
                            OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
            assertTrue(ex.getMessage().contains("unknown_param"));
        }

        @Test
        void promptCacheOptions() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam(
                                    "prompt_cache_options",
                                    Map.of("mode", "explicit", "ttl", "30m"))
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.promptCacheOptions().isPresent());
        }

        @Test
        void maxToolCallsInvalidValueFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("max_tool_calls", "abc")
                            .build();
            OpenAIOfficialModelException ex =
                    assertThrows(
                            OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
            assertTrue(ex.getMessage().contains("max_tool_calls"));
        }

        @Test
        void promptCacheOptionsNonMapFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("prompt_cache_options", "not-a-map")
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }
    }

    // ── Rejected fields ──────────────────────────────────────────

    @Nested
    class RejectedFields {

        @Test
        void frequencyPenalty() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .frequencyPenalty(0.5)
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void presencePenalty() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .presencePenalty(0.3)
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void topK() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false).topK(40).build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void seed() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false).seed(42L).build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void cacheControl() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .cacheControl(true)
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void thinkingBudget() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .thinkingBudget(1000)
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void additionalHeadersFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalHeader("X-Custom", "val")
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void endpointPathFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .endpointPath("/custom/path")
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void additionalQueryParamsFailsFast() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalQueryParam("param", "val")
                            .build();
            assertThrows(OpenAIOfficialModelException.class, () -> mapWith(opts, null, null));
        }
    }

    // ── History mapping ──────────────────────────────────────────

    @Nested
    class HistoryMapping {

        @Test
        void systemTextMappedAsEasyInputMessage() {
            List<Msg> messages =
                    List.of(SystemMessage.builder().content(text("You are helpful.")).build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void userTextOnlyMappedAsStringContent() {
            List<Msg> messages = List.of(UserMessage.builder().content(text("Hello!")).build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void userMultimodalMappedAsContentList() {
            List<Msg> messages =
                    List.of(
                            UserMessage.builder()
                                    .content(text("What is this?"))
                                    .content(
                                            ImageBlock.builder()
                                                    .source(
                                                            new URLSource(
                                                                    "https://example.com/img.png"))
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void assistantTextMappedAsAssistantMessage() {
            List<Msg> messages =
                    List.of(AssistantMessage.builder().content(text("I can help.")).build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void assistantToolUseMappedAsFunctionCall() {
            List<Msg> messages =
                    List.of(
                            AssistantMessage.builder()
                                    .content(
                                            ToolUseBlock.builder()
                                                    .id("call_1")
                                                    .name("search")
                                                    .input(Map.of("q", "test"))
                                                    .content("{\"q\":\"test\"}")
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void toolResultMappedAsFunctionCallOutput() {
            List<Msg> messages =
                    List.of(
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("search")
                                                    .output(text("result"))
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void toolResultWithTextOnlyUsesStringForm() {
            List<Msg> messages =
                    List.of(
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("search")
                                                    .output(text("result"))
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
            ResponseInputItem.FunctionCallOutput fco = input.get(0).asFunctionCallOutput();
            assertTrue(fco.output().isString());
        }

        @Test
        void toolResultWithImageOutputMappedAsList() {
            List<Msg> messages =
                    List.of(
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("screenshot")
                                                    .output(
                                                            ImageBlock.builder()
                                                                    .source(
                                                                            new URLSource(
                                                                                    "https://example.com/shot.png"))
                                                                    .build())
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
            ResponseInputItem.FunctionCallOutput fco = input.get(0).asFunctionCallOutput();
            assertTrue(fco.output().isResponseFunctionCallOutputItemList());
            List<ResponseFunctionCallOutputItem> outputItems =
                    fco.output().asResponseFunctionCallOutputItemList();
            assertEquals(1, outputItems.size());
            assertTrue(outputItems.get(0).isInputImage());
        }

        @Test
        void toolResultWithMixedTextAndImagePreservesOrder() {
            List<Msg> messages =
                    List.of(
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("describe")
                                                    .output(
                                                            List.<ContentBlock>of(
                                                                    text("before image"),
                                                                    ImageBlock.builder()
                                                                            .source(
                                                                                    new URLSource(
                                                                                            "https://example.com/img.png"))
                                                                            .build(),
                                                                    text("after image")))
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
            ResponseInputItem.FunctionCallOutput fco = input.get(0).asFunctionCallOutput();
            assertTrue(fco.output().isResponseFunctionCallOutputItemList());
            List<ResponseFunctionCallOutputItem> outputItems =
                    fco.output().asResponseFunctionCallOutputItemList();
            assertEquals(3, outputItems.size());
            assertTrue(outputItems.get(0).isInputText());
            assertTrue(outputItems.get(1).isInputImage());
            assertTrue(outputItems.get(2).isInputText());
        }

        @Test
        void toolResultWithDataBlockImageMappedAsList() {
            List<Msg> messages =
                    List.of(
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("capture")
                                                    .output(
                                                            DataBlock.builder()
                                                                    .source(
                                                                            new URLSource(
                                                                                    "https://example.com/data.png"))
                                                                    .build())
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
            ResponseInputItem.FunctionCallOutput fco = input.get(0).asFunctionCallOutput();
            assertTrue(fco.output().isResponseFunctionCallOutputItemList());
            List<ResponseFunctionCallOutputItem> outputItems =
                    fco.output().asResponseFunctionCallOutputItemList();
            assertEquals(1, outputItems.size());
            assertTrue(outputItems.get(0).isInputImage());
        }

        @Test
        void thinkingBlockWithoutEncryptedContentFailsFast() {
            List<Msg> messages =
                    List.of(
                            AssistantMessage.builder()
                                    .content(
                                            ThinkingBlock.builder()
                                                    .thinking("some reasoning")
                                                    .build())
                                    .build());
            assertThrows(
                    OpenAIOfficialModelException.class, () -> mapHistory(baseOptions(), messages));
        }

        @Test
        void reasoningReplayWithEncryptedContent() {
            List<Msg> messages =
                    List.of(
                            AssistantMessage.builder()
                                    .content(
                                            ThinkingBlock.builder()
                                                    .thinking("some reasoning")
                                                    .metadata(
                                                            Map.of(
                                                                    OpenAIOfficialConstants
                                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                                    "encrypted123"))
                                                    .build())
                                    .build());
            ResponseCreateParams params = mapHistory(baseOptions(), messages);
            assertNotNull(params.input());
        }

        @Test
        void assistantMessageWithUnsupportedBlockFailsFast() {
            List<Msg> messages =
                    List.of(
                            AssistantMessage.builder()
                                    .content(
                                            ImageBlock.builder()
                                                    .source(
                                                            new URLSource(
                                                                    "https://example.com/img.png"))
                                                    .build())
                                    .build());
            assertThrows(
                    OpenAIOfficialModelException.class, () -> mapHistory(baseOptions(), messages));
        }

        @Test
        void emptyToolMessageFailsFast() {
            List<Msg> messages = List.of(ToolResultMessage.builder().build());
            assertThrows(
                    OpenAIOfficialModelException.class, () -> mapHistory(baseOptions(), messages));
        }
    }

    // ── Tool definition mapping ─────────────────────────────────

    @Nested
    class ToolsMapping {

        @Test
        void basicToolDefinition() {
            ToolSchema schema =
                    ToolSchema.builder()
                            .name("get_weather")
                            .description("Get weather")
                            .parameters(Map.of("type", "object", "properties", Map.of()))
                            .build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            assertTrue(params.tools().isPresent());
            assertEquals(1, params.tools().get().size());
            FunctionTool tool = params.tools().get().get(0).asFunction();
            assertEquals("get_weather", tool.name());
            assertTrue(tool.description().isPresent());
            assertEquals("Get weather", tool.description().orElseThrow());
        }

        @Test
        void strictFromToolSchema() {
            ToolSchema schema =
                    ToolSchema.builder().name("tool1").description("d").strict(true).build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            assertTrue(params.tools().isPresent());
            FunctionTool tool = params.tools().get().get(0).asFunction();
            assertTrue(tool.strict().isPresent());
            assertEquals(true, tool.strict().orElseThrow());
        }

        @Test
        void strictFallbackToBuilder() {
            ToolSchema schema = ToolSchema.builder().name("tool1").description("d").build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), true);
            assertTrue(params.tools().isPresent());
            FunctionTool tool = params.tools().get().get(0).asFunction();
            assertTrue(tool.strict().isPresent());
            assertEquals(true, tool.strict().orElseThrow());
        }

        @Test
        void emptyToolsNotSet() {
            ResponseCreateParams params = mapWith(baseOptions(), List.of(), null);
            assertFalse(params.tools().isPresent());
        }

        @Test
        void strictWithEmptyParamsSynthesizesMinimalSchema() {
            ToolSchema schema =
                    ToolSchema.builder().name("tool1").description("d").strict(true).build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            assertTrue(params.tools().isPresent());
        }

        @Test
        void outputSchemaMapped() {
            ToolSchema schema =
                    ToolSchema.builder()
                            .name("tool1")
                            .description("d")
                            .outputSchema(Map.of("type", "object"))
                            .build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            assertTrue(params.tools().isPresent());
        }

        @Test
        void strictDefaultsToFalseWhenBothNull() {
            ToolSchema schema = ToolSchema.builder().name("tool1").description("d").build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            assertTrue(params.tools().isPresent());
            FunctionTool tool = params.tools().get().get(0).asFunction();
            assertTrue(tool.strict().isPresent());
            assertEquals(false, tool.strict().orElseThrow());
        }

        @Test
        void emptyParamsNonStrictOmitsAdditionalProperties() {
            ToolSchema schema =
                    ToolSchema.builder().name("tool1").description("d").strict(false).build();
            ResponseCreateParams params = mapWith(baseOptions(), List.of(schema), null);
            FunctionTool tool = params.tools().get().get(0).asFunction();
            // Non-strict empty params should not impose additionalProperties:false
            // (only strict=true synthesizes the minimal strict schema)
            Map<String, JsonValue> paramMap =
                    tool.parameters().orElseThrow()._additionalProperties();
            assertFalse(paramMap.containsKey("additionalProperties"));
        }
    }

    // ── Reasoning validation ──────────────────────────────────────

    @Nested
    class ReasoningValidation {

        @Test
        void reasoningEffortInvalidValueThrows() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .reasoningEffort("extreme")
                            .build();
            assertThrows(RuntimeException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void reasoningContextAllTurns() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.context", "all_turns")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().context().isPresent());
            assertEquals("all_turns", params.reasoning().get().context().get().asString());
        }

        @Test
        void reasoningContextInvalidValueThrows() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.context", "every_turn")
                            .build();
            assertThrows(RuntimeException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void reasoningModePro() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.mode", "pro")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().mode().isPresent());
            assertEquals("pro", params.reasoning().get().mode().get().asString());
        }

        @Test
        void reasoningModeInvalidValueThrows() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.mode", "ultra")
                            .build();
            assertThrows(RuntimeException.class, () -> mapWith(opts, null, null));
        }

        @Test
        void reasoningSummaryOptInRequestSide() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("reasoning.summary", "auto")
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.reasoning().isPresent());
            assertTrue(params.reasoning().get().summary().isPresent());
            assertEquals("auto", params.reasoning().get().summary().get().asString());
        }

        @Test
        void reasoningSummaryNoOptInRequestSide() {
            ResponseCreateParams params = mapWith(baseOptions(), null, null);
            assertFalse(params.reasoning().isPresent());
        }

        @Test
        void enableThinkingNotMappedByRequestMapper() {
            ResponseCreateParams params = mapWith(baseOptions(), null, null);
            assertFalse(params.reasoning().isPresent());
        }
    }

    // ── Structured output ─────────────────────────────────────────

    @Nested
    class StructuredOutputTests {

        @Test
        void responseFormatJsonSchemaStrictTrue() {
            JsonSchema schema =
                    JsonSchema.builder().name("Result").schema(Map.of("type", "object")).build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            null,
                            opts,
                            null,
                            true,
                            ResponsesRequestMapper::mapHistory);
            assertTrue(params.text().isPresent());
            assertTrue(params.text().orElseThrow().format().orElseThrow().isJsonSchema());
            assertEquals(
                    true,
                    params.text()
                            .orElseThrow()
                            .format()
                            .orElseThrow()
                            .asJsonSchema()
                            .strict()
                            .orElseThrow());
        }

        @Test
        void responseFormatJsonSchemaStrictNullNotSet() {
            JsonSchema schema =
                    JsonSchema.builder().name("Result").schema(Map.of("type", "object")).build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            null,
                            opts,
                            null,
                            null,
                            ResponsesRequestMapper::mapHistory);
            assertTrue(params.text().isPresent());
            assertTrue(params.text().orElseThrow().format().orElseThrow().isJsonSchema());
            assertFalse(
                    params.text()
                            .orElseThrow()
                            .format()
                            .orElseThrow()
                            .asJsonSchema()
                            .strict()
                            .isPresent());
        }

        @Test
        void strictJsonSchemaOrthogonalToToolStrict() {
            ToolSchema toolSchema =
                    ToolSchema.builder().name("tool1").description("d").strict(false).build();
            JsonSchema jsonSchema =
                    JsonSchema.builder().name("Result").schema(Map.of("type", "object")).build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(jsonSchema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            List.of(toolSchema),
                            opts,
                            null,
                            true,
                            ResponsesRequestMapper::mapHistory);
            FunctionTool tool = params.tools().orElseThrow().get(0).asFunction();
            assertEquals(false, tool.strict().orElseThrow());
            assertTrue(
                    params.text()
                            .orElseThrow()
                            .format()
                            .orElseThrow()
                            .asJsonSchema()
                            .strict()
                            .isPresent());
        }

        @Test
        void responseFormatJsonSchemaSchemaLevelStrictTrue() {
            JsonSchema schema =
                    JsonSchema.builder()
                            .name("Result")
                            .schema(Map.of("type", "object"))
                            .strict(true)
                            .build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            null,
                            opts,
                            null,
                            null,
                            ResponsesRequestMapper::mapHistory);
            assertTrue(params.text().isPresent());
            assertTrue(params.text().orElseThrow().format().orElseThrow().isJsonSchema());
            assertEquals(
                    true,
                    params.text()
                            .orElseThrow()
                            .format()
                            .orElseThrow()
                            .asJsonSchema()
                            .strict()
                            .orElseThrow());
        }

        @Test
        void responseFormatJsonSchemaSchemaLevelOverridesBuilder() {
            JsonSchema schema =
                    JsonSchema.builder()
                            .name("Result")
                            .schema(Map.of("type", "object"))
                            .strict(false)
                            .build();
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .responseFormat(ResponseFormat.jsonSchema(schema))
                            .build();
            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            List.of(SystemMessage.builder().content(text("s")).build()),
                            null,
                            opts,
                            null,
                            true,
                            ResponsesRequestMapper::mapHistory);
            assertTrue(params.text().isPresent());
            assertTrue(params.text().orElseThrow().format().orElseThrow().isJsonSchema());
            assertEquals(
                    false,
                    params.text()
                            .orElseThrow()
                            .format()
                            .orElseThrow()
                            .asJsonSchema()
                            .strict()
                            .orElseThrow());
        }
    }

    // ── Prompt cache options detail ───────────────────────────────

    @Nested
    class PromptCacheOptionsDetail {

        @Test
        void promptCacheOptionsWithModeAndTtl() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam(
                                    "prompt_cache_options",
                                    Map.of("mode", "explicit", "ttl", "30m"))
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.promptCacheOptions().isPresent());
            assertEquals(
                    "explicit",
                    params.promptCacheOptions().orElseThrow().mode().orElseThrow().asString());
            assertEquals(
                    "30m",
                    params.promptCacheOptions().orElseThrow().ttl().orElseThrow().asString());
        }

        @Test
        void promptCacheOptionsCoexistsWithPromptCacheKey() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("prompt_cache_key", "key1")
                            .additionalBodyParam("prompt_cache_options", Map.of("mode", "implicit"))
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertEquals("key1", params.promptCacheKey().orElseThrow());
            assertTrue(params.promptCacheOptions().isPresent());
            assertEquals(
                    "implicit",
                    params.promptCacheOptions().orElseThrow().mode().orElseThrow().asString());
        }

        @Test
        void promptCacheOptionsWithOnlyMode() {
            GenerateOptions opts =
                    GenerateOptions.builder().modelName(MODEL).stream(false)
                            .additionalBodyParam("prompt_cache_options", Map.of("mode", "explicit"))
                            .build();
            ResponseCreateParams params = mapWith(opts, null, null);
            assertTrue(params.promptCacheOptions().isPresent());
            assertTrue(params.promptCacheOptions().orElseThrow().mode().isPresent());
            assertFalse(params.promptCacheOptions().orElseThrow().ttl().isPresent());
        }
    }
}
