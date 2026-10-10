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
package io.agentscope.extensions.model.openaiofficial.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseImageGenCallPartialImageEvent;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseToolSearchCall;
import com.openai.models.responses.ResponseToolSearchOutputItem;
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.openaiofficial.TestSdkFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResponsesServerToolHelper}. */
class ResponsesServerToolHelperTest {

    @Test
    void mergesClientAndServerTools() {
        Tool local = localFunctionTool("local_tool");
        OpenAIServerTool serverTool =
                OpenAIServerTool.of(
                        Tool.ofWebSearch(
                                WebSearchTool.builder()
                                        .type(WebSearchTool.Type.WEB_SEARCH)
                                        .build()));

        List<Tool> merged =
                ResponsesServerToolHelper.mergeTools(List.of(local), List.of(serverTool));

        assertEquals(2, merged.size());
        assertTrue(merged.get(0).isFunction());
        assertTrue(merged.get(1).isWebSearch());
    }

    @Test
    void addsSupportedIncludeFlags() {
        List<OpenAIServerTool> serverTools =
                List.of(
                        webSearchServerTool(),
                        OpenAIServerTool.of(
                                Tool.ofCodeInterpreter(
                                        Tool.CodeInterpreter.builder()
                                                .container("container_1")
                                                .build())));
        ResponseCreateParams.Builder builder = baseBuilder();
        builder.tools(ResponsesServerToolHelper.mergeTools(List.of(), serverTools));
        ResponsesServerToolHelper.applyIncludes(builder, serverTools);
        ResponseCreateParams params = builder.build();

        List<ResponseIncludable> includes = params.include().orElseThrow();

        assertTrue(includes.contains(ResponseIncludable.WEB_SEARCH_CALL_RESULTS));
        assertTrue(includes.contains(ResponseIncludable.WEB_SEARCH_CALL_ACTION_SOURCES));
        assertTrue(includes.contains(ResponseIncludable.CODE_INTERPRETER_CALL_OUTPUTS));
    }

    @Test
    void imageGenerationHasNoSdkIncludable() {
        List<OpenAIServerTool> serverTools =
                List.of(
                        OpenAIServerTool.of(
                                Tool.ofImageGeneration(Tool.ImageGeneration.builder().build())));
        ResponseCreateParams.Builder builder = baseBuilder();
        builder.tools(ResponsesServerToolHelper.mergeTools(List.of(), serverTools));
        ResponsesServerToolHelper.applyIncludes(builder, serverTools);
        ResponseCreateParams params = builder.build();

        assertFalse(params.include().isPresent());
    }

    @Test
    void decodesWebSearchCallAndResult() {
        List<ContentBlock> blocks =
                ResponsesServerToolHelper.decodeBlocks(TestSdkFixtures.webSearchItem());

        assertEquals(2, blocks.size());
        ToolUseBlock use = (ToolUseBlock) blocks.get(0);
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);

