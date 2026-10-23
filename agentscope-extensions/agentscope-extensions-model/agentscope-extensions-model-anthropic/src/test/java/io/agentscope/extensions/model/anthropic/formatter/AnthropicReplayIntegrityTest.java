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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Exercises the native parser -> real ReAct accumulation -> next request converter chain. */
class AnthropicReplayIntegrityTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replaysSeparateSignedBlocksInNativeOrder(boolean streaming) throws Exception {
        Flux<ChatResponse> response;
        if (streaming) {
            response =
                    parseEvents(
                            """
                            {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"FIRST"}}
                            """,
                            """
                            {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-first"}}
                            """,
                            """
                            {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"BETWEEN"}}
                            """,
                            """
                            {"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"SECOND"}}
                            """,
                            """
                            {"type":"content_block_delta","index":2,"delta":{"type":"signature_delta","signature":"sig-second"}}
                            """,
                            """
                            {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"call","name":"record","input":{}}}
                            """,
                            """
                            {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{}"}}
                            """,
                            """
                            {"type":"content_block_stop","index":3}
                            """,
                            """
                            {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}
                            """);
        } else {
            Message message =
                    ObjectMappers.jsonMapper()
                            .readValue(
                                    """
                                    {"id":"fixture","type":"message","role":"assistant","model":"fixture",
                                     "content":[
                                      {"type":"thinking","thinking":"FIRST","signature":"sig-first"},
                                      {"type":"text","text":"BETWEEN"},
                                      {"type":"thinking","thinking":"SECOND","signature":"sig-second"},
                                      {"type":"tool_use","id":"call","name":"record","input":{}}],
                                     "stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}
                                    """,
                                    Message.class);
            response = Flux.just(AnthropicResponseParser.parseMessage(message, Instant.now()));
        }
        AtomicInteger executions = new AtomicInteger();
        List<Msg> nextRequest = new ArrayList<>();
        try (ReActAgent agent = agent(response, executions, nextRequest)) {
            agent.call(new UserMessage("fixture")).block(Duration.ofSeconds(5));
            assertEquals(1, executions.get());
            List<MessageParam> nativeMessages =
                    new AnthropicMessageConverter(blocks -> "result").convert(nextRequest);
            assertEquals(3, nativeMessages.size());
            var blocks = nativeMessages.get(1).content().asBlockParams();
            assertEquals(4, blocks.size());
            assertEquals("FIRST", blocks.get(0).asThinking().thinking());
            assertEquals("sig-first", blocks.get(0).asThinking().signature());
            assertEquals("BETWEEN", blocks.get(1).asText().text());
            assertEquals("SECOND", blocks.get(2).asThinking().thinking());
            assertEquals("sig-second", blocks.get(2).asThinking().signature());
            assertEquals("call", blocks.get(3).asToolUse().id());
            assertEquals(
                    "call",
                    nativeMessages
                            .get(2)
                            .content()
                            .asBlockParams()
                            .get(0)
                            .asToolResult()
                            .toolUseId());
            assertEquals(
                    "tool_use",
                    nextRequest.get(1).getMetadata().get(MessageMetadataKeys.FINISH_REASON));
        }
    }

    @Test
    void maxTokensRejectsEmptyStartButKeepsCompletedNoArgSibling() throws Exception {
        Flux<ChatResponse> response =
                parseEvents(
                        """
                        {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"complete","name":"record","input":{}}}
                        """,
                        """
                        {"type":"content_block_stop","index":0}
                        """,
                        """
                        {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"incomplete","name":"record","input":{}}}
                        """,
                        """
                        {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":1}}
                        """);
        AtomicInteger executions = new AtomicInteger();
        List<Msg> nextRequest = new ArrayList<>();
        try (ReActAgent agent = agent(response, executions, nextRequest)) {
            agent.call(new UserMessage("fixture")).block(Duration.ofSeconds(5));
            assertEquals(1, executions.get());
            assertEquals(
                    "max_tokens",
                    nextRequest.get(1).getMetadata().get(MessageMetadataKeys.FINISH_REASON));
            List<ToolResultBlock> results =
                    nextRequest.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            assertEquals(2, results.size());
            assertTrue(
                    results.stream()
                            .anyMatch(
                                    r ->
                                            r.getId().equals("incomplete")
                                                    && r.getState() == ToolResultState.ERROR));
            assertTrue(
                    results.stream()
                            .anyMatch(
                                    r ->
                                            r.getId().equals("complete")
                                                    && r.getState() == ToolResultState.SUCCESS));
            var nativeMessages =
                    new AnthropicMessageConverter(blocks -> "result").convert(nextRequest);
            assertEquals(3, nativeMessages.size());
            assertEquals(2, nativeMessages.get(2).content().asBlockParams().size());
            assertFalse(
                    nativeMessages.get(2).content().asBlockParams().stream()
                            .anyMatch(b -> !b.isToolResult()));
        }
    }

    private static Flux<ChatResponse> parseEvents(String... jsonEvents) throws Exception {
        List<RawMessageStreamEvent> events = new ArrayList<>();
        for (String json : jsonEvents) {
            events.add(ObjectMappers.jsonMapper().readValue(json, RawMessageStreamEvent.class));
        }
        return AnthropicResponseParser.parseStreamEvents(Flux.fromIterable(events), Instant.now());
    }

    private static ReActAgent agent(
            Flux<ChatResponse> firstResponse, AtomicInteger executions, List<Msg> nextRequest) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "record";
                    }

                    @Override
                    public String getDescription() {
                        return "In-memory fixture";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("recorded"));
                    }
                });
        Model model =
                new Model() {
                    private int requests;

                    @Override
                    public String getModelName() {
                        return "fixture";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (requests++ == 0) {
                            return firstResponse;
                        }
                        nextRequest.addAll(messages);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        return ReActAgent.builder()
                .name("fixture")
                .model(model)
                .toolkit(toolkit)
                .maxIters(3)
                .build();
    }
}
