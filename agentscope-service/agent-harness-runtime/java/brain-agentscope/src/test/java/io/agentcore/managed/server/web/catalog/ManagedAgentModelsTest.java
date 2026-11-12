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
package io.agentcore.managed.server.web.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedHttpExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedHttpPreviews;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionPreviews;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Timeout(30)
class ManagedAgentModelsTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sealedControlsOverrideNativeDefaults() {
        var binding = new ObjectMapper().createObjectNode();
        binding.put("requestTimeoutSeconds", 17);
        binding.putObject("parameters")
                .put("topP", 0.75)
                .put("parallelToolCalls", false)
                .put("toolChoice", "none");
        var options =
                ManagedAgentModels.options(
                        binding,
                        GenerateOptions.builder().topP(0.3).parallelToolCalls(true).build());
        assertEquals(0.75, options.getTopP());
        assertEquals(false, options.getParallelToolCalls());
        assertEquals("None", options.getToolChoice().getClass().getSimpleName());
        assertEquals(Duration.ofSeconds(17), options.getExecutionConfig().getTimeout());
        assertEquals(1, options.getExecutionConfig().getMaxAttempts());
    }

    @Test
    void nativeClientsKeepIndependentBindingsAndEnforceParametersOnActualHttpRequests()
            throws Exception {
        var requests = new ConcurrentHashMap<String, JsonNode>();
        var headers = new ConcurrentHashMap<String, String>();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    var body = mapper.readTree(exchange.getRequestBody());
                    requests.put(path, body);
                    boolean anthropic = path.startsWith("/anthropic/");
                    headers.put(
                            path,
                            exchange.getRequestHeaders()
                                    .getFirst(anthropic ? "x-api-key" : "Authorization"));
                    byte[] content =
                            (anthropic ? anthropicResponse() : openaiResponse())
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, content.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(content);
                    }
                });
        http.start();
        try {
            String endpoint = "http://127.0.0.1:" + http.getAddress().getPort();
            var factory =
                    new ManagedAgentModels(
                            mock(SessionServiceClient.class),
                            mapper,
                            Map.of(
                                            "MANAGED_MODEL_ONE",
                                            "key-one",
                                            "MANAGED_MODEL_TWO",
                                            "key-two",
                                            "MANAGED_MODEL_THREE",
                                            "key-three")
                                    ::get);
            var first =
                    factory.create(
                            binding(
                                    "one",
                                    endpoint + "/one",
                                    "MANAGED_MODEL_ONE",
                                    "openai-completions",
                                    512));
            var second =
                    factory.create(
                            binding(
                                    "two",
                                    endpoint + "/two",
                                    "MANAGED_MODEL_TWO",
                                    "openai-completions",
                                    64));
            var third =
                    factory.create(
                            binding(
                                    "three",
                                    endpoint + "/anthropic",
                                    "MANAGED_MODEL_THREE",
                                    "anthropic-messages",
                                    128));
            assertEquals(8192, first.getContextWindowSize());
            assertEquals(8192, third.getContextWindowSize());
            var messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text("hello").build())
                                    .build());
            var chunks =
                    Flux.merge(
                                    first.stream(
                                                    messages,
                                                    List.of(),
                                                    GenerateOptions.builder()
                                                            .temperature(0.9)
                                                            .maxTokens(4096)
                                                            .build())
                                            .subscribeOn(Schedulers.boundedElastic()),
                                    second.stream(
                                                    messages,
                                                    List.of(),
                                                    GenerateOptions.builder().maxTokens(32).build())
                                            .subscribeOn(Schedulers.boundedElastic()),
                                    third.stream(messages, List.of(), null)
                                            .subscribeOn(Schedulers.boundedElastic()))
                            .collectList()
                            .block(Duration.ofSeconds(15));
            assertNotNull(chunks);
            assertFalse(chunks.isEmpty());
            assertEquals(3, requests.size());
            for (var entry : requests.entrySet()) {
                String path = entry.getKey();
                JsonNode request = entry.getValue();
                String name =
                        path.startsWith("/one/")
                                ? "one"
                                : path.startsWith("/two/") ? "two" : "three";
                assertEquals(name, request.required("model").asText());
                assertEquals(0.2, request.required("temperature").asDouble());
                assertEquals(
                        name.equals("one") ? 512 : name.equals("two") ? 32 : 128,
                        request.required("max_tokens").asInt());
                assertTrue(request.required("stream").asBoolean());
                assertEquals(
                        name.equals("three") ? "key-three" : "Bearer key-" + name,
                        headers.get(path));
            }
        } finally {
            http.stop(0);
        }
    }

    @Test
    void boundModelsDoNotHideRetriesAndKeepTheRequestDeadline() {
        var requested =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .maxAttempts(5)
                                        .timeout(Duration.ofSeconds(180))
                                        .build())
                        .build();
        var options = ManagedAgentModels.options(mapper.createObjectNode(), requested);
        assertEquals(1, options.getExecutionConfig().getMaxAttempts());
        assertEquals(Duration.ofSeconds(180), options.getExecutionConfig().getTimeout());
    }

    @Test
    void dashscopeHistoryKeepsToolCallsEncryptedReplayAndOriginalCheckpoint() {
        var config =
                mapper.createObjectNode()
                        .put("provider", "dashscope")
                        .put("api", "openai-responses");
        var plain = ThinkingBlock.builder().thinking("plain reasoning").build();
        var encrypted =
                ThinkingBlock.builder()
                        .thinking("summary")
                        .metadata(Map.of("openai.reasoning.encrypted_content", "opaque-replay"))
                        .build();
        var call =
                ToolUseBlock.builder().id("call_1").name("lookup").input(Map.of("id", "1")).build();
        var original =
                Msg.builder()
                        .id("message_1")
                        .role(MsgRole.ASSISTANT)
                        .content(plain, encrypted, call)
                        .metadata(Map.of("keep", "metadata"))
                        .build();
        var history =
                List.of(original, Msg.builder().role(MsgRole.ASSISTANT).content(plain).build());
        var mapped = ManagedAgentModels.responsesHistory(config, history);
        assertEquals(1, mapped.size());
        assertEquals(List.of(encrypted, call), mapped.get(0).getContent());
        assertEquals(original.getId(), mapped.get(0).getId());
        assertEquals(original.getMetadata(), mapped.get(0).getMetadata());
        assertEquals(3, original.getContent().size());
        config.put("provider", "openai");
        assertEquals(history, ManagedAgentModels.responsesHistory(config, history));
        config.put("provider", "dashscope").put("api", "openai-completions");
        assertEquals(history, ManagedAgentModels.responsesHistory(config, history));
    }

    @Test
    void responsesApiUsesNativeStreamingAndMapsReasoningAndOutputLimit() throws Exception {
        var firstDelta = new CountDownLatch(1);
        var streamedBeforeTerminal = new AtomicBoolean();
        var request = new AtomicReference<JsonNode>();
        var authorization = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/",
                exchange -> {
                    request.set(mapper.readTree(exchange.getRequestBody()));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    path.set(exchange.getRequestURI().getPath());
                    var response =
                            mapper.createObjectNode()
                                    .put("id", "resp_test")
                                    .put("object", "response")
                                    .put("created_at", 1)
                                    .put("model", "responses-model")
                                    .put("status", "completed")
                                    .put("parallel_tool_calls", true)
                                    .put("temperature", 0.2)
                                    .put("top_p", 1)
                                    .put("tool_choice", "auto")
                                    .put("store", false);
                    response.putArray("output")
                            .addObject()
                            .put("id", "msg_test")
                            .put("type", "message")
                            .put("role", "assistant")
                            .put("status", "completed")
                            .putArray("content")
                            .addObject()
                            .put("type", "output_text")
                            .put("text", "responses answer")
                            .putArray("annotations");
                    response.putArray("tools");
                    response.putObject("metadata");
                    var usage =
                            response.putObject("usage")
                                    .put("input_tokens", 5)
                                    .put("output_tokens", 2)
                                    .put("total_tokens", 7);
                    usage.putObject("input_tokens_details")
                            .put("cached_tokens", 0)
                            .put("cache_write_tokens", 0);
                    usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
                    var delta =
                            mapper.createObjectNode()
                                    .put("type", "response.output_text.delta")
                                    .put("sequence_number", 1)
                                    .put("item_id", "msg_test")
                                    .put("output_index", 0)
                                    .put("content_index", 0)
                                    .put("delta", "responses ");
                    var secondDelta =
                            delta.deepCopy().put("sequence_number", 2).put("delta", "answer");
                    var complete =
                            mapper.createObjectNode()
                                    .put("type", "response.completed")
                                    .put("sequence_number", 3);
                    complete.set("response", response);
                    byte[] bytes =
                            ("event: response.output_text.delta\ndata: "
                                            + delta
                                            + "\n\nevent: response.output_text.delta\ndata: "
                                            + secondDelta
                                            + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    byte[] terminalBytes =
                            ("event: response.completed\ndata: " + complete + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                        output.flush();
                        try {
                            streamedBeforeTerminal.set(firstDelta.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        output.write(terminalBytes);
                    }
                });
        http.start();
        try {
            var config =
                    binding(
                            "responses-model",
                            "http://127.0.0.1:" + http.getAddress().getPort() + "/v1",
                            "MANAGED_MODEL_RESPONSES",
                            "openai-responses",
                            128);
            config.put("provider", "dashscope");
            config.put("reasoning", true);
            ((ObjectNode) config.required("parameters")).put("reasoning", "low");
            ((ObjectNode) config.required("parameters"))
                    .put("topP", 0.75)
                    .put("parallelToolCalls", false)
                    .put("toolChoice", "none");
            var model =
                    new ManagedAgentModels(
                                    mock(SessionServiceClient.class),
                                    mapper,
                                    ignored -> "responses-secret")
                            .create(config);
            var messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(
                                            ThinkingBlock.builder()
                                                    .thinking("private previous reasoning")
                                                    .build(),
                                            TextBlock.builder().text("previous answer").build())
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text("hello").build())
                                    .build());
            var schema =
                    mapper.readTree(
                            """
                            {"type":"object","properties":{
                              "answer":{"default":null,"anyOf":[{"type":"string"},{"type":"integer"}]},
                              "limit":{"type":"integer","minimum":0,"default":9223372036854775807},
                              "nested":{"type":"object","properties":{"value":{"const":null}}}
                            },"required":[]}
                            """);
            var tool =
                    ToolSchema.builder()
                            .name("complete_task")
                            .description("Complete the task")
                            .parameters(mapper.convertValue(schema, Map.class))
                            .build();
            var chunks =
                    model.stream(messages, List.of(tool), null)
                            .doOnNext(ignored -> firstDelta.countDown())
                            .collectList()
                            .block(Duration.ofSeconds(15));
            assertNotNull(chunks);
            assertTrue(streamedBeforeTerminal.get(), "Preview must not buffer until completion");
            assertEquals(3, chunks.size());
            assertEquals(1, chunks.stream().map(ChatResponse::getId).distinct().count());
            assertEquals(
                    "responses answer",
                    chunks.stream()
                            .flatMap(chunk -> chunk.getContent().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .reduce("", String::concat));
            var reasoning = new ReasoningContext("test");
            chunks.forEach(reasoning::processChunk);
            assertEquals(chunks.get(0).getId(), reasoning.buildFinalMessage().getId());
            assertEquals("resp_test", chunks.get(2).getMetadata().get("openai.response.id"));
            assertTrue(messages.get(0).hasContentBlocks(ThinkingBlock.class));
            assertFalse(
                    request.get()
                            .required("input")
                            .toString()
                            .contains("private previous reasoning"));
            assertTrue(request.get().required("input").toString().contains("previous answer"));
            assertEquals("/v1/responses", path.get());
            assertEquals("Bearer responses-secret", authorization.get());
            assertEquals("responses-model", request.get().required("model").asText());
            assertEquals(128, request.get().required("max_output_tokens").asInt());
            assertEquals(0.2, request.get().required("temperature").asDouble());
            assertEquals(0.75, request.get().required("top_p").asDouble());
            assertFalse(request.get().required("parallel_tool_calls").asBoolean());
            assertEquals("none", request.get().required("tool_choice").asText());
            assertEquals("low", request.get().required("reasoning").required("effort").asText());
            assertFalse(request.get().required("store").asBoolean());
            assertEquals(schema, request.get().required("tools").get(0).required("parameters"));
            assertEquals(8192, model.getContextWindowSize());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void responsesIdentityIsSubscriptionLocalAndRetainsNativePayload() {
        var tool =
                ToolUseBlock.builder().id("call_original").name("lookup").input(Map.of()).build();
        var usage = ChatUsage.builder().inputTokens(5).outputTokens(2).build();
        var original =
                ChatResponse.builder()
                        .id("provider_terminal")
                        .content(List.of(tool))
                        .usage(usage)
                        .metadata(Map.of("provider", "metadata"))
                        .finishReason("completed")
                        .build();
        var stream = ManagedAgentModels.stableResponsesIdentity(Flux.just(original));
        var first = stream.blockFirst();
        var second = stream.blockFirst();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(original.getId(), first.getId());
        assertSame(tool, first.getContent().get(0));
        assertSame(usage, first.getUsage());
        assertEquals(original.getMetadata(), first.getMetadata());
        assertEquals(original.getFinishReason(), first.getFinishReason());
        var failure = new IllegalStateException("provider failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                ManagedAgentModels.stableResponsesIdentity(Flux.error(failure))
                                        .blockLast()));
        var cancelled = new AtomicBoolean();
        var subscription =
                ManagedAgentModels.stableResponsesIdentity(
                                Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)))
                        .subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
    }

    @Test
    void responsesKeepQueuedPreviewsAndFinalMessageOnTheSameIdentity() throws Exception {
        var scope = new ManagedScope("test", "test", "queued-preview");
        var handles = mock(ManagedHttpExecutions.class);
        var request = mapper.createObjectNode().put("turnId", "turn");
        request.putObject("fence").put("epoch", "1");
        when(handles.request(scope)).thenAnswer(ignored -> request.deepCopy());
        var releaseSender = new CountDownLatch(1);
        var received = new CountDownLatch(2);
        var frames = new CopyOnWriteArrayList<JsonNode>();
        var reasoning = new ReasoningContext("test");
        try (var previews =
                new ManagedHttpPreviews(
                        (operation, value) -> {
                            if (operation.equals("previews/interest")) {
                                try {
                                    if (!releaseSender.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("Producer did not finish");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                                return mapper.createObjectNode().put("interested", true);
                            }
                            frames.add(mapper.valueToTree(value));
                            received.countDown();
                            return mapper.createObjectNode().put("accepted", true);
                        },
                        handles)) {
            var preview = previews.begin(scope, "turn", "model", 1, "REASONING");
            var source =
                    Flux.just(
                            ChatResponse.builder()
                                    .content(List.of(TextBlock.builder().text("first").build()))
                                    .build(),
                            ChatResponse.builder()
                                    .content(List.of(TextBlock.builder().text("second").build()))
                                    .build(),
                            ChatResponse.builder().id("provider_final").content(List.of()).build());
            try {
                ManagedAgentModels.stableResponsesIdentity(source)
                        .doOnNext(
                                chunk -> {
                                    reasoning.processChunk(chunk);
                                    preview.accept(
                                            new ManagedSessionPreviews.TextDelta(
                                                    chunk.getId(),
                                                    chunk.getContent().stream()
                                                            .filter(TextBlock.class::isInstance)
                                                            .map(TextBlock.class::cast)
                                                            .map(TextBlock::getText)
                                                            .toList()));
                                })
                        .blockLast();
            } finally {
                releaseSender.countDown();
            }
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(2, frames.size());
            for (var frame : frames)
                assertEquals(
                        reasoning.buildFinalMessage().getId(),
                        frame.required("messageId").asText());
            assertEquals("0", frames.get(0).required("sequence").asText());
            assertEquals("2", frames.get(1).required("sequence").asText());
        }
    }

    @Test
    void rejectsMissingCredentialsUnsupportedApisAndRequestOverrides() throws Exception {
        var binding =
                binding(
                        "model",
                        "https://models.example/v1",
                        "MANAGED_MODEL_ONE",
                        "openai-completions",
                        512);
        var missing =
                new ManagedAgentModels(mock(SessionServiceClient.class), mapper, ignored -> null);
        assertThrows(IllegalArgumentException.class, () -> missing.create(binding));
        var models =
                new ManagedAgentModels(
                        mock(SessionServiceClient.class), mapper, ignored -> "test-secret");
        var unsupported = binding.deepCopy();
        unsupported.put("api", "google-generative-ai");
        assertThrows(IllegalArgumentException.class, () -> models.create(unsupported));
        var anthropic = binding.deepCopy();
        anthropic.put("api", "anthropic-messages");
        ((ObjectNode) anthropic.required("parameters")).put("reasoning", "high");
        assertThrows(IllegalArgumentException.class, () -> models.create(anthropic));
        binding.put("reasoning", false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ManagedAgentModels.options(
                                binding,
                                GenerateOptions.builder().reasoningEffort("high").build()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ManagedAgentModels.options(
                                binding,
                                GenerateOptions.builder()
                                        .additionalBodyParam("max_tokens", 99999)
                                        .build()));
        var model = models.create(binding);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        model.stream(
                                        List.of(),
                                        List.of(),
                                        GenerateOptions.builder().apiKey("other-secret").build())
                                .blockLast());
        var capped =
                ManagedAgentModels.options(
                        binding, GenerateOptions.builder().maxCompletionTokens(256).build());
        assertEquals(256, capped.getMaxTokens());
        assertEquals(256, capped.getMaxCompletionTokens());
    }

    ObjectNode binding(String id, String endpoint, String credential, String api, int maxTokens) {
        var value = mapper.createObjectNode();
        value.put("provider", "managed")
                .put("id", id)
                .put("api", api)
                .put("baseUrl", endpoint)
                .put("credentialEnv", credential)
                .put("contextWindow", 8192)
                .put("outputLimit", 2048);
        value.putObject("parameters").put("temperature", 0.2).put("maxTokens", maxTokens);
        return value;
    }

    static String openaiResponse() {
        return "data:"
                   + " {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"bound"
                   + " answer\"},\"finish_reason\":null}]}\n\n"
                   + "data:"
                   + " {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n"
                   + "data: [DONE]\n\n";
    }

    static String anthropicResponse() {
        return "event: message_start\n"
                   + "data:"
                   + " {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}\n\n"
                   + "event: content_block_start\n"
                   + "data:"
                   + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                   + "event: content_block_delta\n"
                   + "data:"
                   + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"bound"
                   + " answer\"}}\n\n"
                   + "event: content_block_stop\n"
                   + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                   + "event: message_delta\n"
                   + "data:"
                   + " {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}\n\n"
                   + "event: message_stop\n"
                   + "data: {\"type\":\"message_stop\"}\n\n";
    }
}
