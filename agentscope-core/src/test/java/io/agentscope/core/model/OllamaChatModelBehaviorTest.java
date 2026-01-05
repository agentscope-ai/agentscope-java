package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.model.ollama.ThinkOption;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;

/**
 * Unit tests for OllamaChatModel that validate behavior consistency with Python implementation.
 * These tests verify the Java implementation matches the expected behavior from Python version.
 */
@Tag("unit")
@DisplayName("OllamaChatModel Behavior Consistency Tests")
class OllamaChatModelBehaviorTest {

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
    @DisplayName("Should initialize with default parameters like Python version")
    void testInitDefaultParams() {
        // Create model similar to Python test
        OllamaChatModel defaultModel =
                OllamaChatModel.builder().modelName("qwen2.5:14b-instruct").build();

        assertEquals("qwen2.5:14b-instruct", defaultModel.getModelName());
        // Default stream is not a field in Java implementation - it's determined by which method is
        // called
    }

    @Test
    @DisplayName("Should initialize with custom parameters like Python version")
    void testInitWithCustomParams() {
        OllamaOptions ollamaOptions = OllamaOptions.builder().temperature(0.7).topP(0.9).build();

        OllamaChatModel customModel =
                OllamaChatModel.builder()
                        .modelName("qwen2.5:14b-instruct")
                        .defaultOptions(ollamaOptions)
                        .baseUrl("http://192.168.2.2:11434")
                        .build();

        assertEquals("qwen2.5:14b-instruct", customModel.getModelName());
        // Note: stream is handled differently in Java vs Python
        // In Java, streaming is determined by which method is called (chat vs stream)
    }

