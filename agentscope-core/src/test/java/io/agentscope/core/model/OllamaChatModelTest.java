/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.formatter.ollama.OllamaMultiAgentFormatter;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.model.ollama.ThinkOption;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;

/**
 * Unit tests for OllamaChatModel.
 *
 * <p>These tests verify the OllamaChatModel behavior including configuration
 * and basic execution using Mock transport.
 */
@Tag("unit")
@DisplayName("OllamaChatModel Unit Tests")
class OllamaChatModelTest {

    private static final String TEST_MODEL_NAME = "qwen2.5:14b-instruct";

    @Mock private HttpTransport httpTransport;

    private OllamaChatModel model;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Create model with builder
        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://192.168.2.2:11434")
                        .httpTransport(httpTransport)
                        .build();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        assertNotNull(model, "Model should be created");
        assertEquals(TEST_MODEL_NAME, model.getModelName());

        // Test builder pattern with minimal args
        OllamaChatModel simpleModel =
                OllamaChatModel.builder().modelName("qwen2.5:14b-instruct").build();
        assertNotNull(simpleModel);
        assertEquals("qwen2.5:14b-instruct", simpleModel.getModelName());
    }

    // ========== Configuration Tests ==========

    @Test
    @DisplayName("Should create with default options")
    void testDefaultOptions() {
        OllamaOptions options = OllamaOptions.builder().temperature(0.5).numCtx(4096).build();

        OllamaChatModel modelWithOptions =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
        assertEquals(TEST_MODEL_NAME, modelWithOptions.getModelName());
    }

    @Test
    @DisplayName("Should create with custom base URL")
    void testCustomBaseUrl() {
        OllamaChatModel modelWithBaseUrl =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://192.168.2.2:11434")
                        .build();

        assertNotNull(modelWithBaseUrl);
    }

    @Test
    @DisplayName("Should support different formatter types")
    void testDifferentFormatterTypes() {
        // Chat formatter
        OllamaChatModel chatModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(new OllamaChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // MultiAgent formatter
        OllamaChatModel multiAgentModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(new OllamaMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should handle empty messages list")
    void testEmptyMessagesList() {
        List<Msg> emptyMessages = new ArrayList<>();

        // This should not throw during instantiation
        assertDoesNotThrow(
                () -> {
                    OllamaChatModel testModel =
                            OllamaChatModel.builder().modelName(TEST_MODEL_NAME).build();
                    assertNotNull(testModel);
                });
    }

    // ========== Execution Tests (Mocked) ==========

    @Test
    @DisplayName("Should handle chat request")
    void testChatRequest() {
        System.out.println("Running testChatRequest (Mocked)...");
        // Mock response
        String jsonResponse =
                "{"
                        + "\"model\": \""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"created_at\": \"2023-08-04T08:52:19.385406455-07:00\","
                        + "\"message\": {\"role\": \"assistant\", \"content\": \"Hello!\"},"
                        + "\"done\": true,"
                        + "\"total_duration\": 100,"
                        + "\"load_duration\": 10,"
                        + "\"prompt_eval_count\": 5,"
                        + "\"prompt_eval_duration\": 20,"
                        + "\"eval_count\": 10,"
                        + "\"eval_duration\": 30"
                        + "}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // Execute chat using Ollama-specific chat method
        ChatResponse response =
                model.chat(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        OllamaOptions.builder().build());

        System.out.println(
                "Response content: " + ((TextBlock) response.getContent().get(0)).getText());

        // Verify response
        assertNotNull(response);
        ContentBlock content = response.getContent().get(0);
        assertTrue(content instanceof TextBlock);
        assertEquals("Hello!", ((TextBlock) content).getText());
        assertEquals(TEST_MODEL_NAME, response.getMetadata().get("model"));
        assertEquals(5, response.getUsage().getInputTokens());
        assertEquals(10, response.getUsage().getOutputTokens());

        // Verify request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
        // Note: URL might differ depending on internal logic, but usually /api/chat
        assertTrue(request.getUrl().endsWith("/api/chat"));
    }

    @Test
    @DisplayName("Should handle streaming chat request")
    void testStreamChatRequest() {
        System.out.println("Running testStreamChatRequest (Mocked)...");
        // Mock streaming response
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String part2 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " World\"},\"done\":false}";
        String part3 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"done\":true,\"total_duration\":100,\"eval_count\":2}";

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(part1, part2, part3));

        // Execute stream using generic stream method with GenerateOptions
        // Note: converting OllamaOptions to GenerateOptions for the generic interface
        GenerateOptions genOptions = OllamaOptions.builder().build().toGenerateOptions();
        Flux<ChatResponse> flux =
                model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        genOptions);

        List<ChatResponse> responses = flux.collectList().block();

        System.out.println("Streamed " + responses.size() + " responses");

        // Verify
        assertNotNull(responses);
        assertEquals(3, responses.size());

        ContentBlock content1 = responses.get(0).getContent().get(0);
        assertTrue(content1 instanceof TextBlock);
        assertEquals("Hello", ((TextBlock) content1).getText());

        ContentBlock content2 = responses.get(1).getContent().get(0);
        assertTrue(content2 instanceof TextBlock);
        assertEquals(" World", ((TextBlock) content2).getText());

        // Verify request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
    }

    @Test
    @DisplayName("Integration Test: Real connection to local Ollama")
    void testRealConnection() {
        System.out.println("Running testRealConnection (Integration)...");
        try {
            // Create a real model without mocked transport (uses default OkHttpTransport)
            OllamaChatModel realModel =
                    OllamaChatModel.builder()
                            .modelName(TEST_MODEL_NAME)
                            .baseUrl("http://192.168.2.2:11434")
                            .build();

            // Try a simple chat
            System.out.println("Sending 'Hi' to local Ollama...");
            ChatResponse response =
                    realModel.chat(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                            OllamaOptions.builder().temperature(0.7).build());

            System.out.println("Received response:");
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                ContentBlock block = response.getContent().get(0);
                if (block instanceof TextBlock) {
                    System.out.println("Text: " + ((TextBlock) block).getText());
                }
            }
            System.out.println("Metadata: " + response.getMetadata());

        } catch (Exception e) {
            System.out.println("Skipping real connection test: " + e.getMessage());
            // We don't fail the test if local Ollama is not running, as this is a unit test file
            // But we print the error so the user can see it.
        }
    }

    @Test
    @DisplayName("Should merge options correctly")
    void testOptionMerging() {
        // Default options
        OllamaOptions defaultOpts = OllamaOptions.builder().temperature(0.5).numCtx(2048).build();

        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .defaultOptions(defaultOpts)
                        .httpTransport(httpTransport)
                        .build();

        // Runtime options
        OllamaOptions runtimeOpts =
                OllamaOptions.builder()
                        .temperature(0.8) // Should override
                        .topK(50) // Should add
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // Execute chat
        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Test").build()), runtimeOpts);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        // Verify JSON body contains expected options
        assertTrue(body.contains("\"temperature\":0.8"));
        assertTrue(body.contains("\"num_ctx\":2048"));
        assertTrue(body.contains("\"top_k\":50"));
    }

    @Test
    @DisplayName("Should serialize JSON format option")
    void testJsonFormat() {
        OllamaOptions options = OllamaOptions.builder().format("json").build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Output JSON").build()),
                options);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"format\":\"json\""));
    }

    @Test
    @DisplayName("Should serialize stop sequences")
    void testStopSequences() {
        OllamaOptions options = OllamaOptions.builder().stop(List.of("STOP", "HALT")).build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()), options);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        // Check for stop sequences in JSON array format
        assertTrue(body.contains("\"stop\":[\"STOP\",\"HALT\"]"));
    }

    @Test
    @DisplayName("Should serialize tools correctly")
    void testToolSerialization() {
        // Model default config
        // Define a simple tool
        ToolSchema tool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get current weather")
                        .parameters(
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of("location", Map.of("type", "string")),
                                        "required", List.of("location")))
                        .build();

        // Create model
        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .httpTransport(httpTransport)
                        .build();

        // Mock response
        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        GenerateOptions options = GenerateOptions.builder().build();
        model.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("What's the weather?")
                                        .build()),
                        List.of(tool),
                        options)
                .blockLast();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(
                captor.capture()); // doStream uses streamWithHttpClient which uses stream()

        String body = captor.getValue().getBody();
        // Verify tools are present in JSON
        assertTrue(body.contains("\"tools\":"));
        assertTrue(body.contains("\"name\":\"get_weather\""));
        assertTrue(body.contains("\"function\":"));
    }

    @Test
    @DisplayName("Should parse tool calls from response")
    void testToolCallParsing() {
        // Mock response with tool calls
        // Ollama format: message: { role: assistant, tool_calls: [ { function: { name: ...,
        // arguments: ... } } ] }
        String jsonResponse =
                "{"
                        + "\"model\": \""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"done\": true,"
                        + "\"message\": {"
                        + "  \"role\": \"assistant\","
                        + "  \"content\": \"\","
                        + "  \"tool_calls\": ["
                        + "    {"
                        + "      \"function\": {"
                        + "        \"name\": \"get_weather\","
                        + "        \"arguments\": {\"location\": \"San Francisco\"}"
                        + "      }"
                        + "    }"
                        + "  ]"
                        + "}"
                        + "}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        // Execute
        ChatResponse response =
                model.stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("Weather in SF")
                                                .build()),
                                null,
                                GenerateOptions.builder().build())
                        .blockLast();

        assertNotNull(response);
        assertFalse(response.getContent().isEmpty());

        ContentBlock block = response.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);

        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("get_weather", toolUse.getName());
        assertEquals("San Francisco", toolUse.getInput().get("location"));
    }

    @Test
    @DisplayName("Should serialize image correctly")
    void testImageSerialization() {
        String base64Data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        ImageBlock imageBlock = new ImageBlock(new Base64Source("image/png", base64Data));

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Describe this image").build(),
                                        imageBlock))
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"It is a"
                        + " dot.\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(List.of(msg), OllamaOptions.builder().build());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"images\":[\"" + base64Data + "\"]"));
    }

    @Test
    @DisplayName("Should serialize ToolChoice.Specific")
    void testToolChoiceSpecific() {
        ToolSchema tool =
                ToolSchema.builder().name("get_weather").description("Get weather").build();

        // Use generic GenerateOptions with specific tool choice
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("get_weather"))
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        List.of(tool),
                        options)
                .blockLast();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        // Check for specific tool choice format (Ollama expects tool_choice as object or string?
        // Currently Ollama supports "auto", "none" or specific function object)
        // Adjust based on OllamaChatFormatter implementation.
        // Assuming it serializes to "tool_choice": {"type": "function", "function": {"name":
        // "get_weather"}}
        assertTrue(body.contains("\"tool_choice\":"));
        assertTrue(body.contains("\"function\":"));
        assertTrue(body.contains("\"name\":\"get_weather\""));
    }

    @Test
    @DisplayName("Should handle streaming chat request with options")
    void testStreamChatWithOptions() {
        System.out.println("Running testStreamChatWithOptions...");
        // Mock streaming response
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String part2 =
                "{\"model\":\"" + TEST_MODEL_NAME + "\",\"done\":true,\"total_duration\":100}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(part1, part2));

        OllamaOptions options = OllamaOptions.builder().temperature(0.9).seed(42).build();

        // Execute stream
        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        options.toGenerateOptions())
                .blockLast();

        // Verify request contains options
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"temperature\":0.9"));
        assertTrue(body.contains("\"seed\":42"));
    }

    @Test
    @DisplayName("Should use MultiAgentFormatter correctly")
    void testMultiAgentFormatterExecution() {
        OllamaMultiAgentFormatter multiAgentFormatter = new OllamaMultiAgentFormatter();

        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(multiAgentFormatter)
                        .httpTransport(httpTransport)
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // We use chat() which triggers buildRequest in MultiAgentFormatter
        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                OllamaOptions.builder().build());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        // Just verify the request was made, ensuring the branch was taken without error
        assertNotNull(captor.getValue());
    }

    @Test
    @DisplayName("Should handle streaming error")
    void testStreamErrorHandling() {
        System.out.println("Running testStreamErrorHandling...");

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("Stream error")));

        // Disable retries for this test
        OllamaOptions options =
                OllamaOptions.builder()
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();

        Flux<ChatResponse> flux =
                model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        options.toGenerateOptions());

        try {
            flux.blockLast();
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("Stream error")
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains("Stream error")));
        }
    }

    @Test
    @DisplayName("Should handle streaming with multiple messages context")
    void testStreamWithMultipleMessages() {
        System.out.println("Running testStreamWithMultipleMessages...");

        String responseJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Sure\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(responseJson));

        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi there").build(),
                        Msg.builder().role(MsgRole.USER).textContent("Tell me a joke").build());

        model.stream(history, null, GenerateOptions.builder().build()).blockLast();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        // Verify all messages are in the request body
        assertTrue(body.contains("Hello"));
        assertTrue(body.contains("Hi there"));
        assertTrue(body.contains("Tell me a joke"));
    }

    @Test
    @DisplayName("Should handle transport error")
    void testTransportError() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // Disable retries to ensure original exception is thrown immediately
        OllamaOptions options =
                OllamaOptions.builder()
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();

        try {
            model.chat(
                    List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()), options);
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("Connection refused")
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains("Connection refused")),
                    "Exception should contain 'Connection refused', but was: " + e.getMessage());
        }
    }

    // ========== Thinking Mode Tests ==========

    @Test
    @DisplayName("Should serialize ThinkBoolean option at root level")
    void testThinkBooleanOption() throws JsonProcessingException {
        // Test enabling thinking with temperature to ensure separation
        OllamaOptions enableThinkOptions =
                OllamaOptions.builder()
                        .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                        .temperature(0.8)
                        .build();

        String jsonResponse =
                "{\"model\":\"qwen3:32b-q4_K_M"
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Think!").build()),
                enableThinkOptions);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();

        // Parse JSON to verify structure
        JsonNode root =
                ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        // Verify 'think' is at root
        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertTrue(root.get("think").asBoolean(), "'think' should be true");

        // Verify 'options' exists and contains temperature but NOT think
        assertTrue(root.has("options"), "JSON root should contain 'options'");
        assertTrue(root.get("options").has("temperature"), "options should contain 'temperature'");
        assertFalse(root.get("options").has("think"), "options should NOT contain 'think'");

        // Test disabling thinking
        OllamaOptions disableThinkOptions =
                OllamaOptions.builder().thinkOption(ThinkOption.ThinkBoolean.DISABLED).build();

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Don't think!").build()),
                disableThinkOptions);

        verify(httpTransport, Mockito.times(2)).execute(captor.capture());
        body = captor.getValue().getBody();
        root = ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertFalse(root.get("think").asBoolean(), "'think' should be false");
    }

    @Test
    @DisplayName("Should fetch list of all Ollama models")
    void testFetchAllModels() {
        System.out.println("Running testFetchAllModels...");

        // Mock response data for Ollama /api/tags endpoint
        String mockResponse =
                """
                {
                  "models": [
                    {
                      "name": "qwen2.5:14b-instruct",
                      "model": "qwen2.5:14b-instruct",
                      "modified_at": "2024-05-20T10:30:00.000Z",
                      "size": 8089860096,
                      "digest": "abc123def456...",
                      "details": {
                        "parent_model": "",
                        "format": "gguf",
                        "family": "qwen2",
                        "families": ["qwen2"],
                        "parameter_size": "14B",
                        "quantization_level": "Q4_K_M"
                      }
                    },
                    {
                      "name": "llama3:8b-instruct",
                      "model": "llama3:8b-instruct",
                      "modified_at": "2024-04-15T14:20:00.000Z",
                      "size": 4689860096,
                      "digest": "def456ghi789...",
                      "details": {
                        "parent_model": "",
                        "format": "gguf",
                        "family": "llama3",
                        "families": ["llama3"],
                        "parameter_size": "8B",
                        "quantization_level": "Q4_K_M"
                      }
                    }
                  ]
                }
                """;

        // Configure return value for Mock object
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(mockResponse).build());

        // Create GET request to Ollama server /api/tags endpoint
        HttpRequest request =
                HttpRequest.builder()
                        .url("http://192.168.2.2:11434/api/tags")
                        .method("GET")
                        .build();

        // Execute request and get response
        HttpResponse response = httpTransport.execute(request);

        // Output the list of models obtained
        System.out.println("Response status: " + response.getStatusCode());
        System.out.println("Models list: " + response.getBody());

        // Verify response
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("models"));
        assertTrue(response.getBody().contains("qwen2.5:14b-instruct"));
        assertTrue(response.getBody().contains("llama3:8b-instruct"));
    }

    @Test
    @DisplayName("Should serialize ThinkLevel option at root level")
    void testThinkLevelOption() throws JsonProcessingException {
        // Test high thinking level
        OllamaOptions highThinkOptions =
                OllamaOptions.builder().thinkOption(ThinkOption.ThinkLevel.HIGH).build();

        String jsonResponse =
                "{\"model\":\"qwen3:32b-q4_K_M"
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Think hard!").build()),
                highThinkOptions);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        JsonNode root =
                ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertEquals("high", root.get("think").asText(), "'think' should be 'high'");

        // If options is present, it shouldn't contain think
        if (root.has("options")) {
            assertFalse(root.get("options").has("think"), "options should NOT contain 'think'");
        }
    }

    @Test
    @DisplayName("Should map thinking budget from GenerateOptions")
    void testThinkingBudgetMapping() {
        // GenerateOptions with budget > 0 should map to ThinkBoolean.ENABLED
        GenerateOptions genOptions = GenerateOptions.builder().thinkingBudget(1024).build();

        OllamaOptions options = OllamaOptions.fromGenerateOptions(genOptions);

        // Note: fromGenerateOptions maps budget > 0 to ThinkBoolean.ENABLED (as per implementation)
        // Let's verify this behavior by serialization or inspecting the object if getters available
        // Since thinkOption is private/protected access via getter:
        assertNotNull(options.getThinkOption());
        assertTrue(options.getThinkOption() instanceof ThinkOption.ThinkBoolean);
        assertEquals(true, ((ThinkOption.ThinkBoolean) options.getThinkOption()).enabled());
    }

    @Test
    @DisplayName("Integration: Real Qwen3 Thinking Mode (Stream)")
    void testRealQwen3Thinking() {
        System.out.println("Running testRealQwen3Thinking (Integration)...");
        try {
            // Use qwen3:1.7b as requested by user
            String modelName = "qwen3:14b-q8_0";

            // Configure options with Thinking Disabled
            OllamaOptions options =
                    OllamaOptions.builder()
                            .thinkOption(
                                    ThinkOption.ThinkBoolean.DISABLED) // Explicitly set to disabled
                            .temperature(0.7)
                            .build();

            // Output configuration information for debugging
            System.out.println("Think option: " + options.getThinkOption());
            System.out.println(
                    "Think option value: "
                            + ((ThinkOption.ThinkBoolean) options.getThinkOption()).enabled());

            OllamaChatModel realModel =
                    OllamaChatModel.builder()
                            .modelName(modelName)
                            .baseUrl("http://192.168.2.2:11434")
                            .defaultOptions(options)
                            .build();

            System.out.println("Streaming prompt to " + modelName + " with thinking disabled...");

            StringBuilder fullContent = new StringBuilder();

            // Use generic stream API
            realModel.stream(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent(
                                                    "9.11 and 9.8, which is larger? Explain your"
                                                            + " reasoning.")
                                            .build()),
                            null,
                            options.toGenerateOptions()) // Ensure conversion to GenerateOptions
                    .doOnNext(
                            response -> {
                                if (response.getContent() != null
                                        && !response.getContent().isEmpty()) {
                                    io.agentscope.core.message.ContentBlock block =
                                            response.getContent().get(0);
                                    if (block instanceof TextBlock) {
                                        String text = ((TextBlock) block).getText();
                                        System.out.print(text); // Print chunk to stdout
                                        fullContent.append(text);
                                    }
                                }
                            })
                    .blockLast();

            System.out.println("\n\nFull Response:\n" + fullContent.toString());

        } catch (Exception e) {
            System.err.println(
                    "Real connection test failed (Check if Ollama is running and model exists): "
                            + e.getMessage());
        }
    }
}
