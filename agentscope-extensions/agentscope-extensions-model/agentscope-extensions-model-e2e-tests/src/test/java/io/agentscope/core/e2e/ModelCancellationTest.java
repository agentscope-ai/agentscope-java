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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.errors.BadRequestException;
import com.fasterxml.jackson.core.JsonParseException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Hooks;

/** Local HTTP regressions shared across transport-backed model providers. */
class ModelCancellationTest {
    private MockWebServer server;
    private JdkHttpTransport transport;
    private final Queue<Throwable> dropped = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        transport =
                JdkHttpTransport.builder()
                        .config(
                                HttpTransportConfig.builder()
                                        .readTimeout(Duration.ofSeconds(10))
                                        .build())
                        .build();
        Hooks.onErrorDropped(dropped::add);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            transport.close();
            server.shutdown();
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    static Stream<Arguments> providers() {
        return Stream.of("openai", "dashscope", "ollama", "openai-official", "gemini", "anthropic")
                .flatMap(
                        provider ->
                                Stream.of(
                                        Arguments.of(provider, false),
                                        Arguments.of(provider, true)));
    }

    @ParameterizedTest(name = "{0}, stream={1}")
    @MethodSource("providers")
    void timeoutThenSuccessDoesNotDropCancellationErrors(String provider, boolean stream) {
        Model model = model(provider, stream);
        // Warm up SDK initialization and validate the local fixture outside the timeout budget.
        server.enqueue(response(provider, stream));
        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("warmup").build()),
                        null,
                        GenerateOptions.builder()
                                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                                .build())
                .collectList()
                .block(Duration.ofSeconds(10));
        server.enqueue(response(provider, stream).setHeadersDelay(3, TimeUnit.SECONDS));
        server.enqueue(response(provider, stream));
        // Some SDKs finish the old socket operation despite worker interruption. Allow its
        // connection to settle before retrying; remote cancellation is SDK-dependent.
        Duration backoff =
                List.of("openai-official", "gemini", "anthropic").contains(provider)
                        ? Duration.ofSeconds(5)
                        : Duration.ofMillis(100);
        GenerateOptions options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(1))
                                        .maxAttempts(2)
                                        .initialBackoff(backoff)
                                        .maxBackoff(backoff)
                                        .build())
                        .build();
        List<ChatResponse> results;
        try {
            results =
                    model.stream(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("hello")
                                                    .build()),
                                    null,
                                    options)
                            .collectList()
                            .block(Duration.ofSeconds(10));
        } catch (RuntimeException error) {
            throw new AssertionError(
                    "provider="
                            + provider
                            + ", stream="
                            + stream
                            + ", requests="
                            + server.getRequestCount()
                            + ", dropped="
                            + dropped,
                    error);
        }
        assertEquals(3, server.getRequestCount());
        assertTrue(
                results.stream()
                        .flatMap(result -> result.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(text -> "hello".equals(text.getText())));
        assertTrue(dropped.isEmpty(), () -> "Unexpected dropped errors: " + dropped);
    }

    @Test
    void anthropicStreamAcquisitionFailureKeepsModelExceptionContext() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {"type":"error","error":{"type":"invalid_request_error","message":"test invalid request"}}
                                """));
        Model model = model("anthropic", true);

        ModelException error = assertThrows(ModelException.class, () -> singleAttempt(model));

        assertEquals("test-model", error.getModelName());
        assertEquals("anthropic", error.getProvider());
        assertInstanceOf(BadRequestException.class, error.getCause());
        assertEquals(1, server.getRequestCount());
        assertTrue(dropped.isEmpty());
    }

    @Test
    void geminiStreamAcquisitionFailureKeepsModelException() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {"error":{"code":400,"message":"test invalid request","status":"INVALID_ARGUMENT"}}
                                """));
        GeminiChatModel model = (GeminiChatModel) model("gemini", true);
        try {
            ModelException error = assertThrows(ModelException.class, () -> singleAttempt(model));

            assertInstanceOf(ClientException.class, error.getCause());
            assertEquals(1, server.getRequestCount());
            assertTrue(dropped.isEmpty());
        } finally {
            model.close();
        }
    }

    @Test
    void geminiStreamConsumptionFailureKeepsSdkException() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {invalid}\n\n"));
        GeminiChatModel model = (GeminiChatModel) model("gemini", true);
        try {
            GenAiIOException error =
                    assertThrows(GenAiIOException.class, () -> singleAttempt(model));

            assertInstanceOf(JsonParseException.class, error.getCause());
            assertEquals(1, server.getRequestCount());
            assertTrue(dropped.isEmpty());
        } finally {
            model.close();
        }
    }

    private List<ChatResponse> singleAttempt(Model model) {
        return model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()),
                        null,
                        GenerateOptions.builder()
                                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                                .build())
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private Model model(String provider, boolean stream) {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        switch (provider) {
            case "openai":
                return OpenAIChatModel.builder()
                        .apiKey("test-key")
                        .modelName("test-model")
                        .baseUrl(baseUrl)
                        .stream(stream)
                        .httpTransport(transport)
                        .build();
            case "dashscope":
                return DashScopeChatModel.builder()
                        .apiKey("test-key")
                        .modelName("qwen-max")
                        .baseUrl(baseUrl)
                        .stream(stream)
                        .httpTransport(transport)
                        .build();
            case "ollama":
                return OllamaChatModel.builder().modelName("test-model").baseUrl(baseUrl).stream(
                                stream)
                        .httpTransport(transport)
                        .build();
            case "openai-official":
                return OpenAIResponsesChatModel.builder()
                        .apiKey("test-key")
                        .modelName("test-model")
                        .baseUrl(baseUrl)
                        .stream(stream)
                        .build();
            case "gemini":
                return GeminiChatModel.builder()
                        .apiKey("test-key")
                        .modelName("test-model")
                        .baseUrl(baseUrl)
                        .streamEnabled(stream)
                        .build();
            case "anthropic":
                return AnthropicChatModel.builder()
                        .apiKey("test-key")
                        .modelName("test-model")
                        .baseUrl(baseUrl)
                        .stream(stream)
                        .build();
            default:
                throw new IllegalArgumentException(provider);
        }
    }

    private MockResponse response(String provider, boolean stream) {
        String json;
        switch (provider) {
            case "openai":
                json =
                        stream
                                ? "{\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}"
                                : "{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}";
                break;
            case "dashscope":
                json =
                        "{\"request_id\":\"test\",\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}}";
                break;
            case "ollama":
                json =
                        "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"done\":true}";
                break;
            case "gemini":
                json =
                        """
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}]}
                        """;
                break;
            case "anthropic":
                json =
                        """
                        {"id":"test","type":"message","role":"assistant","model":"test-model",
                        "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                        "usage":{"input_tokens":1,"output_tokens":1}}
                        """;
                if (stream) {
                    return new MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                    "event: message_start\n"
                                            + "data: {\"type\":\"message_start\",\"message\":"
                                            + json.replace("hello", "").replace("\n", "")
                                            + "}\n\n"
                                            + "event: content_block_start\n"
                                            + "data:"
                                            + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                                            + "event: content_block_delta\n"
                                            + "data:"
                                            + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
                                            + "event: content_block_stop\n"
                                            + "data:"
                                            + " {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                                            + "event: message_stop\n"
                                            + "data: {\"type\":\"message_stop\"}\n\n");
                }
                break;
            case "openai-official":
                json =
                        """
                        {"id":"resp_test","object":"response","created_at":1,"status":"completed","model":"test-model",
                        "output":[{"id":"msg_test","type":"message","role":"assistant","status":"completed",
                        "content":[{"type":"output_text","text":"hello","annotations":[]}]}]}
                        """;
                if (stream) {
                    return new MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                    "event: response.output_text.delta\n"
                                        + "data:"
                                        + " {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_test\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\",\"sequence_number\":1}\n\n"
                                        + "event: response.completed\n"
                                        + "data: {\"type\":\"response.completed\",\"response\":"
                                            + json.replace("\n", "")
                                            + ",\"sequence_number\":2}\n\n");
                }
                break;
            default:
                throw new IllegalArgumentException(provider);
        }
        if (stream && !"ollama".equals(provider)) {
            return new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                            "data: "
                                    + json.replace("\n", "")
                                    + ("gemini".equals(provider)
                                            ? "\n\n"
                                            : "\n\ndata: [DONE]\n\n"));
        }
        return new MockResponse()
                .setHeader("Content-Type", stream ? "application/x-ndjson" : "application/json")
                .setBody(json + "\n");
    }
}