    @Test
    @DisplayName("Should handle regular model calls like Python version")
    void testCallWithRegularModel() {
        String jsonResponse =
                "{"
                        + "\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello! How can I"
                        + " help you?\"},\"done\":true}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build());

        ChatResponse result = model.chat(messages, OllamaOptions.builder().build());

        assertNotNull(result);
        assertFalse(result.getContent().isEmpty());
        assertTrue(result.getContent().get(0) instanceof TextBlock);
        assertEquals(
                "Hello! How can I help you?", ((TextBlock) result.getContent().get(0)).getText());

        // Verify the request was made with correct parameters
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"model\":\"" + TEST_MODEL_NAME + "\""));
        assertTrue(requestBody.contains("\"messages\""));
    }

    @Test
    @DisplayName("Should handle tool calls integration like Python version")
    void testCallWithToolsIntegration() {
        // Mock response with tool calls
        String jsonResponse =
                "{"
                        + "\"model\":\""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"message\":{"
                        + "  \"role\":\"assistant\","
                        + "  \"content\":\"I'll check the weather for you.\","
                        + "  \"tool_calls\":["
                        + "    {"
                        + "      \"function\":{"
                        + "        \"name\":\"get_weather\","
                        + "        \"arguments\":{\"location\":\"Beijing\"}"
                        + "      }"
                        + "    }"
                        + "  ]"
                        + "},"
                        + "\"done\":true"
                        + "}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("What's the weather?")
                                .build());

        ToolSchema tool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather info")
                        .parameters(
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of("location", Map.of("type", "string")),
                                        "required", Collections.singletonList("location")))
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        ChatResponse result =
                model.stream(messages, Collections.singletonList(tool), options).blockLast();

        assertNotNull(result);
        // The content structure may vary based on how the response parser handles tool calls
        // The Java implementation should parse tool calls into ToolUseBlock

        // Verify tools were included in request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"tools\""));
        assertTrue(requestBody.contains("\"name\":\"get_weather\""));
    }

    @Test
    @DisplayName("Should handle thinking functionality like Python version")
    void testCallWithThinkingEnabled() {
        // Mock response with thinking content
        String jsonResponse =
                "{"
                        + "\"model\":\"qwen2.5:14b-instruct\","
                        + "\"message\":{"
                        + "  \"role\":\"assistant\","
                        + "  \"content\":\"Here's my analysis\","
                        + "  \"thinking\":\"Let me analyze this step by step...\""
                        + "},"
                        + "\"done\":true"
                        + "}";

        // Create model with thinking enabled
        OllamaChatModel thinkingModel =
                OllamaChatModel.builder()
                        .modelName("qwen2.5:14b-instruct")
                        .baseUrl("http://192.168.2.2:11434")
                        .httpTransport(httpTransport)
                        .defaultOptions(
                                OllamaOptions.builder()
                                        .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                                        .build())
                        .build();

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("Think about this problem")
                                .build());

        ChatResponse result = thinkingModel.chat(messages, OllamaOptions.builder().build());

        assertNotNull(result);

        // Verify the request was made with thinking enabled
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"think\""));
    }

    @Test
    @DisplayName("Should handle structured model integration like Python version")
    void testCallWithStructuredModelIntegration() {
        // Mock response with structured data
        String jsonResponse =
                "{"
                        + "\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"name\\\":"
                        + " \\\"John\\\", \\\"age\\\": 30}\"},\"done\":true}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Generate a person").build());

        // Use format option to simulate structured output
        OllamaOptions options =
                OllamaOptions.builder()
                        .format("json") // This would trigger structured output in Ollama
                        .build();

        ChatResponse result = model.chat(messages, options);

        assertNotNull(result);
        assertFalse(result.getContent().isEmpty());
        assertTrue(result.getContent().get(0) instanceof TextBlock);
        String contentText = ((TextBlock) result.getContent().get(0)).getText();
        assertTrue(contentText.contains("John") && contentText.contains("30"));

        // Verify format was included in request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"format\":\"json\""));
    }

    @Test
    @DisplayName("Should handle streaming response processing like Python version")
    void testStreamingResponseProcessing() {
        // Mock streaming response chunks
        String chunk1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String chunk2 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " there!\"},\"done\":true}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(chunk1, chunk2));

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build());

        List<ChatResponse> responses =
                model.stream(messages, null, GenerateOptions.builder().build())
                        .collectList()
                        .block();

        assertNotNull(responses);
        assertFalse(responses.isEmpty());

        // Accumulate content from all responses to check complete content
        StringBuilder fullContent = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                ContentBlock block = response.getContent().get(0);
                if (block instanceof TextBlock) {
                    fullContent.append(((TextBlock) block).getText());
                }
            }
        }

        String accumulatedContent = fullContent.toString();
        assertTrue(accumulatedContent.contains("Hello") && accumulatedContent.contains("there!"));

        // The final response should also contain its specific content
        ChatResponse finalResponse = responses.get(responses.size() - 1);
        assertFalse(finalResponse.getContent().isEmpty());
        assertTrue(finalResponse.getContent().get(0) instanceof TextBlock);
        String finalContent = ((TextBlock) finalResponse.getContent().get(0)).getText();
        assertTrue(finalContent.contains("there"));

        // Verify streaming request was made
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"model\":\"" + TEST_MODEL_NAME + "\""));
    }

    @Test
    @DisplayName("Should integrate options like Python version")
    void testOptionsIntegration() {
        OllamaOptions ollamaOptions = OllamaOptions.builder().temperature(0.7).topP(0.9).build();

        OllamaChatModel modelWithOptions =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://192.168.2.2:11434")
                        .httpTransport(httpTransport)
                        .defaultOptions(ollamaOptions)
                        .build();

        String jsonResponse =
                "{"
                        + "\"model\":\""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Test response\"},"
                        + "\"done\":true"
                        + "}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        List<Msg> messages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Test").build());

        // Test with additional runtime options
        OllamaOptions runtimeOptions = OllamaOptions.builder().topK(40).build();

        modelWithOptions.chat(messages, runtimeOptions);

        // Verify options were merged and sent in request
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String requestBody = captor.getValue().getBody();
        assertTrue(requestBody.contains("\"temperature\":0.7"));
        assertTrue(requestBody.contains("\"top_p\":0.9"));
        assertTrue(requestBody.contains("\"top_k\":40"));
    }
}
