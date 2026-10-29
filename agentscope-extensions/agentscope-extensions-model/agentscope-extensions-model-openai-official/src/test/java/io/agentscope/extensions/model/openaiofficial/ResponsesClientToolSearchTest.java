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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseToolSearchCall;
import com.openai.services.blocking.ResponseService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.search.ToolSearchMiddleware;
import io.agentscope.core.tool.search.ToolSearchTool;
import io.agentscope.extensions.model.openaiofficial.tool.ResponsesToolHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ResponsesClientToolSearchTest {
    private static ResponseOutputItem searchCall() {
        return ResponseOutputItem.ofToolSearchCall(
                ResponseToolSearchCall.builder()
                        .id("search_item")
                        .callId("search_call")
                        .execution(ResponseToolSearchCall.Execution.CLIENT)
                        .status(ResponseToolSearchCall.Status.COMPLETED)
                        .arguments(JsonValue.from(Map.of("query", "order")))
                        .build());
    }

    private static ToolUseBlock decoded() {
        return ResponsesToolHelper.decodeClientToolSearch(searchCall());
    }

    @Test
    void nativeSearchDeclarationAndHistoryRoundTrip() {
        var use = decoded();
        assertFalse(use.isServerTool());
        assertEquals("search_call", use.getId());
        assertEquals(Map.of("query", "order"), use.getInput());
        assertEquals(
                Map.of(ResponsesToolHelper.CLIENT_TOOL_SEARCH_CALL_METADATA, true),
                use.getMetadata());
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id(use.getId())
                        .name("tool_search")
                        .state(ToolResultState.SUCCESS)
                        .output(TextBlock.builder().text("Found order").build())
                        .metadata(
                                Map.of(
                                        ToolSearchTool.METADATA_TOOLS,
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "get_order",
                                                        "description",
                                                        "Look up orders",
                                                        "parameters",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "properties",
                                                                Map.of())))))
                        .build();
        List<Msg> messages =
                List.of(
                        AssistantMessage.builder().content(use).build(),
                        ToolResultMessage.builder().content(result).build());
        var schema =
                ToolSchema.builder()
                        .name("tool_search")
                        .description("Discover tools")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .toolSearch(true)
                        .build();
        var request =
                ResponsesRequestMapper.map(
                        messages,
                        List.of(schema),
                        List.of(),
                        GenerateOptions.builder().modelName("test").build(),
                        true,
                        null,
                        ResponsesRequestMapper::mapHistory);
        assertEquals(ToolSearchTool.NAME, use.getName());
        assertTrue(request.tools().orElseThrow().get(0).isSearch());
        assertEquals(
                com.openai.models.responses.ToolSearchTool.Execution.CLIENT,
                request.tools().orElseThrow().get(0).asSearch().execution().orElseThrow());
        assertTrue(
                request.input()
                        .orElseThrow()
                        .asResponse()
                        .get(1)
                        .asToolSearchOutput()
                        .tools()
                        .get(0)
                        .asFunction()
                        .strict()
                        .orElseThrow());
        var history = ResponsesRequestMapper.mapHistory(messages);
        assertTrue(history.get(0).isToolSearchCall());
        assertTrue(history.get(1).isToolSearchOutput());
        var restoredCall = history.get(0).asToolSearchCall();
        assertEquals("search_call", restoredCall.callId().orElseThrow());
        assertEquals("client", restoredCall.execution().orElseThrow().asString());
        assertEquals("completed", restoredCall.status().orElseThrow().asString());
        assertEquals(JsonValue.from(Map.of("query", "order")), restoredCall._arguments());
        var output = history.get(1).asToolSearchOutput();
        assertEquals("search_call", output.callId().orElseThrow());
        assertEquals("get_order", output.tools().get(0).asFunction().name());
        assertEquals(true, output.tools().get(0).asFunction().deferLoading().orElseThrow());
        var multiHistory = new ResponsesMultiAgentFormatter().formatHistory(messages);
        assertTrue(multiHistory.get(1).isToolSearchOutput());
    }

    @Test
    void deniedOrInvalidSearchStillProducesNativeEmptyOutput() {
        var use = decoded();
        var error =
                ToolResultBlock.error("Invalid query").withIdAndName(use.getId(), use.getName());
        var history =
                ResponsesRequestMapper.mapHistory(
                        List.of(
                                AssistantMessage.builder().content(use).build(),
                                ToolResultMessage.builder().content(error).build()));
        assertTrue(history.get(1).isToolSearchOutput());
        assertTrue(history.get(1).asToolSearchOutput().tools().isEmpty());
    }

    @Test
    void streamingAndNonStreamingClientCallsRemainLocallyExecutable() {
        var item = searchCall();
        var response = TestSdkFixtures.response(List.of(item), ResponseStatus.COMPLETED, null);
        var parsed = ResponsesResponseParser.parse(response, "test", Instant.now());
        ToolUseBlock nonStreaming = (ToolUseBlock) parsed.getContent().get(0);
        assertFalse(nonStreaming.isServerTool());
        var stream =
                TestSdkFixtures.streamOf(
                        List.of(
                                TestSdkFixtures.outputItemAddedEvent(item),
                                TestSdkFixtures.outputItemDoneEvent(item),
                                TestSdkFixtures.completedEvent(response)));
        var chunks =
                ResponsesStreamingAssembler.assemble(stream, "test", Instant.now())
                        .collectList()
                        .block();
        var calls =
                chunks.stream()
                        .flatMap(chunk -> chunk.getContent().stream())
                        .filter(ToolUseBlock.class::isInstance)
                        .map(ToolUseBlock.class::cast)
                        .toList();
        assertEquals(1, calls.size());
        assertFalse(calls.get(0).isServerTool());
        assertEquals(nonStreaming.getInput(), calls.get(0).getInput());
        assertEquals(nonStreaming.getMetadata(), calls.get(0).getMetadata());
    }

    @Test
    void nativeSearchLoadsAndExecutesThroughTheRealAgentLoop() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService service = mock(ResponseService.class);
        when(client.responses()).thenReturn(service);
        var response =
                TestSdkFixtures.response(List.of(searchCall()), ResponseStatus.COMPLETED, null);
        var function =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .id("order_item")
                                .callId("order_call")
                                .name("get_order")
                                .arguments("{}")
                                .build());
        List<Response> responses =
                List.of(
                        response,
                        TestSdkFixtures.response(List.of(function), ResponseStatus.COMPLETED, null),
                        TestSdkFixtures.textResponse("done"));
        List<ResponseCreateParams> requests = new ArrayList<>();
        when(service.create(any(ResponseCreateParams.class)))
                .thenAnswer(
                        invocation -> {
                            requests.add(invocation.getArgument(0));
                            return responses.get(requests.size() - 1);
                        });
        var options =
                GenerateOptions.builder().modelName("test").apiKey("test").stream(false).build();
        var model =
                new OpenAIResponsesChatModel(
                        client, options, "test", null, null, null, null, List.of());
        Toolkit toolkit = new Toolkit();
        AtomicInteger executions = new AtomicInteger();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("get_order")
                                .description("Look up order")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                                .readOnly(true)) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.success("order 42"));
                    }
                });
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(new ToolSearchMiddleware(toolkit))
                        .build();
        assertEquals(
                "done",
                agent.call(
                                "Find order",
                                RuntimeContext.builder().sessionId("native-search").build())
                        .block()
                        .getTextContent());
        assertEquals(1, executions.get());
        assertEquals(3, requests.size());
        assertEquals(1, requests.get(1).tools().orElseThrow().size());
        assertTrue(
                requests.get(1).input().orElseThrow().asResponse().stream()
                        .filter(item -> item.isToolSearchOutput())
                        .anyMatch(
                                item ->
                                        item.asToolSearchOutput()
                                                .tools()
                                                .get(0)
                                                .asFunction()
                                                .name()
                                                .equals("get_order")));
    }
}