        assertEquals("ws_test_001", use.getId());
        assertEquals("web_search", use.getName());
        assertEquals("OpenAI Responses", use.getInput().get("query"));
        assertTrue(use.isServerTool());
        assertNull(use.getMetadata().get("openai.serverToolItem"));
        assertEquals("ws_test_001", result.getId());
        assertEquals("web_search", result.getName());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(result.isServerTool());
        assertNotNull(result.getMetadata().get("openai.serverToolItem"));
        assertTrue(result.getOutput().get(0).toString().contains("OpenAI Responses"));
    }

    @Test
    void fileSearchOutputIsIgnored() {
        assertTrue(
                ResponsesServerToolHelper.decodeBlocks(TestSdkFixtures.fileSearchItem()).isEmpty());
    }

    @Test
    void decodesCodeInterpreterAndImageGenerationResults() {
        assertServerResult(TestSdkFixtures.codeInterpreterItem(), "code_interpreter", "hello");
    }

    @Test
    void decodesCodeInterpreterImageOutputAsDataBlockWithUrlSource() {
        List<ContentBlock> blocks =
                ResponsesServerToolHelper.decodeBlocks(
                        TestSdkFixtures.codeInterpreterItemWithImageOutput());

        assertEquals(2, blocks.size());
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);
        assertEquals(2, result.getOutput().size());
        assertInstanceOf(TextBlock.class, result.getOutput().get(0));
        assertEquals("hello", ((TextBlock) result.getOutput().get(0)).getText());

        assertInstanceOf(DataBlock.class, result.getOutput().get(1));
        DataBlock image = (DataBlock) result.getOutput().get(1);
        assertInstanceOf(URLSource.class, image.getSource());
        URLSource source = (URLSource) image.getSource();
        assertEquals("https://example.com/generated-image.png", source.getUrl());
        assertEquals("image/png", source.getMimeType());
        assertEquals(
                "container_image_001",
                result.getMetadata().get("openai.codeInterpreter.containerId"));
    }

    @Test
    void decodesImageGenerationResultAsDataBlock() {
        List<ContentBlock> blocks =
                ResponsesServerToolHelper.decodeBlocks(TestSdkFixtures.imageGenerationItem());

        assertEquals(2, blocks.size());
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);
        assertInstanceOf(DataBlock.class, result.getOutput().get(0));
        DataBlock image = (DataBlock) result.getOutput().get(0);
        assertInstanceOf(Base64Source.class, image.getSource());
        Base64Source source = (Base64Source) image.getSource();
        assertEquals("image/png", source.getMediaType());
        assertEquals("image-result", source.getData());
    }

    @Test
    void decodesPartialImageAsRunningServerToolResult() {
        ResponseStreamEvent streamEvent =
                TestSdkFixtures.imageGenerationPartialImageEvent(
                        "ig_test_001", 1, "partial-image-base64", "webp");
        ResponseImageGenCallPartialImageEvent event =
                streamEvent.asImageGenerationCallPartialImage();

        ToolResultBlock result = ResponsesServerToolHelper.decodePartialImage(event);

        assertEquals("ig_test_001", result.getId());
        assertEquals("image_generation", result.getName());
        assertEquals(ToolResultState.RUNNING, result.getState());
        assertTrue(result.isServerTool());
        assertInstanceOf(DataBlock.class, result.getOutput().get(0));
        DataBlock image = (DataBlock) result.getOutput().get(0);
        assertInstanceOf(Base64Source.class, image.getSource());
        Base64Source source = (Base64Source) image.getSource();
        assertEquals("image/webp", source.getMediaType());
        assertEquals("partial-image-base64", source.getData());
    }

    @Test
    void separatedToolSearchItemsDoNotCreatePlaceholderResult() {
        List<ContentBlock> callBlocks =
                ResponsesServerToolHelper.decodeBlocks(TestSdkFixtures.toolSearchCallItem());

        assertEquals(1, callBlocks.size());
        ToolUseBlock use = (ToolUseBlock) callBlocks.get(0);
        assertEquals("tool_search", use.getName());
        assertEquals("weather", use.getInput().get("query"));
        assertNotNull(use.getMetadata().get("openai.serverToolItem"));
        assertTrue(
                ResponsesServerToolHelper.decodeResultBlock(TestSdkFixtures.toolSearchCallItem())
                        .isEmpty());

        ToolResultBlock result =
                ResponsesServerToolHelper.decodeResultBlock(TestSdkFixtures.toolSearchOutputItem())
                        .orElseThrow();
        assertEquals("ts_output_001", result.getId());
        assertEquals("tool_search", result.getName());
        assertTrue(result.getOutput().get(0).toString().contains("code_interpreter"));
    }

    @Test
    void hostedToolSearchResultAdoptsCompanionCallId() {
        ResponseOutputItem call = TestSdkFixtures.toolSearchCallItem();
        ResponseOutputItem output = TestSdkFixtures.toolSearchOutputItem();
        ResponsesServerToolHelper.HostedToolSearchPairing pairing =
                new ResponsesServerToolHelper.HostedToolSearchPairing();

        List<ContentBlock> blocks = new ArrayList<>();
        for (ResponseOutputItem item : List.of(call, output)) {
            blocks.addAll(
                    ResponsesServerToolHelper.decodeBlocks(item, pairing.companionCallId(item)));
        }

        assertEquals(2, blocks.size());
        ToolUseBlock use = (ToolUseBlock) blocks.get(0);
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);
        assertEquals("ts_call_001", use.getId());
        assertEquals(use.getId(), result.getId());
        assertTrue(
                ((String) result.getMetadata().get("openai.serverToolItem"))
                        .contains("ts_output_001"));
    }

    @Test
    void multipleHostedToolSearchCallsPairInCallOrder() {
        List<ResponseOutputItem> items =
                List.of(
                        TestSdkFixtures.toolSearchCallItem("ts_call_001"),
                        TestSdkFixtures.toolSearchCallItem("ts_call_002"),
                        TestSdkFixtures.toolSearchOutputItem("ts_output_001"),
                        TestSdkFixtures.toolSearchOutputItem("ts_output_002"));
        ResponsesServerToolHelper.HostedToolSearchPairing pairing =
                new ResponsesServerToolHelper.HostedToolSearchPairing();

        List<ContentBlock> blocks = new ArrayList<>();
        for (ResponseOutputItem item : items) {
            blocks.addAll(
                    ResponsesServerToolHelper.decodeBlocks(item, pairing.companionCallId(item)));
        }

        assertEquals(4, blocks.size());
        assertEquals("ts_call_001", ((ToolUseBlock) blocks.get(0)).getId());
        assertEquals("ts_call_002", ((ToolUseBlock) blocks.get(1)).getId());
        assertEquals("ts_call_001", ((ToolResultBlock) blocks.get(2)).getId());
        assertEquals("ts_call_002", ((ToolResultBlock) blocks.get(3)).getId());
    }

    @Test
    void clientToolSearchResultKeepsSharedCallId() {
        ResponseOutputItem call =
                ResponseOutputItem.ofToolSearchCall(
                        ResponseToolSearchCall.builder()
                                .id("ts_client_call")
                                .arguments(JsonValue.from(Map.of("query", "weather")))
                                .callId("call_client")
                                .execution(ResponseToolSearchCall.Execution.CLIENT)
                                .status(ResponseToolSearchCall.Status.COMPLETED)
                                .build());
        ResponseOutputItem output =
                ResponseOutputItem.ofToolSearchOutput(
                        ResponseToolSearchOutputItem.builder()
                                .id("ts_client_output")
                                .callId("call_client")
                                .execution(ResponseToolSearchOutputItem.Execution.CLIENT)
                                .status(ResponseToolSearchOutputItem.Status.COMPLETED)
                                .tools(
                                        List.of(
                                                Tool.ofCodeInterpreter(
                                                        Tool.CodeInterpreter.builder()
                                                                .container("container_1")
                                                                .build())))
                                .build());
        ResponsesServerToolHelper.HostedToolSearchPairing pairing =
                new ResponsesServerToolHelper.HostedToolSearchPairing();

        List<ContentBlock> blocks = new ArrayList<>();
        for (ResponseOutputItem item : List.of(call, output)) {
            blocks.addAll(
                    ResponsesServerToolHelper.decodeBlocks(item, pairing.companionCallId(item)));
        }

        assertEquals(2, blocks.size());
        ToolUseBlock use = (ToolUseBlock) blocks.get(0);
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);
        assertEquals("call_client", use.getId());
        assertEquals(use.getId(), result.getId());
    }

    private static ResponseCreateParams.Builder baseBuilder() {
        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().model("gpt-4o").store(false);
        return builder;
    }

    private static OpenAIServerTool webSearchServerTool() {
        return OpenAIServerTool.of(
                Tool.ofWebSearch(
                        WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build()));
    }

    private static Tool localFunctionTool(String name) {
        return Tool.ofFunction(
                FunctionTool.builder()
                        .name(name)
                        .strict(false)
                        .parameters(FunctionTool.Parameters.builder().build())
                        .build());
    }

    private static void assertServerResult(
            ResponseOutputItem item, String expectedName, String expectedContent) {
        List<ContentBlock> blocks = ResponsesServerToolHelper.decodeBlocks(item);

        assertEquals(2, blocks.size());
        ToolResultBlock result = (ToolResultBlock) blocks.get(1);
        assertEquals(expectedName, result.getName());
        assertTrue(result.isServerTool());
        assertTrue(result.getOutput().get(0).toString().contains(expectedContent));
    }
}
