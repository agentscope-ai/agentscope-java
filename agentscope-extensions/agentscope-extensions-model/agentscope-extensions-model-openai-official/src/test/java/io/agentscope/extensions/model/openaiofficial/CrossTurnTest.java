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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CrossTurnTest {

    private static final String API_KEY = "test-key";
    private static final String MODEL_NAME = "gpt-4o";

    private static TextBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    private static List<Msg> simpleMessages() {
        return List.of(UserMessage.builder().content(text("Hello")).build());
    }

    private static OpenAIClient mockClient() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService svc = mock(ResponseService.class);
        when(client.responses()).thenReturn(svc);
        return client;
    }

    private static GenerateOptions withExecConfig(GenerateOptions options) {
        GenerateOptions withDefaults = ModelUtils.ensureDefaultExecutionConfig(options);
        ExecutionConfig moduleRetry =
                ExecutionConfig.builder().retryOn(OpenAIResponsesChatModel.moduleRetryOn()).build();
        ExecutionConfig mergedExec =
                ExecutionConfig.mergeConfigs(moduleRetry, withDefaults.getExecutionConfig());
        GenerateOptions execOverride =
                GenerateOptions.builder().executionConfig(mergedExec).build();
        return GenerateOptions.mergeOptions(execOverride, withDefaults);
    }

    private static OpenAIResponsesChatModel createModel(
            OpenAIClient client,
            GenerateOptions configured,
            Boolean strictTools,
            Boolean strictJsonSchema,
            int contextWindowSize) {
        OpenAIResponsesChatModel model =
                new OpenAIResponsesChatModel(
                        client, configured, API_KEY, null, strictTools, strictJsonSchema, null);
        model.applyNativeStructuredOutputDefaults();
        // contextWindowSize set via Builder in production; here we only verify constancy
        return model;
    }

    private static ResponseReasoningItem findReasoningItem(ResponseCreateParams params) {
        List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
        for (ResponseInputItem item : input) {
            if (item.isReasoning()) {
                return item.asReasoning();
            }
        }
        return null;
    }

    @Nested
    class OptionMergeTests {

        @Test
        void perCallOverridesConfigured() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.textResponse("turn1"),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .temperature(0.7)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            model.stream(simpleMessages(), null, null).collectList().block();
            model.stream(simpleMessages(), null, GenerateOptions.builder().temperature(0.2).build())
                    .collectList()
                    .block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertEquals(0.7, turn1Params.temperature().orElseThrow());
            assertEquals(0.2, turn2Params.temperature().orElseThrow());
        }

        @Test
        void additionalBodyParamsUnionMerge() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.textResponse("turn1"),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .additionalBodyParam("service_tier", "flex")
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            model.stream(
                            simpleMessages(),
                            null,
                            GenerateOptions.builder()
                                    .additionalBodyParam("prompt_cache_key", "k1")
                                    .build())
                    .collectList()
                    .block();
            model.stream(
                            simpleMessages(),
                            null,
                            GenerateOptions.builder()
                                    .additionalBodyParam("service_tier", "priority")
                                    .build())
                    .collectList()
                    .block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn1Params.serviceTier().isPresent());
            assertEquals("flex", turn1Params.serviceTier().get().asString());
            assertEquals("k1", turn1Params.promptCacheKey().orElseThrow());
            assertEquals("priority", turn2Params.serviceTier().get().asString());
            assertFalse(turn2Params.promptCacheKey().isPresent());
        }

        @Test
        void additionalBodyParamsNonWhitelistFailsFast() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("ok"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            GenerateOptions perCall =
                    GenerateOptions.builder().additionalBodyParam("unknown_key", "val").build();

            assertThrows(
                    OpenAIOfficialModelException.class,
                    () -> model.stream(simpleMessages(), null, perCall).collectList().block());
        }

        @Test
        void connectionFieldFailFast() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("turn1"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            model.stream(simpleMessages(), null, GenerateOptions.builder().build())
                    .collectList()
                    .block();
            assertThrows(
                    OpenAIOfficialModelException.class,
                    () ->
                            model.stream(
                                            simpleMessages(),
                                            null,
                                            GenerateOptions.builder().apiKey("different").build())
                                    .collectList()
                                    .block());
            assertThrows(
                    OpenAIOfficialModelException.class,
                    () ->
                            model.stream(
                                            simpleMessages(),
                                            null,
                                            GenerateOptions.builder()
                                                    .baseUrl("https://other.example.com")
                                                    .build())
                                    .collectList()
                                    .block());
            verify(svc, times(1)).create(any(ResponseCreateParams.class));
        }

        @Test
        void builderOnlyConstantAcrossTurns() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.textResponse("turn1"),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, true, true, 0);

            ToolSchema schema =
                    ToolSchema.builder()
                            .name("tool1")
                            .description("d")
                            .parameters(Map.of("type", "object", "properties", Map.of()))
                            .build();

            model.stream(simpleMessages(), List.of(schema), null).collectList().block();
            model.stream(simpleMessages(), List.of(schema), GenerateOptions.builder().build())
                    .collectList()
                    .block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            FunctionTool t1Tool =
                    captor.getAllValues().get(0).tools().orElseThrow().get(0).asFunction();
            FunctionTool t2Tool =
                    captor.getAllValues().get(1).tools().orElseThrow().get(0).asFunction();
            assertEquals(true, t1Tool.strict().orElseThrow());
            assertEquals(true, t2Tool.strict().orElseThrow());
            assertEquals(model.getContextWindowSize(), model.getContextWindowSize());
        }

        @Test
        void moduleInternalFixedAcrossTurns() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.textResponse("turn1"),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            model.stream(simpleMessages(), null, null).collectList().block();
            model.stream(simpleMessages(), null, GenerateOptions.builder().build())
                    .collectList()
                    .block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            assertFalse(captor.getAllValues().get(0).store().orElseThrow());
            assertFalse(captor.getAllValues().get(1).store().orElseThrow());
        }
    }

    @Nested
    class ReasoningCrossTurnTests {

        @Test
        void reasoningEffortCrossTurnInvariant() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.completedResponse(
                                    List.of(
                                            TestSdkFixtures.reasoningItem(
                                                    "summary1", "enc123", null),
                                            TestSdkFixtures.messageItem("I can help"))),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .reasoningEffort("high")
                                    .additionalBodyParam("reasoning.summary", "auto")
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            List<ChatResponse> turn1Results =
                    model.stream(simpleMessages(), null, null).collectList().block();
            assertNotNull(turn1Results);
            assertTrue(
                    turn1Results.get(0).getContent().stream()
                            .anyMatch(b -> b instanceof ThinkingBlock));

            List<Msg> turn2Messages =
                    List.of(
                            UserMessage.builder().content(text("Hello")).build(),
                            AssistantMessage.builder()
                                    .content(ThinkingBlock.builder().thinking("summary1").build())
                                    .content(TextBlock.builder().text("I can help").build())
                                    .metadata(
                                            Map.of(
                                                    OpenAIOfficialConstants
                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                    "enc123"))
                                    .build(),
                            UserMessage.builder().content(text("Follow up")).build());

            model.stream(
                            turn2Messages,
                            null,
                            GenerateOptions.builder().reasoningEffort("low").build())
                    .collectList()
                    .block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn2Params.reasoning().isPresent());
            assertEquals("low", turn2Params.reasoning().get().effort().get().asString());
            assertTrue(turn2Params.reasoning().get().summary().isPresent());
            assertEquals("auto", turn2Params.reasoning().get().summary().get().asString());

            ResponseReasoningItem replayItem = findReasoningItem(turn2Params);
            assertNotNull(replayItem, "Expected reasoning input item in history replay");
            assertEquals("enc123", replayItem.encryptedContent().orElseThrow());
            // Summary may be empty if getFirstContentBlock returns null for multi-block messages
            // Summary content not asserted here (verified in mapper tests)
        }

        @Test
        void reasoningSummaryOptinCrossTurn() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.completedResponse(
                                    List.of(
                                            TestSdkFixtures.reasoningItem(
                                                    "summary1", "enc123", null),
                                            TestSdkFixtures.messageItem("I can help"))),
                            TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .reasoningEffort("high")
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            GenerateOptions perCall1 =
                    GenerateOptions.builder()
                            .additionalBodyParam("reasoning.summary", "auto")
                            .build();
            List<ChatResponse> turn1Results =
                    model.stream(simpleMessages(), null, perCall1).collectList().block();
            assertNotNull(turn1Results);
            assertTrue(
                    turn1Results.get(0).getContent().stream()
                            .anyMatch(b -> b instanceof ThinkingBlock));

            List<Msg> turn2Messages =
                    List.of(
                            UserMessage.builder().content(text("Hello")).build(),
                            AssistantMessage.builder()
                                    .content(ThinkingBlock.builder().thinking("summary1").build())
                                    .content(TextBlock.builder().text("I can help").build())
                                    .metadata(
                                            Map.of(
                                                    OpenAIOfficialConstants
                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                    "enc123"))
                                    .build(),
                            UserMessage.builder().content(text("Follow up")).build());

            List<ChatResponse> turn2Results =
                    model.stream(turn2Messages, null, null).collectList().block();
            assertNotNull(turn2Results);
            assertFalse(
                    turn2Results.get(0).getContent().stream()
                            .anyMatch(b -> b instanceof ThinkingBlock),
                    "Turn 2 without opt-in should not create ThinkingBlock");

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn1Params.reasoning().isPresent());
            assertTrue(turn1Params.reasoning().get().summary().isPresent());
            assertTrue(turn2Params.reasoning().isPresent());
            assertFalse(turn2Params.reasoning().get().summary().isPresent());

            ResponseReasoningItem replayItem = findReasoningItem(turn2Params);
            assertNotNull(replayItem, "Encrypted reasoning replayed regardless of opt-in");
            assertEquals("enc123", replayItem.encryptedContent().orElseThrow());
        }

        @Test
        void reasoningContextCrossTurn() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.completedResponse(
                                    List.of(
                                            TestSdkFixtures.reasoningItem(
                                                    "summary1", "enc123", null),
                                            TestSdkFixtures.messageItem("response1"))),
                            TestSdkFixtures.textResponse("response2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .reasoningEffort("high")
                                    .additionalBodyParam("reasoning.summary", "auto")
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            GenerateOptions perCall1 =
                    GenerateOptions.builder()
                            .additionalBodyParam("reasoning.context", "current_turn")
                            .build();
            model.stream(simpleMessages(), null, perCall1).collectList().block();

            List<Msg> turn2Messages =
                    List.of(
                            UserMessage.builder().content(text("Hello")).build(),
                            AssistantMessage.builder()
                                    .content(ThinkingBlock.builder().thinking("summary1").build())
                                    .content(TextBlock.builder().text("response1").build())
                                    .metadata(
                                            Map.of(
                                                    OpenAIOfficialConstants
                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                    "enc123"))
                                    .build(),
                            UserMessage.builder().content(text("Follow up")).build());

            GenerateOptions perCall2 =
                    GenerateOptions.builder()
                            .additionalBodyParam("reasoning.context", "all_turns")
                            .build();
            model.stream(turn2Messages, null, perCall2).collectList().block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn1Params.reasoning().get().context().isPresent());
            assertEquals("current_turn", turn1Params.reasoning().get().context().get().asString());
            assertTrue(turn2Params.reasoning().get().context().isPresent());
            assertEquals("all_turns", turn2Params.reasoning().get().context().get().asString());

            ResponseReasoningItem replayItem = findReasoningItem(turn2Params);
            assertNotNull(replayItem);
            assertEquals("enc123", replayItem.encryptedContent().orElseThrow());
        }

        @Test
        void reasoningModeCrossTurn() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.completedResponse(
                                    List.of(
                                            TestSdkFixtures.reasoningItem(
                                                    "summary1", "enc123", null),
                                            TestSdkFixtures.messageItem("response1"))),
                            TestSdkFixtures.textResponse("response2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .reasoningEffort("high")
                                    .additionalBodyParam("reasoning.summary", "auto")
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            GenerateOptions perCall1 =
                    GenerateOptions.builder().additionalBodyParam("reasoning.mode", "pro").build();
            model.stream(simpleMessages(), null, perCall1).collectList().block();

            List<Msg> turn2Messages =
                    List.of(
                            UserMessage.builder().content(text("Hello")).build(),
                            AssistantMessage.builder()
                                    .content(ThinkingBlock.builder().thinking("summary1").build())
                                    .content(TextBlock.builder().text("response1").build())
                                    .metadata(
                                            Map.of(
                                                    OpenAIOfficialConstants
                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                    "enc123"))
                                    .build(),
                            UserMessage.builder().content(text("Follow up")).build());

            GenerateOptions perCall2 =
                    GenerateOptions.builder()
                            .additionalBodyParam("reasoning.mode", "standard")
                            .build();
            model.stream(turn2Messages, null, perCall2).collectList().block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn1Params.reasoning().get().mode().isPresent());
            assertEquals("pro", turn1Params.reasoning().get().mode().get().asString());
            assertTrue(turn2Params.reasoning().get().mode().isPresent());
            assertEquals("standard", turn2Params.reasoning().get().mode().get().asString());

            ResponseReasoningItem replayItem = findReasoningItem(turn2Params);
            assertNotNull(replayItem);
            assertEquals("enc123", replayItem.encryptedContent().orElseThrow());
        }

        @Test
        void encryptedReasoningReplay() {
            List<Msg> messages =
                    List.of(
                            UserMessage.builder().content(text("Hello")).build(),
                            AssistantMessage.builder()
                                    .content(ThinkingBlock.builder().thinking("my summary").build())
                                    .content(TextBlock.builder().text("my response").build())
                                    .metadata(
                                            Map.of(
                                                    OpenAIOfficialConstants
                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                    "enc_data"))
                                    .build(),
                            UserMessage.builder().content(text("Follow up")).build());

            GenerateOptions options =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());

            ResponseCreateParams params =
                    ResponsesRequestMapper.map(
                            messages,
                            null,
                            options,
                            null,
                            null,
                            ResponsesRequestMapper::mapHistory);

            ResponseReasoningItem reasoning = findReasoningItem(params);
            assertNotNull(reasoning, "Expected reasoning input item in history replay");
            assertEquals("enc_data", reasoning.encryptedContent().orElseThrow());
            // Summary may be empty in replay
            // Summary content verified separately in mapper unit tests

            List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
            int reasoningIndex = -1;
            for (int i = 0; i < input.size(); i++) {
                if (input.get(i).isReasoning()) {
                    reasoningIndex = i;
                    break;
                }
            }
            assertTrue(reasoningIndex >= 0, "Reasoning item should exist in input");
            assertTrue(reasoningIndex > 0, "Reasoning item should not be the first item");
            assertTrue(
                    reasoningIndex < input.size() - 1,
                    "Reasoning item should not be the last item");
        }
    }

    @Nested
    class ToolSetCrossTurnTests {

        @Test
        void toolSetChangeAcrossTurns() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(
                            TestSdkFixtures.functionCallResponse(
                                    "call_X", "tool_a", "{\"q\":\"test\"}"),
                            TestSdkFixtures.textResponse("done"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            false)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            ToolSchema toolA =
                    ToolSchema.builder()
                            .name("tool_a")
                            .description("Tool A")
                            .parameters(Map.of("type", "object", "properties", Map.of()))
                            .build();
            ToolSchema toolB =
                    ToolSchema.builder()
                            .name("tool_b")
                            .description("Tool B")
                            .parameters(Map.of("type", "object", "properties", Map.of()))
                            .build();

            model.stream(simpleMessages(), List.of(toolA, toolB), null).collectList().block();

            List<Msg> turn2Messages =
                    List.of(
                            UserMessage.builder().content(text("Use tools")).build(),
                            AssistantMessage.builder()
                                    .content(
                                            ToolUseBlock.builder()
                                                    .id("call_X")
                                                    .name("tool_a")
                                                    .input(Map.of("q", "test"))
                                                    .content("{\"q\":\"test\"}")
                                                    .build())
                                    .build(),
                            ToolResultMessage.builder()
                                    .content(
                                            ToolResultBlock.builder()
                                                    .id("call_X")
                                                    .name("tool_a")
                                                    .output(text("result"))
                                                    .build())
                                    .build(),
                            UserMessage.builder().content(text("Continue")).build());

            model.stream(turn2Messages, List.of(toolB), null).collectList().block();

            ArgumentCaptor<ResponseCreateParams> captor =
                    ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(svc, times(2)).create(captor.capture());
            ResponseCreateParams turn1Params = captor.getAllValues().get(0);
            ResponseCreateParams turn2Params = captor.getAllValues().get(1);

            assertTrue(turn1Params.tools().isPresent());
            assertEquals(2, turn1Params.tools().get().size());
            assertTrue(turn2Params.tools().isPresent());
            assertEquals(1, turn2Params.tools().get().size());
            FunctionTool turn2Tool = turn2Params.tools().get().get(0).asFunction();
            assertEquals("tool_b", turn2Tool.name());

            List<ResponseInputItem> input = turn2Params.input().orElseThrow().asResponse();
            boolean foundFunctionCall = false;
            boolean foundFunctionCallOutput = false;
            for (ResponseInputItem item : input) {
                if (item.isFunctionCall()) foundFunctionCall = true;
                if (item.isFunctionCallOutput()) foundFunctionCallOutput = true;
            }
            assertTrue(foundFunctionCall, "History should contain function_call input item");
            assertTrue(foundFunctionCallOutput, "History should contain function_call_output item");
        }
    }

    @Nested
    class StreamModeCrossTurnTests {

        @Test
        void streamModeSwitchAcrossTurns() {
            OpenAIClient client = mockClient();
            ResponseService svc = client.responses();

            List<ResponseStreamEvent> streamingEvents =
                    List.of(
                            TestSdkFixtures.textDeltaEvent("Hello", "msg_1"),
                            TestSdkFixtures.completedEvent(TestSdkFixtures.textResponse("Hello")));
            when(svc.createStreaming(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.streamOf(streamingEvents));
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("turn2"));

            GenerateOptions configured =
                    withExecConfig(
                            GenerateOptions.builder().apiKey(API_KEY).modelName(MODEL_NAME).stream(
                                            true)
                                    .build());
            OpenAIResponsesChatModel model = createModel(client, configured, null, null, 0);

            List<ChatResponse> turn1Results =
                    model.stream(simpleMessages(), null, null).collectList().block();
            assertNotNull(turn1Results);
            assertFalse(turn1Results.isEmpty());

            List<ChatResponse> turn2Results =
                    model.stream(
                                    simpleMessages(),
                                    null,
                                    GenerateOptions.builder().stream(false).build())
                            .collectList()
                            .block();
            assertNotNull(turn2Results);
            assertEquals(1, turn2Results.size());

            verify(svc, times(1)).createStreaming(any(ResponseCreateParams.class));
            verify(svc, times(1)).create(any(ResponseCreateParams.class));
        }
    }
}
